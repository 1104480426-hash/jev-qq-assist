package ai.jev.assist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Exercises the real Android service callback and packaged ONNX model; no API requests. */
public final class DeviceRegressionTest {
    /** ADB shell entry point, used when the ROM disallows a separate test APK. */
    public static void main(String[] args) {
        try {
            runTests(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runTests(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        checkInvalidation();
        checkHeader();
        checkSpeakerPairing();
        final android.content.res.AssetManager assets = android.content.res.AssetManager.class
                .getDeclaredConstructor().newInstance();
        Method addPath = assets.getClass().getMethod("addAssetPath", String.class);
        check(((Integer) addPath.invoke(assets, args[0])) != 0, "APK assets available");
        android.content.Context context = new android.content.ContextWrapper(null) {
            @Override public android.content.res.AssetManager getAssets() { return assets; }
        };
        long start = System.currentTimeMillis();
        LocalJudge judge = LocalJudge.get(context);
        long loaded = System.currentTimeMillis();
        JSONObject answers = judge.judge("对方：明天下午一起去图书馆吗？\n我：可以，几点？\n对方：三点门口见。\n我：好的，明天见。");
        check(answers.length() == 5, "all five ONNX answers");
        for (LocalDecisionSpec.Question q : LocalDecisionSpec.QUESTIONS) {
            double confidence = answers.getJSONObject(q.key).getDouble("confidence");
            check(confidence >= 0 && confidence <= 1, "finite confidence");
        }
        System.out.println("PASS: Android callbacks, QQ headers, speaker pairing, ONNX five answers; load "
                + (loaded - start) + " ms; inference " + (System.currentTimeMillis() - loaded) + " ms");
        System.exit(0);
    }

    private static void checkHeader() throws Exception {
        ChatAccessibilityService service = new ChatAccessibilityService();
        Method collect = ChatAccessibilityService.class.getDeclaredMethod("collect",
                android.view.accessibility.AccessibilityNodeInfo.class, java.util.List.class,
                int.class, java.util.List.class, int.class, int.class);
        collect.setAccessible(true);
        for (String title : new String[]{"学习群(123)", "123456", "工作群"}) {
            android.view.accessibility.AccessibilityNodeInfo node = android.view.accessibility.AccessibilityNodeInfo.obtain();
            node.setText(title);
            node.setClassName("android.widget.Button");
            node.setBoundsInScreen(new android.graphics.Rect(175, 164, 800, 223));
            java.util.List<Object> messages = new java.util.ArrayList<>(), headers = new java.util.ArrayList<>();
            collect.invoke(service, node, messages, 0, headers, 2400, 1080);
            check(messages.isEmpty() && headers.size() == 1, "filtered title still identifies conversation: " + title);
            Field text = headers.get(0).getClass().getDeclaredField("text");
            text.setAccessible(true);
            check(title.equals(text.get(headers.get(0))), "header text retained");
            node.recycle();
        }
        for (String unread : new String[]{"返回消息未读8", "返回消息未读9"}) {
            android.view.accessibility.AccessibilityNodeInfo node = android.view.accessibility.AccessibilityNodeInfo.obtain();
            node.setContentDescription(unread);
            node.setClassName("android.widget.TextView");
            node.setBoundsInScreen(new android.graphics.Rect(27, 161, 92, 226));
            java.util.List<Object> headers = new java.util.ArrayList<>();
            collect.invoke(service, node, new java.util.ArrayList<Object>(), 0, headers, 2400, 1080);
            check(headers.isEmpty(), "unrelated unread count must not change conversation identity");
            node.recycle();
        }
    }

    private static void checkSpeakerPairing() throws Exception {
        ChatAccessibilityService service = new ChatAccessibilityService();
        Class<?> line = Class.forName("ai.jev.assist.ChatAccessibilityService$Line");
        java.lang.reflect.Constructor<?> create = line.getDeclaredConstructor(
                String.class, int.class, int.class, int.class, int.class);
        create.setAccessible(true);
        Object message = create.newInstance("对方的普通回复", 1295, 1459, 140, 326);
        Object photo = create.newInstance("图片", 1459, 2107, 448, 682);
        Method group = ChatAccessibilityService.class.getDeclaredMethod("groupMessages", java.util.List.class, int.class);
        group.setAccessible(true);
        java.util.List<Object> input = new java.util.ArrayList<>();
        input.add(message);
        input.add(photo);
        java.util.List<?> result = (java.util.List<?>) group.invoke(service, input, 1080);
        check(result.size() == 2, "opposite-side reply must not become the photo's speaker name");
        input.clear();
        input.add(create.newInstance("成员甲", 300, 338, 155, 225));
        input.add(create.newInstance("这是群聊中的一条消息", 345, 465, 140, 350));
        result = (java.util.List<?>) group.invoke(service, input, 1080);
        check(result.size() == 1, "aligned group nickname remains paired with its message");
    }

    private static void checkInvalidation() throws Exception {
        CaptureSnapshot alice = snapshot("QQ", "Alice", "对方：好");
        OverlayService service = new OverlayService();
        Field active = field("activeCapture"), dismissed = field("dismissed"), seq = field("requestSeq");
        Method changed = OverlayService.class.getDeclaredMethod("onActiveWindowChanged", CaptureSnapshot.class);
        changed.setAccessible(true);
        active.set(service, alice);
        seq.setInt(service, 12);
        changed.invoke(service, snapshot("QQ", "Alice", "对方：好"));
        check(!dismissed.getBoolean(service) && seq.getInt(service) == 12, "stable screen keeps result");
        changed.invoke(service, snapshot("com.android.systemui", "Panel", ""));
        check(!dismissed.getBoolean(service), "temporary panel keeps result");
        changed.invoke(service, snapshot("QQ", "Bob", "对方：好"));
        check(dismissed.getBoolean(service) && seq.getInt(service) != 12 && active.get(service) == null,
                "same-app switch cancels pending result");
        active.set(service, alice);
        dismissed.setBoolean(service, false);
        changed.invoke(service, snapshot("QQ", "Alice", "对方：好\n我：收到"));
        check(dismissed.getBoolean(service), "new message cancels result");
        active.set(service, alice);
        dismissed.setBoolean(service, false);
        int before = seq.getInt(service);
        service.onDestroy();
        check(dismissed.getBoolean(service) && seq.getInt(service) != before,
                "destroyed service rejects late callbacks");
    }

    private static CaptureSnapshot snapshot(String pkg, String title, String text) {
        return new CaptureSnapshot(text, pkg, 4, title, 1, "读到 1 条", "", 100L);
    }
    private static Field field(String name) throws Exception {
        Field f = OverlayService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
