package app.kakaorecord;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 화면: 맨 위 전체 켜기·끄기, 마지막 저장 시각, 알림이 온 방 목록(이름만)과 방마다 스위치.
// 1:1은 기본 켜짐(빼고 싶은 사람만 끔), 단톡은 켠 방만 모은다(2026-11-08 룡 결정).
public class MainActivity extends Activity {
    static volatile boolean visible;

    private LinearLayout top, status, people, groups;
    private Switch all;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            showStatus();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(40));
        sv.addView(col);
        setContentView(sv);

        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        col.addView(top);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("카톡 기록", 24, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        all = new Switch(this);
        all.setChecked(Store.get(this).allOn());
        all.setOnCheckedChangeListener((v, on) -> { Store.get(this).setAllOn(on); showStatus(); });
        head.addView(all);
        col.addView(head, m(0, dp(4)));

        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        col.addView(status, m(0, dp(20)));

        col.addView(text("1:1 대화", 20, true), m(0, dp(2)));
        col.addView(gray("기본으로 모아요. 빼고 싶은 사람만 끄세요."), m(0, dp(8)));
        people = new LinearLayout(this);
        people.setOrientation(LinearLayout.VERTICAL);
        col.addView(people, m(0, dp(20)));

        col.addView(text("단톡방", 20, true), m(0, dp(2)));
        col.addView(gray("켠 방만 모아요. 꺼진 방은 내용을 저장하지 않아요."), m(0, dp(8)));
        groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.VERTICAL);
        col.addView(groups, m(0, dp(20)));

        col.addView(gray("버전 " + BuildConfig.VERSION_NAME + " · 사진·파일은 알림에 뜬 글자만 저장해요"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        top.removeAllViews();
        boolean listen = ReplacedReceiver.enabled(this);
        if (!listen) top.addView(row("알림 읽기가 꺼져 있어요 — 「카톡 기록」을 켜 주세요", "켜기",
                v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        else try { NotificationListenerService.requestRebind(new ComponentName(this, KakaoListener.class)); } catch (Exception ignored) {}
        // 삼성 폰이 「안 쓰는 앱 재우기」로 알림 읽기를 멈추지 않게: 배터리 사용을 「제한 없음」으로(앱 차단 앱과 같음)
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
            top.addView(row("배터리 「제한 없음」을 켜면 카톡 기록이 멈추지 않아요", "켜기",
                    v -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))));
        if (!kakaoInstalled()) top.addView(row("이 폰에 카카오톡이 없어요", "확인", v -> {}));

        showRooms();
        showStatus();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 5000);
        // 서버에 이 폰이 허락됐는지 보고, 모아 둔 게 있으면 보낸다
        new Thread(() -> {
            SendJob.register(getApplicationContext());
            SendJob.send(getApplicationContext());
            runOnUiThread(this::showStatus);
        }).start();

        // 앱을 열 때마다 새 버전이 있나 본다(업무 기록 앱과 같은 방식, 2026-11-07 룡 결정)
        boolean retry = backFromSecurity;
        backFromSecurity = false;
        new Thread(() -> {
            int latest = Updater.latest(getApplicationContext());
            if (latest <= BuildConfig.VERSION_CODE) return;
            if (!Updater.canInstall(this)) { runOnUiThread(this::askInstallPermission); return; }
            // 보안 화면에서 자동 차단을 끄고 돌아왔으면 바로 설치
            if (retry) { Updater.checkAndInstall(getApplicationContext(), true, true); return; }
            if (!Updater.askedRecently(getApplicationContext(), latest)) runOnUiThread(this::askUpdate);
        }).start();
    }

    @Override
    protected void onPause() {
        visible = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private boolean kakaoInstalled() {
        try { getPackageManager().getPackageInfo(KakaoListener.KAKAO, 0); return true; } catch (Exception e) { return false; }
    }

    private void showStatus() {
        Store st = Store.get(this);
        status.removeAllViews();
        long t = st.lastSaved();
        int wait = st.pendingCount();
        String last = t == 0 ? "아직 저장한 적 없어요" : "마지막 저장 " + new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date(t));
        status.addView(text(st.allOn() ? last : "꺼져 있어요 — 아무것도 모으지 않아요", 15, false));
        String link = st.link();
        if (wait > 0) status.addView(gray("보낼 것 " + wait + "개" + ("wait".equals(link) ? " · 연결 허락을 기다려요(Claude가 허락하면 보내져요)" : " · 인터넷이 되면 보내요")));
        else if ("wait".equals(link)) status.addView(gray("연결 허락을 기다려요(Claude가 허락하면 저장돼요)"));
        else if ("full".equals(link)) status.addView(gray("연결 대기 칸이 꽉 찼어요 — Claude에게 알려 주세요"));
    }

    private void showRooms() {
        people.removeAllViews();
        groups.removeAllViews();
        List<Store.Room> rooms = Store.get(this).rooms();
        int np = 0, ng = 0;
        for (Store.Room r : rooms) {
            Switch s = new Switch(this);
            s.setText(r.name);
            s.setTextSize(16);
            s.setPadding(dp(4), dp(10), 0, dp(10));
            s.setChecked(r.enabled);
            s.setOnCheckedChangeListener((v, on) -> Store.get(this).setRoom(r.key, on));
            if (r.group) { groups.addView(s); ng++; } else { people.addView(s); np++; }
        }
        if (np == 0) people.addView(gray("아직 알림이 온 1:1 대화가 없어요"));
        if (ng == 0) groups.addView(gray("아직 알림이 온 단톡방이 없어요"));
    }

    private boolean backFromSecurity;

    // 새 버전이 있을 때: 「업데이트」를 누르면 보안 화면을 연다. 자동 차단을 끄고 돌아오면 설치가 시작된다.
    private void askUpdate() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("업데이트하려면 삼성 「자동 차단」을 잠깐 꺼야 해요.\n\n「업데이트」를 누르면 보안 화면이 열려요. 「자동 차단」을 끄고 뒤로 오면 업데이트가 시작돼요.\n(자동 차단은 30분 뒤 저절로 다시 켜져요)")
                .setPositiveButton("업데이트", (d, w) -> {
                    backFromSecurity = true;
                    openSecuritySettings();
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void openSecuritySettings() {
        for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (Exception ignored) {}
        }
    }

    // 처음 한 번: 이 앱이 스스로 업데이트할 수 있게 「이 출처 허용」을 켜 달라고 한다
    private void askInstallPermission() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("앞으로 저절로 업데이트되게, 다음 화면에서 「이 출처 허용」을 켜 주세요. 한 번만 하면 돼요.")
                .setPositiveButton("켜러 가기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    // ---- 작은 도우미 ----

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private LinearLayout row(String msg, String btn, View.OnClickListener on) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(4), dp(12), dp(4));
        r.setBackgroundColor(Color.parseColor("#FEF3C7"));
        r.addView(text(msg, 14, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setText(btn);
        b.setAllCaps(false);
        b.setOnClickListener(on);
        r.addView(b);
        r.setLayoutParams(m(0, dp(8)));
        return r;
    }

    private TextView gray(String s) {
        TextView t = text(s, 13, false);
        t.setTextColor(Color.parseColor("#6B7280"));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(bold ? Color.parseColor("#111827") : Color.parseColor("#374151"));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams m(int topM, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topM, 0, bottom);
        return lp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
