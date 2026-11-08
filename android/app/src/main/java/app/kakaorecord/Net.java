package app.kakaorecord;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// 인터넷: 새 버전 확인(GitHub Pages)과 슈파베이스 함수 부르기(kakao_register·kakao_add)만.
final class Net {
    // APK·version.json이 있는 곳
    static final String BASE = "https://ggorotls-prog.github.io/kakao-record";
    // 슈파베이스 「룡룡룡」. 공개 키(publishable)라 앱에 넣어도 된다: 표는 잠겨 있고, 허락된 폰 열쇠로 함수만 부를 수 있다.
    static final String SB = "https://trlnatdkebjvgiqneulv.supabase.co";
    static final String SB_KEY = "sb_publishable_BqE1VCDg50oi9zCflCjMuw_wYF55CTj";

    static final class Result {
        final int status;
        final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
    }

    private Net() {}

    static Result get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        try {
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    // 슈파베이스 함수 부르기: POST /rest/v1/rpc/<이름>
    static Result rpc(String fn, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SB + "/rest/v1/rpc/" + fn).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("apikey", SB_KEY);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static Result read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream s = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (s != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) > 0) buf.write(b, 0, n);
        }
        return new Result(code, buf.toString(StandardCharsets.UTF_8.name()));
    }
}
