package app.kakaorecord;

import android.app.Notification;
import android.app.Person;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// 카카오톡 알림을 읽어 방·보낸 사람·내용·시각을 모은다(2026-11-08 룡 결정).
// 1:1·단톡 모두 기본 켜짐(끄면 안 모음, 2026-11-09 룡). 알림을 지우거나 답하지 않는다. 읽기만.
public class KakaoListener extends NotificationListenerService {
    static final String KAKAO = "com.kakao.talk";
    static volatile boolean connected;

    @Override
    public void onListenerConnected() { connected = true; }

    @Override
    public void onListenerDisconnected() { connected = false; }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !KAKAO.equals(sbn.getPackageName())) return;
        try {
            handle(sbn);
        } catch (Exception ignored) {
            // 알림 하나 못 읽어도 다음 알림은 받는다
        }
    }

    private void handle(StatusBarNotification sbn) throws Exception {
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // 「안 읽은 메시지 n개」 묶음 알림은 뺀다
        Bundle x = n.extras;
        if (x == null) return;

        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence conv = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        boolean styled = msgs != null && msgs.length > 0;
        boolean groupFlag = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);

        CharSequence summary = x.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        CharSequence hidden = x.getCharSequence("android.hiddenConversationTitle");

        // 마지막 메시지를 보낸 사람
        String lastSender = str(title);
        if (styled && msgs[msgs.length - 1] instanceof Bundle) {
            String s = sender((Bundle) msgs[msgs.length - 1]);
            if (!s.isEmpty()) lastSender = s;
        }
        // 방 이름이 따로 오면 단톡(보낸 사람 이름과 같으면 방 이름이 아니다).
        // 카톡은 단톡 표시(isGroupConversation)를 안 붙이고 제목에 보낸 사람을 넣을 때가 있다(2026-11-09 실제 알림으로 확인).
        // 그래서 방 번호(shortcutId, 같은 방이면 같음)로 묶고, 한 방에서 두 사람 이상이 말하면 단톡으로 본다(Store.seeRoom).
        String groupName = "";
        for (CharSequence c : new CharSequence[]{conv, sub, summary, hidden}) {
            String v = str(c);
            if (!v.isEmpty() && !v.equals(lastSender) && !v.equals(str(title))) { groupName = v; break; }
        }
        String shortcut = str(n.getShortcutId());
        if (shortcut.isEmpty()) shortcut = str(sbn.getTag());
        String key = shortcut.isEmpty() ? "n:" + (groupName.isEmpty() ? str(title) : groupName) : "s:" + shortcut;
        if (key.length() <= 2) return;

        // 카톡 방 이름 그대로(2026-11-09 룡: 「이아셀(단톡)」 말고 톡방 이름이 똑같이 나오게).
        // 카톡이 알림에 붙이는 대화 바로가기의 이름표 = 카톡 방 목록에 보이는 방 이름.
        String label = roomLabel(sbn);
        if (label.isEmpty() && !groupName.isEmpty()) label = groupName;

        Store st = Store.get(this);
        // 방 이름이 보낸 사람·제목과 다르면 단톡(1:1은 방 이름 = 상대 이름)
        boolean labelGroup = !label.isEmpty() && !label.equals(lastSender) && !label.equals(str(title));
        Store.Room room = st.seeRoom(key, label, lastSender, groupFlag || !groupName.isEmpty() || labelGroup); // 꺼진 방도 이름은 목록에 남긴다(켤 수 있게)
        if (!st.allOn() || !room.enabled) return;
        boolean group = room.group;

        // 확인용 꼬리표(내용 없음): 1:1·단톡 가르기가 맞는지 실제 알림으로 보려고
        JSONObject meta = new JSONObject();
        meta.put("tag", str(sbn.getTag()));
        meta.put("channel", str(n.getChannelId()));
        meta.put("shortcut", str(n.getShortcutId()));
        meta.put("isGroupFlag", groupFlag);
        meta.put("conv", str(conv));
        meta.put("sub", str(sub));
        meta.put("summary", str(summary));
        meta.put("hidden", str(hidden));
        meta.put("title", str(title));
        meta.put("label", label);
        meta.put("styled", styled);
        meta.put("count", styled ? msgs.length : 0);
        meta.put("keys", new org.json.JSONArray(x.keySet()));

        boolean added = false;
        if (styled) {
            // 카톡은 새 메시지가 오면 앞의 메시지까지 다시 띄운다 → 열쇠로 한 번만 넣는다
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle m = (Bundle) p;
                String who = sender(m);
                if (who.isEmpty()) who = "나"; // 보낸 사람이 없으면 내가 알림에서 답한 것
                added |= add(st, room, who, str(m.getCharSequence("text")), m.getLong("time", n.when), meta);
            }
        } else {
            added = add(st, room, lastSender, str(text), n.when > 0 ? n.when : System.currentTimeMillis(), meta);
        }
        if (added) SendJob.schedule(this);
    }

    // 알림에 붙은 대화 바로가기의 이름(안드로이드 11부터). 못 읽으면 빈 값.
    private String roomLabel(StatusBarNotification sbn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "";
        try {
            RankingMap map = getCurrentRanking();
            Ranking rk = new Ranking();
            if (map == null || !map.getRanking(sbn.getKey(), rk)) return "";
            ShortcutInfo si = rk.getConversationShortcutInfo();
            if (si == null) return "";
            String s = str(si.getLongLabel());
            return s.isEmpty() ? str(si.getShortLabel()) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String sender(Bundle m) {
        Person who = m.getParcelable("sender_person");
        if (who != null && who.getName() != null) return who.getName().toString().trim();
        CharSequence s = m.getCharSequence("sender");
        return s == null ? "" : s.toString().trim();
    }

    private static boolean add(Store st, Store.Room room, String sender, String body, long time, JSONObject meta) throws Exception {
        if (body.isEmpty()) return false;
        String k = hash(room.key + "\n" + sender + "\n" + time + "\n" + body);
        JSONObject r = new JSONObject();
        r.put("k", k);
        r.put("room", room.name);
        r.put("room_key", room.key);
        r.put("sender", sender);
        r.put("body", body);
        r.put("is_group", room.group);
        r.put("posted_at", time);
        r.put("meta", meta);
        return st.queue(k, r.toString());
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }
}
