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
    static final String UNKNOWN_GROUP = "이름 모르는 단톡방";

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    private Store(Context c) {
        super(c, "kakao.db", null, 4);
        ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createRooms(db);
        // k = 같은 메시지를 두 번 넣지 않으려는 열쇠. sent = 보냈으면 1
        db.execSQL("create table outbox (k text primary key, json text, at integer, sent integer default 0)");
    }

    // rkey = s:카톡 방 번호(알림의 shortcutId, 같은 방이면 같음) — 없으면 n:이름
    // senders = 이 방에서 본 보낸 사람(줄바꿈). 둘 이상이면 단톡. touched = 룡이 스위치를 만졌음
    private static void createRooms(SQLiteDatabase db) {
        db.execSQL("create table rooms (rkey text primary key, name text, grp integer, enabled integer, touched integer default 0, senders text default '', last_seen integer, count integer default 0)");
    }

    // 1 → 2 (2026-11-09): 카톡이 단톡 알림에 단톡 표시를 안 붙여 사람마다 1:1로 잡혔다. 방 번호로 다시 모은다(방 목록만 새로).
    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            db.execSQL("drop table if exists rooms");
            createRooms(db);
        }
        // 2 → 3 (2026-11-09 룡): 「이아셀 (단톡)」처럼 사람 이름으로 지은 단톡방 이름을 없앤다.
        // 안 만진 방은 지우고(다음 알림 때 카톡 방 이름으로 다시 생김), 켜 둔 방은 켬을 지키고 이름만 「이름 모르는 단톡방」으로.
        // 3 → 4: 3에서 꼬리만 떼어 「이아셀」처럼 사람 이름이 남은 단톡방도 고친다.
        if (o == 2 || o == 3) {
            db.execSQL("delete from rooms where name like '% (단톡)' and touched=0");
            db.execSQL("update rooms set name=? where name like '% (단톡)' or (grp=1 and instr(char(10)||senders||char(10), char(10)||name||char(10)) > 0)",
                    new Object[]{UNKNOWN_GROUP});
        }
    }

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

    // 알림이 온 방을 적어 두고 그 방의 지금 모습(이름·단톡·켜짐)을 돌려준다.
    // 처음 본 방: 1:1은 켜짐, 단톡은 꺼짐. 1:1로 알던 방에 다른 사람이 말하면 단톡으로 바꾸고, 룡이 안 만졌으면 끈다.
    synchronized Room seeRoom(String key, String groupName, String sender, boolean groupHint) {
        SQLiteDatabase db = getWritableDatabase();
        Room r = null;
        String senders = "";
        boolean touched = false;
        try (Cursor c = db.rawQuery("select name, grp, enabled, touched, senders from rooms where rkey=?", new String[]{key})) {
            if (c.moveToFirst()) {
                r = new Room();
                r.key = key;
                r.name = c.getString(0);
                r.group = c.getInt(1) == 1;
                r.enabled = c.getInt(2) == 1;
                touched = c.getInt(3) == 1;
                senders = c.getString(4) == null ? "" : c.getString(4);
            }
        }
        List<String> who = new ArrayList<>();
        for (String w : senders.split("\n")) if (!w.isEmpty()) who.add(w);
        boolean real = sender != null && !sender.isEmpty() && !"나".equals(sender);
        if (real && !who.contains(sender) && who.size() < 6) who.add(sender);
        boolean group = groupHint || who.size() >= 2 || (r != null && r.group);
        String name;
        if (!groupName.isEmpty()) name = groupName;
        // 단톡방에는 단톡방 이름만, 사람 이름은 절대 안 쓴다(2026-11-09 룡).
        // 방 이름을 못 읽었으면: 전에 읽은 방 이름이 있으면 그대로, 없으면 「이름 모르는 단톡방」.
        else if (group) name = (r != null && r.name != null && !r.name.isEmpty() && !who.contains(r.name) && !r.name.endsWith(" (단톡)")) ? r.name : UNKNOWN_GROUP;
        else name = r != null ? r.name : (real ? sender : "알 수 없음");
        if (r == null) {
            r = new Room();
            r.key = key;
            r.enabled = !group;
            ContentValues v = new ContentValues();
            v.put("rkey", key);
            v.put("grp", group ? 1 : 0);
            v.put("enabled", r.enabled ? 1 : 0);
            v.put("name", name);
            v.put("senders", String.join("\n", who));
            v.put("last_seen", System.currentTimeMillis());
            v.put("count", 1);
            db.insertWithOnConflict("rooms", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            if (group && !r.group && !touched) r.enabled = false; // 단톡으로 드러나면 켠 방만 모으기
            db.execSQL("update rooms set name=?, grp=?, enabled=?, senders=?, last_seen=?, count=count+1 where rkey=?",
                    new Object[]{name, group ? 1 : 0, r.enabled ? 1 : 0, String.join("\n", who), System.currentTimeMillis(), key});
        }
        r.name = name;
        r.group = group;
        return r;
    }

    synchronized void setRoom(String key, boolean on) {
        getWritableDatabase().execSQL("update rooms set enabled=?, touched=1 where rkey=?", new Object[]{on ? 1 : 0, key});
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
