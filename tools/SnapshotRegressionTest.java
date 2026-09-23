package ai.jev.assist;

public final class SnapshotRegressionTest {
    public static void main(String[] args) {
        CaptureSnapshot request = snapshot("QQ", 4, "Alice", "对方：好", 1, "读到 1 条");
        CaptureSnapshot updated = snapshot("QQ", 4, "Alice", "对方：好\n我：收到", 2, "读到 2 条");
        check(!request.sameContext(updated), "new messages invalidate old verdict");
        check(!request.sameContext(snapshot("QQ", 4, "Bob", "对方：好", 1, "读到 1 条")),
                "same app and same message in another conversation invalidates verdict");
        check(!request.sameContext(snapshot("QQ", 5, "Alice", "对方：好", 1, "读到 1 条")),
                "different window invalidates verdict");
        check(!request.sameContext(snapshot("WeChat", 4, "Alice", "对方：好", 1, "读到 1 条")),
                "different app invalidates verdict");
        check(!request.sameContext(null), "lost window invalidates verdict");
        check(request.sameContext(snapshot("QQ", 4, "Alice", "对方：好", 1, "new statistics")),
                "identical context survives repeated accessibility events");
        check(request.lineCount == 1 && request.reading.equals("读到 1 条"),
                "pending request retains its original input statistics");
        System.out.println("PASS: conversation, content, window, app, missing window, stable events, snapshot statistics");
    }

    private static CaptureSnapshot snapshot(String pkg, int window, String title, String text,
            int count, String reading) {
        return new CaptureSnapshot(text, pkg, window, title, count, reading, "stats", 100L);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
