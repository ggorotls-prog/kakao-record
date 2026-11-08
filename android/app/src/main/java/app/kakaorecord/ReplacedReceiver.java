package app.kakaorecord;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

// 업데이트·재부팅 뒤: 못 보낸 기록을 보내고, 알림 읽기가 꺼져 있으면 알림으로 알린다(앱 차단 앱과 같은 생각).
public class ReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && !Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        SendJob.schedule(ctx);
        if (enabled(ctx)) {
            try { NotificationListenerService.requestRebind(new ComponentName(ctx, KakaoListener.class)); } catch (Exception ignored) {}
            return;
        }
        PendingIntent tap = PendingIntent.getActivity(ctx, 7,
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Updater.notify(ctx, "카톡 기록이 멈춰 있어요", "눌러서 알림 읽기 「카톡 기록」을 켜 주세요", tap);
    }

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName() + "/");
    }
}
