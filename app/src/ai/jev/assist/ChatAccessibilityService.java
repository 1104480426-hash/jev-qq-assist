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
        final int left;
        final int centerX;

        Line(String text, int top, int bottom, int left, int centerX) {
            this.text = text;
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.centerX = centerX;
        }

        int height() {
            return bottom - top;
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
                    out.add(new Line(value, r.top, r.bottom, r.left, r.centerX()));
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

    /**
     * 一条消息：谁说的 + 说了什么。
     */
    private static final class Message {
        String speaker;
        final String text;
        final int top;

        Message(String speaker, String text, int top) {
            this.speaker = speaker;
            this.text = text;
            this.top = top;
        }
    }

    /**
     * 把扁平的文字行还原成带说话人的消息列表。
     *
     * <p>不能简单地两两相邻配对。实测的群聊结构里，头像那格也带文字（用户名的首字），
     * 它的 top（475）刚好落在昵称（456）和消息（506）之间，会把这俩隔开，导致配对失败。
     *
     * <p>所以分两步：先剔除头像带出来的单字（它们贴在屏幕最左侧、又窄又短），
     * 再对每条消息往上就近找一个昵称。
     */
    private List<Message> groupMessages(List<Line> lines, int width) {
        int avatarBand = (int) (width * 0.13);

        List<Line> kept = new ArrayList<>(lines.size());
        for (Line l : lines) {
            boolean avatarGlyph = l.left < avatarBand && l.text.length() <= 2;
            if (!avatarGlyph) {
                kept.add(l);
            }
        }

        Set<Line> usedAsLabel = Collections.newSetFromMap(new java.util.IdentityHashMap<Line, Boolean>());
        List<Message> out = new ArrayList<>(kept.size());

        for (int i = 0; i < kept.size(); i++) {
            Line cur = kept.get(i);
            String speaker = "";
            // 只看上面三条，再远就不可能是这条消息的昵称了
            for (int j = i - 1; j >= 0 && j >= i - 3; j--) {
                Line above = kept.get(j);
                if (looksLikeSpeakerLabel(above, cur)) {
                    speaker = above.text;
                    usedAsLabel.add(above);
                    break;
                }
                if (above.bottom < cur.top - 30) {
                    break;
                }
            }
            if (speaker.length() > 0) {
                out.add(new Message(speaker, cur.text, cur.top));
            } else if (!usedAsLabel.contains(cur)) {
                out.add(new Message("", cur.text, cur.top));
            }
        }
        return out;
    }

    /**
     * 判断 a 是不是 b 上面那个昵称。
     *
     * <p>三个条件同时成立才算：紧贴其上、更矮、够短。只用"紧贴"会误伤——两条挨得近的
     * 短消息也会满足，所以额外要求它比下面那行矮一截、且不长于 12 个字。
     */
    private boolean looksLikeSpeakerLabel(Line a, Line b) {
        if (a.height() <= 0 || b.height() <= 0) {
            return false;
        }
        int gap = b.top - a.bottom;
        if (gap < -6 || gap > 20) {
            return false;          // 不在正上方
        }
        if (a.height() > b.height() * 0.72) {
            return false;          // 昵称一定比正文矮
        }
        if (a.text.length() > 12) {
            return false;          // 昵称不会很长
        }
        // 昵称一般不含句末标点，正文常有
        return !a.text.matches(".*[。！？!?]$");
    }

    private String buildTranscript(List<Line> lines) {
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                if (a.top != b.top) {
                    return a.top < b.top ? -1 : 1;
                }
                return a.left - b.left;
            }
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = dm.widthPixels;

        List<Message> messages = groupMessages(lines, width);

        // 认出两个以上不同昵称才算群聊；否则按一对一的左右分栏处理
        Set<String> speakers = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m.speaker.length() > 0) {
                speakers.add(m.speaker);
            }
        }
        boolean groupChat = speakers.size() >= 2;

        int split = dynamicSplit(lines, width);
        boolean dynamic = split > 0;
        if (!dynamic) {
            split = width / 2;
        }
        int margin = Math.max(width / 20, (int) (12 * dm.density));

        // 位置 -> 左右归属，昵称缺失时用它兜底
        java.util.Map<Integer, Integer> xByTop = new java.util.HashMap<>();
        for (Line l : lines) {
            xByTop.put(l.top, l.centerX);
        }

        int wanted = Prefs.contextLines(this);
        int from = Math.max(0, messages.size() - wanted);

        StringBuilder sb = new StringBuilder();
        String previous = null;
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.text.equals(previous)) {
                continue;
            }
            previous = m.text;

            String who;
            if (groupChat && m.speaker.length() > 0) {
                // 群聊：直接用昵称，让判定模型分得清谁是谁
                who = m.speaker + "：";
            } else {
                Integer cx = xByTop.get(m.top);
                int x = cx == null ? split : cx;
                if (x < split - margin) {
                    who = "对方：";
                } else if (x > split + margin) {
                    who = "我：";
                } else {
                    who = "";
                }
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(who).append(m.text);
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
