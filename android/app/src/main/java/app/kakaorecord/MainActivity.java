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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 화면: 맨 위 전체 켜기·끄기, 마지막 저장 시각, 알림이 온 방 목록(이름만)과 방마다 스위치.
// 1:1·단톡 모두 기본 켜짐(빼고 싶은 방만 끔). 안 받을 방은 룡이 카톡에서 알림을 끈다(2026-11-09 룡 결정, 11-08 「단톡은 켠 방만」을 바꿈).
public class MainActivity extends Activity {
    static volatile boolean visible;

    private LinearLayout top, status, people, groups;
    // 색: 업무 기록 사이트와 같은 값(밝은·어두운 화면)
    private boolean dark;
    private int BG, CARD, INK, SUB, LINE, ACCENT, ON_ACCENT, WARN_BG, WARN;
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

        dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BG = c(dark ? "#161615" : "#f6f5f1");
        CARD = c(dark ? "#22221f" : "#ffffff");
        INK = c(dark ? "#ecebe6" : "#1f1f1d");
        SUB = c(dark ? "#a3a29b" : "#6b6a64");
        LINE = c(dark ? "#34332f" : "#e4e2db");
        ACCENT = c(dark ? "#6fb39f" : "#2f5d50");
        ON_ACCENT = c(dark ? "#10201b" : "#ffffff");
        WARN_BG = c(dark ? "#352a15" : "#fff4e0");
        WARN = c(dark ? "#f0b350" : "#b26a00");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(40));
        sv.addView(col);
        setContentView(sv);

        // 머리: 이름 + 마지막 저장
        col.addView(text("카톡 기록", 26, true), m(0, dp(2)));
        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        col.addView(status, m(0, dp(16)));

        // 해야 할 설정(필요할 때만)
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        col.addView(top);

        // 전체 켜기
        LinearLayout master = card();
        all = new Switch(this);
        all.setChecked(Store.get(this).allOn());
        all.setOnCheckedChangeListener((v, on) -> { Store.get(this).setAllOn(on); showStatus(); });
        master.addView(switchRow("카톡 모으기", "끄면 아무것도 모으지 않아요", all));
        col.addView(master, m(0, dp(24)));

        col.addView(section("1:1 대화", "기본으로 모아요. 빼고 싶은 사람만 끄세요"));
        people = card();
        col.addView(people, m(0, dp(24)));

        col.addView(section("단톡방", "기본으로 모아요. 빼고 싶은 방만 끄세요"));
        groups = card();
        col.addView(groups, m(0, dp(24)));

        TextView foot = text("버전 " + BuildConfig.VERSION_NAME + " · 사진·파일은 알림에 뜬 글자만 저장해요", 12, false);
        foot.setTextColor(SUB);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, m(dp(8), 0));
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
        if (top.getChildCount() > 0) top.addView(new View(this), m(0, dp(14)));

        SendJob.schedulePeriodic(this);
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
        String link = st.link();
        String line;
        if (!st.allOn()) line = "꺼져 있어요";
        else if (t == 0) line = "아직 저장한 적 없어요";
        else line = "마지막 저장 " + new SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(new Date(t));
        if (wait > 0) line += " · 보낼 것 " + wait + "개";
        TextView tv = text(line, 14, false);
        tv.setTextColor(SUB);
        status.addView(tv);
        String why = "wait".equals(link) ? "연결 허락을 기다려요 — Claude가 허락하면 저장돼요"
                : "full".equals(link) ? "연결 대기 칸이 꽉 찼어요 — Claude에게 알려 주세요"
                : wait > 0 ? "인터넷이 되면 보내요" : "";
        if (!why.isEmpty()) {
            TextView w = text(why, 13, false);
            w.setTextColor(WARN);
            status.addView(w, m(dp(2), 0));
        }
    }

    private void showRooms() {
        people.removeAllViews();
        groups.removeAllViews();
        List<Store.Room> rooms = Store.get(this).rooms();
        for (Store.Room r : rooms) {
            Switch s = new Switch(this);
            s.setChecked(r.enabled);
            s.setOnCheckedChangeListener((v, on) -> Store.get(this).setRoom(r.key, on));
            LinearLayout box = r.group ? groups : people;
            if (box.getChildCount() > 0) box.addView(divider());
            box.addView(switchRow(r.name, null, s));
        }
        if (people.getChildCount() == 0) people.addView(empty("아직 알림이 온 1:1 대화가 없어요"));
        if (groups.getChildCount() == 0) groups.addView(empty("아직 알림이 온 단톡방이 없어요"));
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

    // 해야 할 설정 한 줄: 노란 카드 + 오른쪽 버튼
    private LinearLayout row(String msg, String btn, View.OnClickListener on) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(12), dp(12));
        r.setBackground(round(WARN_BG, 0, 14));
        TextView t = text(msg, 14, false);
        t.setTextColor(dark ? INK : c("#5c3a00"));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView b = text(btn, 14, true);
        b.setTextColor(ON_ACCENT);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setBackground(round(ACCENT, 0, 999));
        b.setOnClickListener(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), 0, 0, 0);
        r.addView(b, lp);
        r.setLayoutParams(m(0, dp(10)));
        return r;
    }

    private LinearLayout card() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(round(CARD, LINE, 16));
        return k;
    }

    private LinearLayout section(String title, String sub) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(4), 0, dp(4), dp(8));
        s.addView(text(title, 17, true));
        TextView t = text(sub, 13, false);
        t.setTextColor(SUB);
        s.addView(t);
        return s;
    }

    // 카드 안 한 줄: 왼쪽 글, 오른쪽 스위치(강조색). 줄 어디를 눌러도 켜고 끈다
    private LinearLayout switchRow(String title, String sub, Switch sw) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(12), dp(14));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 16, false));
        if (sub != null) {
            TextView t = text(sub, 13, false);
            t.setTextColor(SUB);
            txt.addView(t);
        }
        r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{ACCENT, dark ? c("#bdbcb6") : c("#ffffff")}));
        sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{withAlpha(ACCENT, 110), dark ? c("#4a4944") : c("#cfcdc5")}));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMargins(dp(16), 0, 0, 0);
        d.setLayoutParams(lp);
        return d;
    }

    private TextView empty(String s) {
        TextView t = text(s, 14, false);
        t.setTextColor(SUB);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(INK);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable round(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(0.5f)), stroke);
        return g;
    }

    private static int c(String hex) { return Color.parseColor(hex); }

    private static int withAlpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }

    private LinearLayout.LayoutParams m(int topM, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topM, 0, bottom);
        return lp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
