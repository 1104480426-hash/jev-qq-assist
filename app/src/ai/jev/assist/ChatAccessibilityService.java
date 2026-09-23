package ai.jev.assist;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
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

    /** 已连接的服务实例，用于按需立刻抓取。 */
    private static volatile ChatAccessibilityService instance;

    /**
     * 活动窗口换人时的通知口。OverlayService 挂上去，用来判断球上那个判定染色还作不作数。
     *
     * <p>用回调而不是让 OverlayService 自己轮询：窗口切换是个事件，读屏服务本来就收得到，
     * 轮询既要定周期又会漏掉一次性的切换。
     */
    public interface WindowWatcher {
        void onActiveWindow(CaptureSnapshot snapshot);
    }

    private static volatile WindowWatcher windowWatcher;

    public static void setWindowWatcher(WindowWatcher watcher) {
        windowWatcher = watcher;
    }

    private static void notifyActiveWindow(CaptureSnapshot snapshot) {
        WindowWatcher w = windowWatcher;
        if (w == null) {
            return;
        }
        try {
            w.onActiveWindow(snapshot);
        } catch (Exception ignored) {
            // 回调出错不该影响读屏本身
        }
    }

    /**
     * 最近一次抓取的统计：原始多少行、过滤掉多少、识别出几条消息、几行拿到了署名。
     *
     * <p>判定不对时先看这个：是根本没抓到（原始行数太少），还是抓到了但署名没认出来。
     * 两者要修的地方完全不同。
     */
    private static volatile String lastStats = "";

    public static String lastStats() {
        return lastStats;
    }

    /**
     * 最近一次转录实际送进判定的行数。
     *
     * <p>判定结果本身看不出版本差异，但这个数字决定了那份结果值不值得信：实测只喂一句时
     * Jev 会把有争执的对话判成「平静、闲聊」，置信度还有 0.92。渲染层拿它决定要不要照常
     * 展示结论，见 {@link DecisionSpec#thinContextWarning(int)}。
     */
    private static volatile int lastTranscriptLines = 0;

    public static int lastTranscriptLines() {
        return lastTranscriptLines;
    }

    /**
     * 最近一次转录的构成摘要，形如「读到 11 条 · 对方 7 · 我 4」。
     *
     * <p>这是用户唯一能当场发现「读错了」的线索：结论本身看不出输入对不对，措辞总是通顺的；
     * 而设置页里那份完整转录要切走才能看，只有起了疑心的人才会去翻。只报结构不报内容，
     * 拼法见 {@link #reading}。
     */
    private static volatile String lastReading = "";

    public static String lastReading() {
        return lastReading;
    }

    /**
     * 点悬浮球那一下抓到的内容。
     *
     * <p>被动事件永不写这三个字段。原因：用户点完球、看完判定，要切回设置页看"刚才到底读了什么"，
     * 而这一路上会经过桌面——桌面在屏幕上停留超过 {@link #THROTTLE_MS}，被动事件就把缓存
     * 改写成桌面了，设置页于是展示应用图标名而不是那段对话。展示"最近一次判定用的输入"
     * 必须有一份不受路过窗口影响的快照。
     */
    private static volatile String pinnedTranscript = "";
    private static volatile String pinnedPkg = "";

    /** 上一次点球判定的来源包名。用来判断那个窗口还在不在前台。 */
    public static String pinnedPkg() {
        return pinnedPkg;
    }
    private static volatile long pinnedAt = 0L;
    private static volatile String pinnedStats = "";

    public static String pinnedTranscript() {
        return pinnedTranscript;
    }

    public static String pinnedStats() {
        return pinnedStats;
    }

    public static long pinnedAt() {
        return pinnedAt;
    }

    private static final long THROTTLE_MS = 250L;
    private static final int MAX_NODES = 4000;
    private static final String TAG = "JevWingman";

    /**
     * 按需抓取期间记录每个节点的去向，抓完就写进 logcat。
     *
     * <p>被动缓存和事后 dump 看到的是两个不同瞬间的屏幕（群聊一直在滚），
     * 对不上账。要让"读错了"这件事可复现，只能在抓取的那一帧里留痕：
     * 哪些节点被收下、哪些被丢、丢在哪个条件上。
     */
    private StringBuilder traceBuf = null;

    private void trace(String s) {
        if (traceBuf != null) {
            traceBuf.append(s).append('\n');
        }
    }

    /** 节点类名去掉包前缀，日志里看着清爽。 */
    private static String shortCls(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) {
            return "?";
        }
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    /** 只留开头十个字：够看清是什么控件，不至于把整段聊天抄进日志。 */
    private static String head(String v) {
        String flat = v.replace('\n', ' ');
        return (flat.length() > 10 ? flat.substring(0, 10) + "…" : flat) + " (len=" + v.length() + ")";
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    /** 读屏服务是否已连上。按需抓取依赖它，连不上就只能退回缓存。 */
    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * 立刻抓一次当前活动窗口并返回转录。
     *
     * <p>缓存是靠事件被动更新的，而事件可能来自别的窗口——实测出现过缓存里还是桌面
     * （负一屏的步数、应用图标名）的情况，用户以为在分析聊天，其实在分析桌面。
     * 所以点悬浮球的时候必须现抓，不能吃缓存。
     *
     * <p>返回 null 表示读屏服务没连上。
     */
    public static String captureNow() {
        CaptureSnapshot snapshot = captureSnapshotNow();
        return snapshot == null ? null : snapshot.transcript;
    }

    /** Called on the main thread, like accessibility events; snapshot fields travel together. */
    public static CaptureSnapshot captureSnapshotNow() {
        ChatAccessibilityService s = instance;
        if (s == null) {
            return null;
        }
        CaptureSnapshot fresh = s.captureActiveWindow();
        if (fresh != null && fresh.transcript.length() > 0) {
            // 写回缓存。这样用户切回设置页时，看到的就是刚才那次判定实际用的文本，
            // 而不是上一次被动事件留下的、可能来自别的窗口的旧内容。
            //
            // 包名必须用抓取时那一次 root 的。早先在这里重新取了一次
            // getRootInActiveWindow()，结果卡片一弹出来活动窗口就变成通知栏，
            // 界面上如实写着「最近读自 com.android.systemui」。
            cachedTranscript = fresh.transcript;
            lastCapturePkg = fresh.packageName;
            lastCaptureAt = fresh.capturedAt;

            // 同一份内容钉住。设置页展示的是这一次，不是"最近任何窗口"。
            pinnedTranscript = fresh.transcript;
            pinnedPkg = fresh.packageName;
            pinnedAt = lastCaptureAt;
            pinnedStats = fresh.stats;
        }
        return fresh;
    }

    private CaptureSnapshot captureActiveWindow() {
        traceBuf = new StringBuilder(4096);
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                trace("! 没有活动窗口");
                return null;
            }
            try {
                return readSnapshot(root);
            } finally {
                recycleSafely(root);
            }
        } finally {
            Log.i(TAG, "—— 抓取 ——\n" + traceBuf);
            traceBuf = null;
        }
    }

    private CaptureSnapshot readSnapshot(AccessibilityNodeInfo root) {
        List<Line> lines = new ArrayList<>();
        List<Line> headers = new ArrayList<>();
        int height = realScreenHeight();
        collect(root, lines, 0, headers, height, getResources().getDisplayMetrics().widthPixels);
        String owner = root.getPackageName() == null ? "" : root.getPackageName().toString();
        lastTranscriptLines = 0;
        lastReading = "";
        lastStats = "";
        String transcript = lines.isEmpty() ? ""
                : buildTranscript(lines, getResources().getDisplayMetrics().widthPixels, height);
        // Use the same header band as message filtering. No coordinates or clocks in the key.
        StringBuilder header = new StringBuilder();
        for (Line line : headers) {
            header.append(line.text).append('\n');
        }
        List<int[]> bands = new ArrayList<>(lines.size());
        for (Line line : lines) bands.add(new int[]{line.top, line.bottom});
        cachedBands = bands;
        return new CaptureSnapshot(transcript, owner, root.getWindowId(), header.toString(),
                lastTranscriptLines, lastReading, lastStats, System.currentTimeMillis());
    }

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
    private final Handler events = new Handler(Looper.getMainLooper());
    private final Runnable trailingCapture = new Runnable() {
        @Override
        public void run() {
            lastEventAt = System.currentTimeMillis();
            observeActiveWindow();
        }
    };

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
            // 自己的演示页。不写这一条的话，演示模式下「来源」会显示成 ai.jev.assist，
            // 对着一个包名没人知道那是什么。
            {"ai.jev.assist", "演示模式"},
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
        return appNameOf(lastCapturePkg);
    }

    /** 点球那一次读的是哪个 App。 */
    public static String pinnedSourceName() {
        return appNameOf(pinnedPkg);
    }

    private static String appNameOf(String pkg) {
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
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && pkg.equals(lastPkg) && now - lastEventAt < THROTTLE_MS) {
            // Do not lose the final new-message event inside the throttle interval.
            events.removeCallbacks(trailingCapture);
            events.postDelayed(trailingCapture, THROTTLE_MS - (now - lastEventAt));
            return;
        }
        events.removeCallbacks(trailingCapture);
        lastEventAt = now;
        lastPkg = pkg;
        observeActiveWindow();
    }

    private void observeActiveWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            notifyActiveWindow(null);
            return;
        }
        try {
            // 来源包名要用 root 的，不能用事件的。事件可能是状态栏或输入法发出来的，
            // 而 getRootInActiveWindow() 拿到的是真正的活动窗口——实测就这样把一份
            // 读对了的 QQ 群聊标成了「最近读自 com.android.systemui」。
            CharSequence rp = root.getPackageName();
            String owner = rp == null ? "" : rp.toString();
            if (owner.startsWith("com.android.systemui")) {
                return; // Temporary system panels do not replace the conversation.
            }
            CaptureSnapshot snapshot = readSnapshot(root);
            notifyActiveWindow(snapshot);
            // Observe our demo/settings transitions too, but preserve the external input cache.
            if (owner.equals(getPackageName()) || snapshot.transcript.length() == 0) {
                return;
            }
            cachedTranscript = snapshot.transcript;
            lastCapturePkg = owner;
            lastCaptureAt = snapshot.capturedAt;
        } finally {
            recycleSafely(root);
        }
    }

    @Override
    public void onInterrupt() {
        notifyActiveWindow(null);
    }

    @Override
    public void onDestroy() {
        events.removeCallbacksAndMessages(null);
        instance = null;
        cachedTranscript = "";
        notifyActiveWindow(null);
        super.onDestroy();
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

    private void collect(AccessibilityNodeInfo node, List<Line> out, int depth,
            List<Line> headers, int screenHeight, int screenWidth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES) {
            return;
        }
        CharSequence text = node.getText();
        boolean fromDesc = false;
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
            fromDesc = true;
        }
        if (text != null && text.length() > 0) {
            String value = text.toString().trim();
            // Identity is collected BEFORE message filtering: QQ group names, numeric names,
            // and titles rendered as buttons are not messages, but still identify the chat.
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.bottom <= screenHeight * 0.11
                    // Edge controls include QQ's changing unread count on the back button.
                    // They identify navigation state, not the current conversation.
                    && bounds.left >= screenWidth * 0.12 && bounds.right <= screenWidth * 0.88
                    && !node.isEditable() && !node.isPassword()
                    && !value.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$")) {
                headers.add(new Line(value, bounds.top, bounds.bottom, bounds.left, bounds.centerX()));
            }
            if (isMessageLike(node, value)) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    out.add(new Line(value, r.top, r.bottom, r.left, r.centerX()));
                    trace("K " + shortCls(node) + (fromDesc ? "(desc)" : "")
                            + " [" + r.top + "," + r.bottom + "," + r.left + "] " + head(value));
                } else {
                    trace("R 无坐标 | " + head(value));
                }
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collect(child, out, depth + 1, headers, screenHeight, screenWidth);
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
            return reject("EditText/Button", value);
        }
        if (node.isEditable()) {
            return reject("可编辑", value);
        }
        if (value.length() > 500) {
            return reject("过长", value);
        }
        if (NOISE.contains(value)) {
            return reject("通用控件词", value);
        }
        // 纯时间戳或纯数字，通常是分隔符
        if (value.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$") || value.matches("^\\d+$")) {
            return reject("纯时间/数字", value);
        }
        // QQ 群聊的分隔条长这样：「112609 2026-09-22 16:28:25」，
        // 序号 + 日期 + 时间。混进转录会打断「昵称-消息」的相邻关系。
        if (value.matches("^\\d{3,8}\\s+\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}(:\\d{2})?$")) {
            return reject("QQ时间分隔条", value);
        }
        // 日期打头的行。实测 QQ 的分隔条不一定带序号，也会只写「2026-09-22 16:28」
        if (value.matches("^\\d{4}-\\d{1,2}-\\d{1,2}(\\s.*)?$")) {
            return reject("日期打头", value);
        }
        // 序号 + 日期的其它排列，比如「2026/9/22 16:28」或「16:28 2026-09-22」
        if (value.matches("^[\\d\\s:/-]{8,}$")) {
            return reject("数字分隔符堆", value);
        }
        // 群名 + 成员数这种标题：「某某群(1489)」
        if (value.matches("^.{1,30}\\(\\d{1,6}\\)$")) {
            return reject("群标题", value);
        }
        // 至少要有一个文字或数字。纯标点不要——「。。。」「···」「|」这类是装饰。
        // 注意这里必须放行数字：聊天里「9.19」「3.14」「666」都是正常发言，
        // 早先只放行汉字和字母，把它们整条吞掉了，连累上下文的配对。
        if (!value.matches(".*[\\p{IsHan}A-Za-z0-9].*")) {
            return reject("纯符号", value);
        }
        return true;
    }

    /** 拒绝一条记录，并把理由留在诊断日志里。 */
    private boolean reject(String reason, String value) {
        trace("R " + reason + " | " + head(value));
        return false;
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
        // 头像、标题栏、同一行的多段文字都已经在 sanitize 里理过了
        List<Line> kept = lines;

        // 两遍。第一遍只登记"哪一行是谁的昵称"，第二遍才输出。
        // 必须这样分：昵称行排在它标注的消息之前，一遍处理时轮到昵称自己，
        // 它还没被登记为标签，就会被当成一条普通消息输出（实测就是这样，
        // 转录里出现了「对方：张三」这种把昵称当消息的行）。
        Set<Line> labels = Collections.newSetFromMap(new java.util.IdentityHashMap<Line, Boolean>());
        java.util.Map<Line, String> speakerOf = new java.util.IdentityHashMap<Line, String>();

        for (int i = 0; i < kept.size(); i++) {
            Line cur = kept.get(i);
            // 只看上面三条，再远就不可能是这条消息的昵称
            for (int j = i - 1; j >= 0 && j >= i - 3; j--) {
                Line above = kept.get(j);
                // 已经有昵称在上面挂着的行是正文，不能再去当别人的昵称。实测
                // 「蚁巢验资和拉新都去」两头被认领：它上面是「管理员 坤山靠」的昵称，
                // 所以它是正文；可它又被当成了下面那条「图片」的昵称。结果它自己
                // 不输出了，署名还挂到了图片上。继续往上找，别 break——更上面还有
                // 可能是这条消息真正的昵称。
                if (speakerOf.containsKey(above)) {
                    continue;
                }
                if (looksLikeSpeakerLabel(above, cur, width)) {
                    labels.add(above);
                    speakerOf.put(cur, above.text);
                    break;
                }
                if (above.bottom < cur.top - 30) {
                    break;
                }
            }
        }

        int named = 0;
        for (java.util.Map.Entry<Line, String> e : speakerOf.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > 0) {
                named++;
            }
        }
        lastStats = "清理后 " + kept.size()
                + " 行 · 成条 " + (kept.size() - labels.size())
                + " · 认出署名 " + named;

        List<Message> out = new ArrayList<>(kept.size());
        for (Line cur : kept) {
            if (labels.contains(cur)) {
                continue;      // 它是昵称，不单独成一条
            }
            String sp = speakerOf.get(cur);
            out.add(new Message(sp == null ? "" : sp, cur.text, cur.top));
        }
        return out;
    }

    /**
     * 判断 a 是不是 b 上面那个昵称。
     *
     * <p>四个条件同时成立才算，而且都留了余量——各个 App 的字号、行高、气泡间距都不一样，
     * 阈值卡太死就只在一个 App 上成立。
     *
     * <p>其中"更矮"用的是相对比较（矮于对方的 85%）而不是绝对值，这样字号大的 App 和
     * 字号小的 App 都适用。"更窄"是额外加的一道：昵称通常也短于消息气泡。
     */
    private boolean looksLikeSpeakerLabel(Line a, Line b, int width) {
        if (a.height() <= 0 || b.height() <= 0) {
            return false;
        }
        // A nickname belongs to the bubble below on the same side. A short left-hand reply
        // followed by a tall right-hand photo must remain a message, not become its nickname.
        if (Math.abs(a.left - b.left) > width * 0.05) {
            return false;
        }
        int gap = b.top - a.bottom;
        if (gap < -8 || gap > 26) {
            return false;              // 不在正上方（含一点容差给不同的行距）
        }
        if (a.height() >= b.height() * 0.85) {
            return false;              // 昵称一定比正文矮，但别要求矮太多
        }
        // 昵称长度上限放到 24。群名片本身就长，再加上群头衔（「管理员」「群主」）
        // 会跟名字合并成同一行，12 字根本不够——实测「管理员 坤山靠（唯一…」是 13 字，
        // 就卡在这条上，整个群的署名只认出一个。真正拦住正文的是上面那条高度判据。
        if (a.text.length() > 24) {
            return false;
        }
        // 昵称一般不含句末标点，正文常有
        if (a.text.matches(".*[。！？!?]$")) {
            return false;
        }
        // 高度差足够明显时（矮于 70%），不必再比长度——群昵称可以很长，
        // 而它下面第一条可能只有两三个字（实测 QQ 群里「nbox准备起飞倒计时了」
        // 就是被这条规则误杀的）。只有当两者高度接近、光看高度分不出来时，
        // 才用长度做二次区分，避免把两条挨着的短消息认成一对。
        boolean clearlyShorter = a.height() < b.height() * 0.7;
        return clearlyShorter || a.text.length() <= Math.max(4, b.text.length() / 2);
    }

    /**
     * 把读到的扁平文字行理成真正的消息行。
     *
     * <p>无障碍树里一条消息会被拆成好几段，还夹着两类不请自来的客人。实测 QQ 群聊里
     * 三者齐全，缺哪一步转录就全是噪音：头像那格的 content-desc 是「张博文的资料卡」，
     * 七个字，按"短到只有一两个字"去认根本认不出来；「管理员」和「bello」是同一行的两个
     * TextView，不合并前者就自己成一条消息；标题栏（返回、群名、成员数、听筒模式、
     * 聊天设置）本来就在文字行里，混进来会顶掉真正的上下文。
     */
    private List<Line> sanitize(List<Line> lines, int width, int height) {
        List<Line> out = mergeSameRow(dropAvatars(lines, width));
        List<Line> body = dropHeader(out, height);
        // 万一窗口里的文字全在顶部（没停在聊天窗口就会这样），标题栏规则会清空一切。
        // 宁可留着标题栏，也别交出一份空白转录。
        return body.isEmpty() ? out : body;
    }

    /**
     * 剔掉头像。
     *
     * <p>头像贴在屏幕两端：别人的在左，自己的在右。实测它有两种形态——图片本身带出来的
     * 单字，以及整格的 content-desc（QQ 给的是「张博文的资料卡」「我的资料卡」）。后者
     * 长度不定，所以不能只靠长度认。真正的判据是位置：头像在两端，对话内容在中间，
     * 所以同一水平线上若有更靠中间的文字，贴在边上那块就是头像。右侧这一支是必需的，
     * 漏掉它自己的头像就会和对面昵称合并，署名直接变成「管理员 bello 我的资料卡」。
     */
    private List<Line> dropAvatars(List<Line> lines, int width) {
        int edgeBand = (int) (width * 0.05);
        int leftBand = (int) (width * 0.13);
        int rightBand = (int) (width * 0.87);
        List<Line> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            // 死死贴在最左边的，是容器或头像的 desc，不是对话文字——真实文本总有内边距。
            // 实测 QQ 会话列表底部就漏进来一条 left=0 的头像 desc，它右边那行的名字没被
            // 读到，所以"与中间文字并存"这条判据对它不成立，只能靠位置硬挡。
            if (l.left < edgeBand) {
                continue;
            }
            boolean atLeft = l.left < leftBand;
            boolean atRight = l.left >= rightBand;
            if (!atLeft && !atRight) {
                out.add(l);
                continue;
            }
            if (l.text.length() <= 2) {
                continue;              // 头像角标
            }
            boolean paired = false;
            for (int j = 0; j < lines.size(); j++) {
                if (j == i) {
                    continue;
                }
                Line other = lines.get(j);
                if (!sameRow(l, other)) {
                    continue;
                }
                // 左边那块要右边有字才算头像，右边那块要左边有字
                if (atLeft ? other.left > l.left : other.left < l.left) {
                    paired = true;
                    break;
                }
            }
            // 判不准就留着。多一行噪音，总好过把真消息吃掉。
            if (!paired) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * 两行是不是画在同一水平行上。
     *
     * <p>不能用"有交叠就算"：昵称的上边框常常和上一条消息的下边框差一两像素交叠，
     * 那样会把两条不同的消息粘成一条（实测把「48啊 我几把算错了」和下一行的
     * 「管理员 韵. 对啊」拼到了一起）。要求重叠部分占较矮那行的一半以上，
     * 真正并排的两段（头衔与昵称、头像与昵称）自然满足，首尾相接的一两像素则不满足。
     *
     * <p>不用"顶边相差几像素"是因为各 App 的并排元素并不严格顶对齐：抖音那个头像
     * desc 的 top 是 1080，右边昵称是 1107，差 27。
     */
    private static boolean sameRow(int topA, int bottomA, Line b) {
        int overlap = Math.min(bottomA, b.bottom) - Math.max(topA, b.top);
        if (overlap <= 0) {
            return false;
        }
        int shorter = Math.min(bottomA - topA, b.height());
        return overlap * 2 >= shorter;
    }

    private static boolean sameRow(Line a, Line b) {
        return sameRow(a.top, a.bottom, b);
    }

    /**
     * 把同一水平行上的多段文字接成一行。
     *
     * <p>QQ 把「管理员」和「bello」渲染成同一行的两个 TextView，顶边都是 1394。
     * 不合并的话「管理员」自己会变成一条消息，而真正的对话内容拿到的是「bello」这个署名。
     */
    private List<Line> mergeSameRow(List<Line> lines) {
        List<Line> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            Line first = lines.get(i);
            StringBuilder text = new StringBuilder(first.text);
            int top = first.top;
            int bottom = first.bottom;
            int centerX = first.centerX;
            int j = i + 1;
            while (j < lines.size()) {
                Line next = lines.get(j);
                // 与已经攒起来的这段范围比对，而不是只跟第一段比——三段的行也接得上
                if (!sameRow(top, bottom, next)) {
                    break;
                }
                text.append(' ').append(next.text);
                top = Math.min(top, next.top);
                bottom = Math.max(bottom, next.bottom);
                centerX = next.centerX;   // 最右边那段通常是正文
                j++;
            }
            out.add(new Line(text.toString(), top, bottom, first.left, centerX));
            i = j;
        }
        return out;
    }

    /**
     * 剔掉标题栏。
     *
     * <p>聊天内容从标题栏下面开始，所以只按纵向位置判断，不去猜文字内容——猜内容就得给
     * 每个 App 建一张词表，换个 App 就废了。整行都落在顶部 11% 以内才算标题栏。
     *
     * <p>11% 是量出来的：QQ 群聊标题栏底边在 226，而单聊顶上还有一行「在线 某某」，
     * 底边 246，卡在 10%（240）外面漏了进来。放宽到 11%（264）能盖住它，同时第一条
     * 真消息（见过最贴边的一条底边 272）仍在带外。判据用底边而不是顶边，就是为了只切
     * 整行都在带内的，别把滚到最上面、只露了半截的消息削掉。
     */
    private List<Line> dropHeader(List<Line> lines, int height) {
        int band = (int) (height * 0.11);
        List<Line> out = new ArrayList<>(lines.size());
        for (Line l : lines) {
            if (l.bottom > band) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * 屏幕的真实高度。
     *
     * <p>不能用 getResources().getDisplayMetrics()：读屏服务拿到的这份尺寸扣掉了状态栏
     * （实测 1080x2232，而屏幕是 1080x2400），可节点的坐标是含状态栏的绝对坐标。
     * 两套坐标系混用，按比例算出来的标题栏下沿会偏 168 像素——差 0.5 像素就会漏掉
     * 单聊顶上那行「在线 某某」。
     */
    private int realScreenHeight() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return getResources().getDisplayMetrics().heightPixels;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getCurrentWindowMetrics().getBounds().height();
        }
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        return real.heightPixels;
    }

    private String buildTranscript(List<Line> lines, int width, int height) {
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

        int raw = lines.size();
        lines = sanitize(lines, width, height);
        for (Line l : lines) {
            trace("A [" + l.top + "," + l.bottom + "," + l.left + " c" + l.centerX + "] " + head(l.text));
        }

        List<Message> messages = groupMessages(lines, width);
        lastStats = "原始 " + raw + " 行 → " + lastStats;

        // 认出两个以上不同昵称才算群聊；否则按一对一的左右分栏处理
        Set<String> speakers = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m.speaker.length() > 0) {
                speakers.add(m.speaker);
            }
        }
        // 认出一个署名就算群聊。原来要求两个不同昵称，结果「只我一个人在发」的群被判成
        // 单聊，整段走左右分栏——那条系统提示「全员禁言中」就是这样被算成「我」的。
        // 单聊不会有署名行，这里放宽不会误伤：配对本身的约束（行间距、高度差、标点）已经够严。
        boolean groupChat = speakers.size() >= 1;

        int split = dynamicSplit(lines, width);
        boolean dynamic = split > 0;
        if (!dynamic) {
            split = width / 2;
        }
        int margin = Math.max(width / 20, (int) (12 * dm.density));

        // 位置 -> 这一行的几何数据，昵称缺失时用它兜底
        java.util.Map<Integer, Line> lineByTop = new java.util.HashMap<>();
        for (Line l : lines) {
            lineByTop.put(l.top, l);
        }

        int wanted = Prefs.contextLines(this);
        int from = Math.max(0, messages.size() - wanted);

        StringBuilder sb = new StringBuilder();
        String previous = null;
        int kept = 0;
        int named = 0;
        int mine = 0;
        int other = 0;
        int notice = 0;
        int lost = 0;
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.text.equals(previous)) {
                continue;
            }
            previous = m.text;

            Line origin = lineByTop.get(m.top);
            int mx = origin == null ? split : origin.centerX;
            int mleft = origin == null ? 0 : origin.left;

            String who;
            if (groupChat && m.speaker.length() > 0) {
                // 群聊：直接用昵称，让判定模型分得清谁是谁
                who = m.speaker + "：";
                named++;
            } else if (Math.abs(mx - width / 2) < width * 0.05 && mleft > width * 0.20) {
                // 水平居中的是系统提示（「全员禁言中，仅群主和管理员可发言」
                // 「你撤回了一条消息」这类），不属于任何一方。放在署名之后判断：
                // 有署名的消息即使位置居中，那个署名也比位置可信。
                //
                // left 必须一起看。对方发一条长消息时气泡会横跨到屏幕中间，它的
                // centerX 同样落进中线附近——实测「没事，就是今天那事我心里有点不
                // 舒服」centerX 531、中线 540，差 9 像素就被判成系统提示，转录里
                // 整条丢掉说话人，而它恰好是情绪转折的那一句。消息气泡永远贴着它
                // 那一侧的边缘（该次实测 left 恒为 147），真正居中的系统提示左右
                // 留白对称，left 一定更靠里。
                who = "";
                notice++;
            } else if (groupChat) {
                // 群聊里没认出署名的那些。这个场景下"左边是对方、右边是我"不成立：
                // 所有人都在左边，两簇聚类能凭空造出一条中线来。所以只有明显贴到
                // 右边缘才算自己发的，其余一律按对方处理——把别人的话算到我头上，
                // 比标错一次"对方"代价大得多。
                if (mx > width * 0.62) {
                    who = "我：";
                    mine++;
                } else if (mx < split - margin) {
                    who = "对方：";
                    other++;
                } else {
                    who = "";
                    lost++;
                }
            } else if (mx < split - margin) {
                who = "对方：";
                other++;
            } else if (mx > split + margin) {
                who = "我：";
                mine++;
            } else {
                // 一对一的兜底：既贴不到左、也贴不到右。长消息最容易掉进这里
                who = "";
                lost++;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(who).append(m.text);
            kept++;
            // 只记前缀和长度，不记正文：够判断"谁被算成了谁"，不至于把整段聊天抄进日志
            trace("T [" + who + "] len=" + m.text.length());
        }
        lastTranscriptLines = kept;
        lastReading = reading(kept, named, mine, other, notice, lost, groupChat);
        return sb.toString();
    }

    /**
     * 把这次转录的构成拼成一行给用户看的摘要。
     *
     * <p>判定结论和它的输入之间原本没有任何可核对的东西。用户在屏幕上看到的文字，和
     * 无障碍树里的文字不是一回事——一条消息在树里是四五个节点，长消息会丢掉说话人，
     * 标题栏和头像描述会混进来——而结论的措辞天然通顺，读错了从结论上根本看不出来。
     *
     * <p>只报结构、不报内容，是唯一既能核对又不占地方的做法：用户清楚自己刚才说了几句、
     * 群里几个人在说话，对不上就说明读坏了。顺带也不碰隐私，这一行不含任何原文。
     */
    private static String reading(int kept, int named, int mine, int other, int notice,
            int lost, boolean groupChat) {
        StringBuilder sb = new StringBuilder("读到 ").append(kept).append(" 条");

        // 正常情况只报条数。归属分布常驻在卡片上是白占一行小字——它真正有用的时刻
        // 是"这次读坏了"，所以只在读出来不对劲的时候才展开细节。
        boolean odd = lost > 0
                || (groupChat ? named < kept : kept > 1 && (mine == 0 || other == 0));
        if (!odd) {
            return sb.toString();
        }

        if (groupChat) {
            // 不报"几个人"：群聊里自己那条也带昵称，仅凭文本分不出哪个昵称是自己，
            // 报出来的人头数会多一个，反而误导。
            sb.append(" · 署名 ").append(named).append('/').append(kept);
        } else {
            sb.append(" · 对方 ").append(other).append(" · 我 ").append(mine);
        }
        if (notice > 0) {
            sb.append(" · 系统提示 ").append(notice);
        }
        if (lost > 0) {
            sb.append(" · 有 ").append(lost).append(" 条没认出发言人");
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
        // 迭代完再看一次两簇中心的距离。开头那次 min/max 差得远，不代表聚类结果真的
        // 分得开：群里气泡全在左侧时，min/max 能差 200 像素以上，可两簇中心其实挨在
        // 一起，取平均就成了一条凭空造出来的中线，把左侧消息判到"我"那一栏去。
        if (hi - lo < width * 0.15) {
            return -1;
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
