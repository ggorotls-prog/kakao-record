package app.kakaorecord;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// 카카오톡 알림을 읽어 방·보낸 사람·내용·시각을 모은다(2026-11-08 룡 결정).
// 1:1은 기본 켜짐(끄면 안 모음), 단톡은 켠 방만. 알림을 지우거나 답하지 않는다. 읽기만.
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

        // 1:1인지 단톡인지: 메시지 꼴 알림이면 안드로이드의 「단톡」 표시, 아니면 작은 글씨(방 이름)가 있는지로.
        boolean group = styled ? groupFlag : (sub != null && sub.length() > 0);
        String room = str(group ? first(conv, sub, title) : first(conv, title));
        if (room.isEmpty()) return;

        Store st = Store.get(this);
        boolean roomOn = st.seeRoom(room, group); // 방 이름은 꺼져 있어도 목록에 남긴다(켤 수 있게)
        if (!st.allOn() || !roomOn) return;

        // 확인용 꼬리표(내용 없음): 1:1·단톡 가르기가 맞는지 실제 알림으로 보려고
        JSONObject meta = new JSONObject();
        meta.put("tag", str(sbn.getTag()));
        meta.put("id", sbn.getId());
        meta.put("channel", str(n.getChannelId()));
        meta.put("shortcut", str(n.getShortcutId()));
        meta.put("isGroupFlag", groupFlag);
        meta.put("conv", str(conv));
        meta.put("sub", str(sub));
        meta.put("title", str(title));
        meta.put("styled", styled);
        meta.put("count", styled ? msgs.length : 0);

        boolean added = false;
        if (styled) {
            // 카톡은 새 메시지가 오면 앞의 메시지까지 다시 띄운다 → 열쇠로 한 번만 넣는다
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle m = (Bundle) p;
                CharSequence body = m.getCharSequence("text");
                long time = m.getLong("time", n.when);
                String sender = "";
                Person who = m.getParcelable("sender_person");
                if (who != null && who.getName() != null) sender = who.getName().toString();
                else if (m.getCharSequence("sender") != null) sender = m.getCharSequence("sender").toString();
                if (sender.isEmpty()) sender = group ? "" : "나"; // 보낸 사람이 없으면 내가 알림에서 답한 것
                added |= add(st, room, group, sender, str(body), time, meta);
            }
        } else {
            String sender = group ? str(title) : room;
            added = add(st, room, group, sender, str(text), n.when > 0 ? n.when : System.currentTimeMillis(), meta);
        }
        if (added) SendJob.schedule(this);
    }

    private static boolean add(Store st, String room, boolean group, String sender, String body, long time, JSONObject meta) throws Exception {
        if (body.isEmpty()) return false;
        String k = hash((group ? "g:" : "p:") + room + "\n" + sender + "\n" + time + "\n" + body);
        JSONObject r = new JSONObject();
        r.put("k", k);
        r.put("room", room);
        r.put("room_key", (group ? "g:" : "p:") + room);
        r.put("sender", sender);
        r.put("body", body);
        r.put("is_group", group);
        r.put("posted_at", time);
        r.put("meta", meta);
        return st.queue(k, r.toString());
    }

    private static String first(CharSequence... a) {
        for (CharSequence s : a) if (s != null && s.toString().trim().length() > 0) return s.toString();
        return "";
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }
}
