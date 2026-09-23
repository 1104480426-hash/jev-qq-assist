package ai.jev.assist;

/** Pure state rules for the home runtime card; kept independent of Android views. */
public final class HomeUiState {
    private HomeUiState() {
    }

    public static String status(boolean accessibility, boolean overlay, boolean running) {
        if (running) {
            return "运行中";
        }
        return accessibility && overlay ? "可使用" : "需要设置";
    }

    public static String status(boolean accessibility, boolean overlay,
            boolean connected, boolean running) {
        if (running) {
            return "运行中";
        }
        return accessibility && overlay && connected ? "可使用" : "需要设置";
    }

    public static String action(boolean accessibility, boolean overlay, boolean running) {
        if (running) {
            return "停止悬浮球";
        }
        return accessibility && overlay ? "启动悬浮球" : "完成设置";
    }

    public static String action(boolean accessibility, boolean overlay,
            boolean connected, boolean running) {
        if (running) {
            return "停止悬浮球";
        }
        return accessibility && overlay && connected ? "启动悬浮球" : "完成设置";
    }

    public static boolean resultMatchesCapture(String resultTranscript, String captureTranscript) {
        return resultTranscript != null && resultTranscript.length() > 0
                && resultTranscript.equals(captureTranscript);
    }

    /** Returns 0 for accessibility, 1 for overlay, or -1 when both are ready. */
    public static int firstMissing(boolean accessibility, boolean overlay) {
        if (!accessibility) {
            return 0;
        }
        if (!overlay) {
            return 1;
        }
        return -1;
    }
}
