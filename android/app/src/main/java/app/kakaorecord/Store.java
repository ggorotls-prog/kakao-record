package app.kakaorecord;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

// 폰 안에 두는 것: 전체 켜기, 방 목록(이름·1:1/단톡·켜짐), 보낼 기록(인터넷이 끊겨도 모아 둠), 이 폰의 열쇠.
// 방 목록에는 이름만 남는다. 꺼진 방의 내용은 어디에도 저장하지 않는다.
final class Store extends SQLiteOpenHelper {
    private static Store inst;
    private static final String PREFS = "kakao-record";

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    private Store(Context c) {
        super(c, "kakao.db", null, 1);
        ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // rkey = (p: 1:1 / g: 단톡) + 방 이름
        db.execSQL("create table rooms (rkey text primary key, name text, grp integer, enabled integer, last_seen integer, count integer default 0)");
        // k = 같은 메시지를 두 번 넣지 않으려는 열쇠. sent = 보냈으면 1
        db.execSQL("create table outbox (k text primary key, json text, at integer, sent integer default 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    // ---- 설정 ----

    private SharedPreferences p() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    boolean allOn() { return p().getBoolean("all", true); }
    void setAllOn(boolean on) { p().edit().putBoolean("all", on).apply(); }

    long lastSaved() { return p().getLong("lastSaved", 0); }
    void setLastSaved(long t) { p().edit().putLong("lastSaved", t).apply(); }

    // ok(허락됨) / wait(Claude 허락 기다림) / 빈 값(아직 모름)
    String link() { return p().getString("link", ""); }
    void setLink(String s) { p().edit().putString("link", s).apply(); }

    // 이 폰의 열쇠(처음 한 번 만들고 바꾸지 않음). 서버에는 해시만 남는다.
    synchronized String deviceKey() {
        String k = p().getString("key", null);
        if (k != null) return k;
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        k = sb.toString();
        p().edit().putString("key", k).apply();
        return k;
    }

    // ---- 방 ----

    static final class Room {
        String key, name;
        boolean group, enabled;
        long lastSeen;
        int count;
    }

    // 알림이 온 방을 적어 두고, 이 방을 모아도 되는지 돌려준다. 처음 본 방: 1:1은 켜짐, 단톡은 꺼짐.
    synchronized boolean seeRoom(String name, boolean group) {
        String key = (group ? "g:" : "p:") + name;
        SQLiteDatabase db = getWritableDatabase();
        boolean enabled;
        try (Cursor c = db.rawQuery("select enabled from rooms where rkey=?", new String[]{key})) {
            if (c.moveToFirst()) {
                enabled = c.getInt(0) == 1;
                db.execSQL("update rooms set last_seen=?, count=count+1 where rkey=?", new Object[]{System.currentTimeMillis(), key});
                return enabled;
            }
        }
        enabled = !group;
        ContentValues v = new ContentValues();
        v.put("rkey", key);
        v.put("name", name);
        v.put("grp", group ? 1 : 0);
        v.put("enabled", enabled ? 1 : 0);
        v.put("last_seen", System.currentTimeMillis());
        v.put("count", 1);
        db.insertWithOnConflict("rooms", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return enabled;
    }

    synchronized void setRoom(String key, boolean on) {
        getWritableDatabase().execSQL("update rooms set enabled=? where rkey=?", new Object[]{on ? 1 : 0, key});
    }

    synchronized List<Room> rooms() {
        List<Room> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("select rkey, name, grp, enabled, last_seen, count from rooms order by last_seen desc", null)) {
            while (c.moveToNext()) {
                Room r = new Room();
                r.key = c.getString(0);
                r.name = c.getString(1);
                r.group = c.getInt(2) == 1;
                r.enabled = c.getInt(3) == 1;
                r.lastSeen = c.getLong(4);
                r.count = c.getInt(5);
                out.add(r);
            }
        }
        return out;
    }

    // ---- 보낼 기록 ----

    // 새 메시지면 true(같은 열쇠가 이미 있으면 넣지 않음)
    synchronized boolean queue(String k, String json) {
        ContentValues v = new ContentValues();
        v.put("k", k);
        v.put("json", json);
        v.put("at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    // 아직 안 보낸 것(오래된 것부터). {k, json}
    synchronized List<String[]> pending(int limit) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("select k, json from outbox where sent=0 order by at limit " + limit, null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        }
        return out;
    }

    synchronized int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery("select count(*) from outbox where sent=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // 보낸 것은 내용을 지우고 열쇠만 7일 남긴다(카톡이 같은 메시지를 다시 띄워도 또 넣지 않게)
    synchronized void markSent(List<String> keys) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String k : keys) db.execSQL("update outbox set sent=1, json='' where k=?", new Object[]{k});
            db.execSQL("delete from outbox where sent=1 and at < ?", new Object[]{System.currentTimeMillis() - 7L * 24 * 3600_000});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
