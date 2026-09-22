package ai.jev.assist;

import android.content.Context;
import android.content.SharedPreferences;

/** 单点配置存取：判定端点、模型名、密钥、抓取条数。 */
public final class Prefs {

    private static final String FILE = "jev_assist";
    private static final String K_ENDPOINT = "endpoint";
    private static final String K_MODEL = "model";
    private static final String K_KEY = "api_key";
    private static final String K_LINES = "context_lines";
    private static final String K_MODE = "mode";

    /** 判定跑在手机里，用打包进 APK 的本地模型，不联网。 */
    public static final String MODE_LOCAL = "local";
    /** 判定发给一个 Jev 兼容的 /v1/systemone 端点。 */
    public static final String MODE_REMOTE = "remote";

    /** 远端模式的默认端点，装好后可在设置里改。 */
    public static final String DEFAULT_ENDPOINT = "http://192.168.31.217:8890/v1/systemone";
    public static final String DEFAULT_MODEL = "jev-latest";

    private Prefs() {
    }

    public static String mode(Context c) {
        return sp(c).getString(K_MODE, MODE_LOCAL);
    }

    public static void setMode(Context c, String v) {
        sp(c).edit().putString(K_MODE, v).apply();
    }

    public static boolean isLocal(Context c) {
        return MODE_LOCAL.equals(mode(c));
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String endpoint(Context c) {
        return sp(c).getString(K_ENDPOINT, DEFAULT_ENDPOINT);
    }

    public static void setEndpoint(Context c, String v) {
        sp(c).edit().putString(K_ENDPOINT, v.trim()).apply();
    }

    public static String model(Context c) {
        return sp(c).getString(K_MODEL, DEFAULT_MODEL);
    }

    public static void setModel(Context c, String v) {
        sp(c).edit().putString(K_MODEL, v.trim()).apply();
    }

    public static String apiKey(Context c) {
        return sp(c).getString(K_KEY, "");
    }

    public static void setApiKey(Context c, String v) {
        sp(c).edit().putString(K_KEY, v.trim()).apply();
    }

    /** 抓取最近多少条聊天行作为上下文。 */
    public static int contextLines(Context c) {
        return sp(c).getInt(K_LINES, 12);
    }

    public static void setContextLines(Context c, int v) {
        sp(c).edit().putInt(K_LINES, Math.max(2, Math.min(60, v))).apply();
    }
}
