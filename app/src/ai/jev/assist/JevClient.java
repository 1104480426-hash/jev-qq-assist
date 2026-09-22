package ai.jev.assist;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Jev 兼容判定客户端。
 *
 * <p>Jev 只有一种调用形态：一次 POST 换回一组类型化判定，没有 chat/completions，
 * 不生成文本。请求体为 model / state / questions，响应体为 answers。
 */
public final class JevClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 45000;

    private JevClient() {
    }

    /** 判定结果：原始 JSON 加一次是否成功的标记。 */
    public static final class Result {
        public final boolean ok;
        public final JSONObject answers;
        public final String model;
        public final String error;
        public final long elapsedMs;

        private Result(boolean ok, JSONObject answers, String model, String error, long elapsedMs) {
            this.ok = ok;
            this.answers = answers;
            this.model = model;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        static Result success(JSONObject answers, String model, long elapsedMs) {
            return new Result(true, answers, model, null, elapsedMs);
        }

        static Result failure(String error, long elapsedMs) {
            return new Result(false, null, null, error, elapsedMs);
        }
    }

    /**
     * 向判定端点发一次请求。
     *
     * @param endpoint 形如 http://host:port/v1/systemone
     * @param apiKey   可为空；为空时不带 Authorization 头
     * @param body     model / state / questions 三件套
     */
    public static Result decide(String endpoint, String apiKey, JSONObject body) {
        long started = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && apiKey.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            byte[] payload = body.toString().getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                close(out);
            }

            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            long elapsed = System.currentTimeMillis() - started;

            if (code < 200 || code >= 300) {
                return Result.failure("HTTP " + code + " " + truncate(text), elapsed);
            }
            JSONObject json = new JSONObject(text);
            JSONObject answers = json.optJSONObject("answers");
            if (answers == null) {
                return Result.failure("响应里没有 answers 字段：" + truncate(text), elapsed);
            }
            return Result.success(answers, json.optString("model", "?"), elapsed);
        } catch (Exception e) {
            return Result.failure(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - started);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
        } finally {
            close(in);
        }
        return new String(buf.toByteArray(), UTF8);
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
            // 关闭失败不影响结果
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "...";
    }
}
