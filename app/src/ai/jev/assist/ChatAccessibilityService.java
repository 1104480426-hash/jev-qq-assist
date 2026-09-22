package ai.jev.assist;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取当前聊天窗口的文字，缓存成一份可从悬浮球取用的对话转录。
 *
 * <p>只读：不回发任何事件、不执行任何 action、不自动发送消息。
 * 说话人靠气泡的屏幕横向位置推断，不确定就不加前缀。
 */
public class ChatAccessibilityService extends AccessibilityService {

    /** 最近一次抓到内容的时间与来源包名，供悬浮球判断上下文是否新鲜。 */
    public static volatile long lastCaptureAt = 0L;
    public static volatile String lastCapturePkg = "";

    private static volatile String cachedTranscript = "";

    private static final long THROTTLE_MS = 250L;
    private static final int MAX_NODES = 4000;

    /** 界面上常见的非消息文本，命中即丢。 */
    private static final Set<String> NOISE = new LinkedHashSet<>();

    static {
        Collections.addAll(NOISE,
                "发送", "表情", "更多", "返回", "聊天", "取消", "确定", "搜索",
                "输入", "语音", "相册", "拍摄", "红包", "转账", "位置", "文件",
                "收藏", "名片", "视频通话", "语音通话", "免提", "静音", "挂断",
                "消息", "联系人", "动态", "空间", "我的", "设置");
    }

    private long lastEventAt = 0L;
    private String lastPkg = "";

    /** 取最近一次的对话转录，无内容时返回空串。 */
    public static String cachedTranscript() {
        return cachedTranscript;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (pkg.length() == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastEventAt < THROTTLE_MS) {
            return;
        }
        lastEventAt = now;
        lastPkg = pkg;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            List<Line> lines = new ArrayList<>();
            collect(root, lines, 0);
            if (lines.isEmpty()) {
                return;
            }
            String transcript = buildTranscript(lines);
            if (transcript.length() == 0) {
                return;
            }
            cachedTranscript = transcript;
            lastCapturePkg = pkg;
            lastCaptureAt = now;
        } finally {
            recycleSafely(root);
        }
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    /** 一段可加说话人前缀的文本行。 */
    private static final class Line {
        final String text;
        final int top;
        final int centerX;

        Line(String text, int top, int centerX) {
            this.text = text;
            this.top = top;
            this.centerX = centerX;
        }
    }

    private void collect(AccessibilityNodeInfo node, List<Line> out, int depth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES) {
            return;
        }
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        if (text != null && text.length() > 0) {
            String value = text.toString().trim();
            if (isMessageLike(node, value)) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    out.add(new Line(value, r.top, r.centerX()));
                }
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collect(child, out, depth + 1);
                } finally {
                    recycleSafely(child);
                }
            }
        }
    }

    private boolean isMessageLike(AccessibilityNodeInfo node, String value) {
        CharSequence cls = node.getClassName();
        String className = cls == null ? "" : cls.toString();
        if (className.contains("EditText") || className.contains("Button")) {
            return false;
        }
        if (node.isEditable()) {
            return false;
        }
        if (value.length() > 500) {
            return false;
        }
        if (NOISE.contains(value)) {
            return false;
        }
        // 纯时间戳或纯数字，通常是分隔符
        if (value.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$") || value.matches("^\\d+$")) {
            return false;
        }
        // 至少要有一个中日韩字符或字母，纯符号不要
        return value.matches(".*[\\p{IsHan}A-Za-z].*");
    }

    private String buildTranscript(List<Line> lines) {
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                if (a.top != b.top) {
                    return a.top < b.top ? -1 : 1;
                }
                return a.centerX - b.centerX;
            }
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int leftBand = (int) (width * 0.35);
        int rightBand = (int) (width * 0.65);

        int linesWanted = Prefs.contextLines(this);
        int from = Math.max(0, lines.size() - linesWanted);

        StringBuilder sb = new StringBuilder();
        String previous = null;
        for (int i = from; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.text.equals(previous)) {
                continue;
            }
            previous = line.text;

            String speaker;
            if (line.centerX <= leftBand) {
                speaker = "对方：";
            } else if (line.centerX >= rightBand) {
                speaker = "我：";
            } else {
                speaker = "";
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(speaker).append(line.text);
        }
        return sb.toString();
    }

    private static void recycleSafely(AccessibilityNodeInfo node) {
        try {
            node.recycle();
        } catch (Exception ignored) {
            // 部分 ROM 会重复回收，忽略
        }
    }
}
