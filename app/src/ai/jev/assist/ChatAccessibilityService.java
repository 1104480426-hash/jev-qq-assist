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

    /**
     * 最近一次抓到的文本行的纵向区间，每项是 [top, bottom]。
     *
     * <p>悬浮卡片用它来避开聊天内容：把屏幕切成若干段，标出哪些段有字，卡片落到
     * 最靠上的那段空白里。这样球停在哪都不至于压住消息。
     */
    private static volatile List<int[]> cachedBands = new ArrayList<>();

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

    /** 取最近一次各文本行的纵向区间，供悬浮卡片避让。 */
    public static List<int[]> recentTextBands() {
        return cachedBands;
    }

    /** 常见聊天 App 的包名，用于把"当前在读谁"讲成人话。 */
    private static final String[][] KNOWN_APPS = {
            {"com.tencent.mobileqq", "QQ"},
            {"com.tencent.tim", "TIM"},
            {"com.tencent.mm", "微信"},
            {"com.tencent.wework", "企业微信"},
            {"com.ss.android.lark", "飞书"},
            {"com.alibaba.android.rimet", "钉钉"},
            {"org.telegram.messenger", "Telegram"},
            {"org.telegram.messenger.web", "Telegram"},
            {"com.whatsapp", "WhatsApp"},
            {"com.facebook.orca", "Messenger"},
            {"com.instagram.android", "Instagram"},
            {"com.discord", "Discord"},
            {"jp.naver.line.android", "LINE"},
    };

    /**
     * 最近一次抓到内容的 App 名。
     *
     * <p>没有任何包名白名单——读到谁就是谁。这个只用来在界面上告诉用户
     * "刚才那一下读的是哪个窗口"，好让他确认方向对不对。
     */
    public static String captureSourceName() {
        String pkg = lastCapturePkg;
        if (pkg == null || pkg.length() == 0) {
            return "";
        }
        for (String[] pair : KNOWN_APPS) {
            if (pair[0].equals(pkg)) {
                return pair[1];
            }
        }
        return pkg;
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

            // 顺手记下每行占的纵向范围，卡片靠它避让
            List<int[]> bands = new ArrayList<>(lines.size());
            for (Line line : lines) {
                bands.add(new int[]{line.top, line.bottom});
            }
            cachedBands = bands;
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
        final int bottom;
        final int centerX;

        Line(String text, int top, int bottom, int centerX) {
            this.text = text;
            this.top = top;
            this.bottom = bottom;
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
                    out.add(new Line(value, r.top, r.bottom, r.centerX()));
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

        // 分界点按当前屏幕的气泡分布现算，而不是写死屏宽百分比。
        // 各家的气泡宽度和左右留白都不一样，固定阈值换个 App 就会把
        // "我"和"对方"认反。算不出来时（样本太少、两簇挨得太近）退回固定值。
        int split = dynamicSplit(lines, width);
        boolean dynamic = split > 0;
        if (!dynamic) {
            split = width / 2;
        }
        int margin = Math.max(width / 20, (int) (12 * dm.density));

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
            if (line.centerX < split - margin) {
                speaker = "对方：";
            } else if (line.centerX > split + margin) {
                speaker = "我：";
            } else {
                // 压在分界上的别猜，宁可不说
                speaker = "";
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(speaker).append(line.text);
        }
        return sb.toString();
    }

    /**
     * 用一维二聚类找出左右两组气泡的分界 x。
     *
     * <p>聊天界面的气泡天然分两簇（对方靠左、我方靠右），两簇中心的中点就是分界。
     * 样本太少、或者两簇离得太近时返回 -1，让调用方退回固定阈值——总比硬猜要好。
     */
    private int dynamicSplit(List<Line> lines, int width) {
        if (lines.size() < 4) {
            return -1;
        }
        double lo = Double.MAX_VALUE;
        double hi = -1;
        for (Line l : lines) {
            lo = Math.min(lo, l.centerX);
            hi = Math.max(hi, l.centerX);
        }
        // 两簇几乎重合，说明这个界面本来就不分左右（比如全宽的列表），别硬分
        if (hi - lo < width * 0.15) {
            return -1;
        }

        for (int iter = 0; iter < 12; iter++) {
            double s1 = 0;
            double s2 = 0;
            int n1 = 0;
            int n2 = 0;
            for (Line l : lines) {
                if (Math.abs(l.centerX - lo) <= Math.abs(l.centerX - hi)) {
                    s1 += l.centerX;
                    n1++;
                } else {
                    s2 += l.centerX;
                    n2++;
                }
            }
            if (n1 == 0 || n2 == 0) {
                return -1;
            }
            lo = s1 / n1;
            hi = s2 / n2;
        }
        return (int) ((lo + hi) / 2);
    }

    private static void recycleSafely(AccessibilityNodeInfo node) {
        try {
            node.recycle();
        } catch (Exception ignored) {
            // 部分 ROM 会重复回收，忽略
        }
    }
}
