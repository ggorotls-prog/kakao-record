package app.kakaorecord;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// 모아 둔 카톡 기록을 슈파베이스로 보낸다. 인터넷이 될 때만 돌고, 못 보내면 폰에 남겨 두었다가 다시 보낸다.
public class SendJob extends JobService {
    private static final int JOB = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB, new ComponentName(c, SendJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true);
        try { js.schedule(b.build()); } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean again = !send(getApplicationContext());
            jobFinished(params, again);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    // 이 폰을 서버에 알린다. ok / wait / full / bad, 인터넷이 안 되면 ""
    static String register(Context c) {
        Store st = Store.get(c);
        try {
            JSONObject body = new JSONObject();
            body.put("p_key", st.deviceKey());
            body.put("p_name", Build.MANUFACTURER + " " + Build.MODEL);
            Net.Result r = Net.rpc("kakao_register", body.toString());
            if (r.status != 200) return "";
            String s = r.body.replace("\"", "").trim();
            st.setLink(s);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    // 다 보냈거나 보낼 게 없으면 true. 끊겼거나 허락 대기면 false(나중에 다시)
    static synchronized boolean send(Context c) {
        Store st = Store.get(c);
        for (int round = 0; round < 20; round++) {
            List<String[]> rows = st.pending(200);
            if (rows.isEmpty()) return true;
            try {
                JSONArray arr = new JSONArray();
                List<String> keys = new ArrayList<>();
                for (String[] r : rows) {
                    arr.put(new JSONObject(r[1]));
                    keys.add(r[0]);
                }
                JSONObject body = new JSONObject();
                body.put("p_key", st.deviceKey());
                body.put("p_rows", arr);
                Net.Result res = Net.rpc("kakao_add", body.toString());
                if (res.status != 200) return false;
                JSONObject o = new JSONObject(res.body);
                if (!o.optBoolean("ok")) {
                    // 처음이면 등록부터. Claude가 허락하기 전까지는 폰에 모아 둔다
                    if ("unknown".equals(o.optString("why"))) register(c);
                    else st.setLink(o.optString("why"));
                    return false;
                }
                st.setLink("ok");
                st.markSent(keys);
                st.setLastSaved(System.currentTimeMillis());
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
