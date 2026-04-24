package BotValui.Service.fonbet;

import lombok.Value;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Лёгкая проверка доступности зеркала.
 * HEAD -> GET Range: bytes=0-0 (без gzip)
 */
public final class HttpProbe {

    @Value
    public static class Result {
        boolean up;
        int status;
        long latencyMs;
        String error;
    }

    /** Проверяем URL: возвращаем true, если код 200 (или 206 при Range). */
    public static Result probe200(String url, int connectTimeoutMs, int readTimeoutMs, int maxRedirects) {
        long start = System.nanoTime();
        try {
            HttpURLConnection c = open(url, "HEAD", connectTimeoutMs, readTimeoutMs);
            int code = c.getResponseCode();
            if (code == 405) { // HEAD не поддерживается
                safeClose(c);
                c = open(url, "GET", connectTimeoutMs, readTimeoutMs);
                c.setRequestProperty("Range", "bytes=0-0");
                code = c.getResponseCode();
                if (code == 206) code = 200;
            }
            safeClose(c);
            boolean ok = code == 200;
            long dur = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new Result(ok, code, dur, ok ? null : "status=" + code);
        } catch (Exception e) {
            long dur = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new Result(false, -1, dur, e.toString());
        }
    }

    private static HttpURLConnection open(String url, String method, int cto, int rto) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(cto);
        conn.setReadTimeout(rto);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept-Encoding", "identity"); // без gzip
        conn.setRequestProperty("Connection", "close");
        return conn;
    }

    private static void safeClose(HttpURLConnection c) {
        try (InputStream is = c.getInputStream()) {} catch (Exception ignore) {}
        try (InputStream es = c.getErrorStream()) {} catch (Exception ignore) {}
        c.disconnect();
    }
}
