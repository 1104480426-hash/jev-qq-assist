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

    /**
     * 默认端点。指向 TypeSafe 官方：它是这批判定里质量最好的来源，
     * 本地模型只作为没有 key 或断网时的兜底。要接自建服务在这里改。
     */
    public static final String DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    public static final String DEFAULT_MODEL = "jev-latest";

    private Prefs() {
    }

    public static String mode(Context c) {
        return sp(c).getString(K_MODE, MODE_REMOTE);
    }

    public static void setMode(Context c, String v) {
        sp(c).edit().putString(K_MODE, v).apply();
    }

    /**
     * 实际是否走本地判定。
     *
     * <p>选了远端却还没填 key 时退回本地：否则刚装好的用户点一下只会拿到一个
     * 鉴权错误，而设备上明明装着能用的模型。填上 key 后自动切回远端。
     */
    public static boolean isLocal(Context c) {
        if (MODE_REMOTE.equals(mode(c)) && apiKey(c).length() == 0) {
            return true;
        }
        return MODE_LOCAL.equals(mode(c));
    }

    /** 置了远端但因为没有 key 而被降级——用于在界面上说清楚发生了什么。 */
    public static boolean isRemoteDegraded(Context c) {
        return MODE_REMOTE.equals(mode(c)) && apiKey(c).length() == 0;
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

    /**
     * 抓取最近多少条聊天行作为上下文。
     *
     * <p>默认值从 12 提到 20。实测（见 docs/jev-behavior-notes.md）上下文越短判定越飘：
     * 只给一行时，模型会以 0.92 的置信度把一句普通招呼判成 smalltalk；给够五行才收敛。
     * 群聊尤其吃亏——12 行里光昵称就占掉三四行，真正的内容没剩几句。
     */
    public static int contextLines(Context c) {
        return sp(c).getInt(K_LINES, 20);
    }

    public static void setContextLines(Context c, int v) {
        sp(c).edit().putInt(K_LINES, Math.max(2, Math.min(60, v))).apply();
    }

    // ---- 悬浮球与卡片的位置 ----
    // 存的是相对屏幕的比例而不是像素，换分辨率、转屏之后位置依然合理。

    private static final String K_BALL_X = "ball_x";
    private static final String K_BALL_Y = "ball_y";
    private static final String K_CARD_DX = "card_dx";
    private static final String K_CARD_DY = "card_dy";
    private static final String K_CARD_PINNED = "card_pinned";

    public static float ballX(Context c) {
        return sp(c).getFloat(K_BALL_X, 0.02f);
    }

    public static float ballY(Context c) {
        return sp(c).getFloat(K_BALL_Y, 0.55f);
    }

    public static void setBallPos(Context c, float x, float y) {
        sp(c).edit().putFloat(K_BALL_X, clamp(x)).putFloat(K_BALL_Y, clamp(y)).apply();
    }

    /**
     * 卡片相对悬浮球的偏移，按屏幕比例存。
     *
     * <p>存相对量而不是绝对坐标，是为了让卡片始终跟着球走：球一挪，卡片按同样的
     * 偏移跟过去。没有 pinned 标记时表示用户还没手动拖过卡片，此时由代码把它贴在
     * 球的侧面。
     */
    public static boolean cardPinned(Context c) {
        return sp(c).getBoolean(K_CARD_PINNED, false);
    }

    public static float cardOffsetX(Context c) {
        return sp(c).getFloat(K_CARD_DX, 0f);
    }

    public static float cardOffsetY(Context c) {
        return sp(c).getFloat(K_CARD_DY, 0f);
    }

    public static void setCardOffset(Context c, float dx, float dy) {
        sp(c).edit()
                .putFloat(K_CARD_DX, dx)
                .putFloat(K_CARD_DY, dy)
                .putBoolean(K_CARD_PINNED, true)
                .apply();
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(-1f, Math.min(1f, v));
    }
}
