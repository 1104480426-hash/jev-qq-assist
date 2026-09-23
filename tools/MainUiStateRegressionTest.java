package ai.jev.assist;

public final class MainUiStateRegressionTest {
    public static void main(String[] args) {
        check("需要设置", HomeUiState.status(false, true, false));
        check("需要设置", HomeUiState.status(true, false, false));
        check("可使用", HomeUiState.status(true, true, false));
        check("运行中", HomeUiState.status(true, true, true));
        check("需要设置", HomeUiState.status(true, true, false, false));
        check("可使用", HomeUiState.status(true, true, true, false));
        check("完成设置", HomeUiState.action(false, true, false));
        check("启动悬浮球", HomeUiState.action(true, true, false));
        check("停止悬浮球", HomeUiState.action(true, true, true));
        check("停止悬浮球", HomeUiState.action(false, false, false, true));
        if (HomeUiState.firstMissing(false, false) != 0) throw new AssertionError("a11y first");
        if (HomeUiState.firstMissing(true, false) != 1) throw new AssertionError("overlay first");
        if (HomeUiState.firstMissing(true, true) != -1) throw new AssertionError("none missing");
        if (!HomeUiState.resultMatchesCapture("same", "same")) throw new AssertionError("matching result");
        if (HomeUiState.resultMatchesCapture("old", "new")) throw new AssertionError("stale result");
        System.out.println("PASS: home UI state combinations");
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
