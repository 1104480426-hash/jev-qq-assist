package ai.jev.assist;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 悬浮球常驻服务：点一下就拿当前聊天窗口的转录去问 Jev，把类型化判定摊在屏幕上。
 *
 * <p>它只读不写：不注入文本、不点发送。决策给你，动作你自己做。
 *
 * <p>结果分两级显示。判定完成后**默认只留一条贴着悬浮球的胶囊**（一行摘要，约 50dp），
 * 因为聊天时最挡人的就是一大块居中面板。点胶囊才展开成完整卡片。
 * 交互闭环：球 = 开/关，胶囊 = 摘要，卡片 = 详情。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "ai.jev.assist.action.START";
    public static final String ACTION_STOP = "ai.jev.assist.action.STOP";

    /** 悬浮球是否在运行，供设置界面显示状态。 */
    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "jev_assist_overlay";
    private static final int NOTIFICATION_ID = 4401;
    private static final int DRAG_SLOP_DP = 6;
    private static final int EDGE_MARGIN_DP = 6;
    private static final int BALL_SIZE_DP = 52;
    private static final int PILL_GAP_DP = 7;
    private static final int PILL_MAX_WIDTH_DP = 236;

    private WindowManager windowManager;
    private View ballView;
    private View cardView;
    private View pillView;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cardParams;
    private WindowManager.LayoutParams pillParams;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int dragSlopPx;
    private int ballSizePx;
    private int screenW;
    private int screenH;
    private float density;

    private Runnable progressTick;
    private int progressDots;

    // 当前这次判定的内容，胶囊和卡片两种形态之间来回切换时要用
    private String headlineText = "";
    private String bodyText = "";
    private String metaText = "";
    private String pillText = "";
    private boolean riskHigh = false;
    private boolean retryable = false;

    /** 判定期间用户主动关掉了：迟到的结果不该再弹回来。 */
    private boolean dismissed = false;
    /** 请求序号，避免旧请求的结果盖掉新请求的。 */
    private int requestSeq = 0;

    // 当前屏幕上摆着什么。悬浮球是唯一的总开关，按这个状态循环推进：
    // 无 -> 判定（卡片形态，带进度动画）-> 胶囊 -> 展开成卡片 -> 无 …
    private static final int STATE_NONE = 0;
    private static final int STATE_CARD = 1;
    private static final int STATE_PILL = 2;
    /** 判定还没回来。形态和结果胶囊一样，但点它是收掉而不是展开。 */
    private static final int STATE_WAITING = 3;
    private int displayState = STATE_NONE;
    /** 收起动画进行中。挡住重入，否则连点两下会把球叠出来两个。 */
    private boolean collapsing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
        dragSlopPx = (int) (DRAG_SLOP_DP * density);
        ballSizePx = (int) (BALL_SIZE_DP * density);
        syncScreenSize();

        // 球上的判定染色要知道用户什么时候离开了那个聊天窗口
        ChatAccessibilityService.setWindowWatcher(new ChatAccessibilityService.WindowWatcher() {
            @Override
            public void onActivePackage(String pkg) {
                onActivePackageChanged(pkg);
            }
        });
    }

    /**
     * 活动窗口换了。
     *
     * <p>判定是冲着某个窗口做的，那个窗口不在前台了，球上的颜色就不再代表眼前的东西。
     * 这比按时间淡出准：用户切走的一瞬间就该退回去，而不是等够 90 秒。
     *
     * <p>90 秒那个定时器仍然留着兜底——同一条聊天里换对话人，包名不变，这条路径看不见。
     */
    private void onActivePackageChanged(String pkg) {
        // 拉通知栏、接电话这类系统面板不算离开聊天，别把颜色抹掉
        if (pkg.startsWith("com.android.systemui")) {
            return;
        }
        if (ballBand >= 0 && !pkg.equals(ChatAccessibilityService.pinnedPkg())) {
            clearBallTint();
        }
    }

    private void syncScreenSize() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (ballView == null) {
            showBall();
        }
        running = true;
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopProgress();
        removeCard();
        removePill();
        removeBall();
        ChatAccessibilityService.setWindowWatcher(null);
        super.onDestroy();
    }

    private void removeBall() {
        if (ballView != null && windowManager != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Exception ignored) {
                // 已移除
            }
            ballView = null;
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.fgs_channel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(getString(R.string.fgs_title))
                .setContentText(getString(R.string.fgs_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---- 悬浮球 ----

    private void showBall() {
        ballView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 尺寸在这里定死：inflate(res, null) 会丢掉 XML 里的 layout_width/height，
        // 只靠 wrap_content 会让球被挤成几十像素的一条。
        ballParams = new WindowManager.LayoutParams(
                ballSizePx,
                ballSizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;

        ballParams.x = (int) (Prefs.ballX(this) * screenW);
        ballParams.y = (int) (Prefs.ballY(this) * screenH);
        clampBallInside();

        ballView.setOnTouchListener(new BallTouchListener());

        try {
            windowManager.addView(ballView, ballParams);
        } catch (Exception e) {
            // 悬浮窗权限被拒时会走到这里
            stopSelf();
            return;
        }
        paintBall();
    }

    // ---- 球上的判定结果染色 ----
    //
    // 球一直在屏幕上，所以它比胶囊上那个点更值得承载结论：不展开也能看见。
    // 但判定是有时效的——切到别的聊天、过几分钟，这个颜色就不再代表眼前这段。
    // 所以它自己会淡掉，退回白玻璃。宁可少提示，也不要给一个过期的警告。

    /** 当前该染的档位：0 绿、1 黄、2 红、-1 未判定。 */
    private int ballBand = -1;
    /** 染色保持多久，之后淡回白。 */
    private static final long TINT_HOLD_MS = 90_000L;
    private Runnable tintFade;

    /** 把 ballBand 画到球上。球每次重建都要重新调用，因为背景是新 inflate 的。 */
    private void paintBall() {
        android.graphics.drawable.GradientDrawable tint = ballTintLayer();
        if (tint == null) {
            return;
        }
        tint.setColor(ballBand < 0 ? 0x00000000 : bandColor(ballBand));
    }

    private android.graphics.drawable.GradientDrawable ballTintLayer() {
        if (ballView == null) {
            return null;
        }
        android.graphics.drawable.Drawable bg = ballView.getBackground();
        if (!(bg instanceof android.graphics.drawable.LayerDrawable)) {
            return null;
        }
        android.graphics.drawable.Drawable layer =
                ((android.graphics.drawable.LayerDrawable) bg.mutate())
                        .findDrawableByLayerId(R.id.ball_tint);
        return layer instanceof android.graphics.drawable.GradientDrawable
                ? (android.graphics.drawable.GradientDrawable) layer
                : null;
    }

    /** 低饱和的染色，浓度压在六成左右：白色珠体透出来才像玻璃，不是一颗彩球。 */
    private static int bandColor(int band) {
        switch (band) {
            case 2:
                return 0xA6E05A4A;
            case 1:
                return 0xA6E0A93C;
            default:
                return 0xA62BC4A0;
        }
    }

    /** 判定出结果时调用：染色，并安排它在 TINT_HOLD_MS 之后淡掉。 */
    private void tintBall(int band) {
        ballBand = band;
        if (tintFade != null) {
            ui.removeCallbacks(tintFade);
            tintFade = null;
        }
        paintBall();

        tintFade = new Runnable() {
            @Override
            public void run() {
                fadeTintOut();
            }
        };
        ui.postDelayed(tintFade, TINT_HOLD_MS);
    }

    /** 把染色层的浓度降到 0。渐变而不是硬切，免得球突然变色。 */
    private void fadeTintOut() {
        if (ballBand < 0) {
            return;     // 已经是白的，再淡一次会从绿色开始，那是错的
        }
        final android.graphics.drawable.GradientDrawable tint = ballTintLayer();
        final int argb = bandColor(ballBand);
        ballBand = -1;
        if (tint == null) {
            return;
        }
        android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofInt((argb >>> 24), 0);
        va.setDuration(700);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                int alpha = (int) a.getAnimatedValue();
                tint.setColor((alpha << 24) | (argb & 0x00FFFFFF));
            }
        });
        va.start();
    }

    /** 立刻把颜色退回去：判定针对的窗口已经不在前台了。 */
    private void clearBallTint() {
        if (ballBand < 0) {
            return;
        }
        if (tintFade != null) {
            ui.removeCallbacks(tintFade);
            tintFade = null;
        }
        fadeTintOut();
    }

    private void clampBallInside() {
        ballParams.x = Math.max(0, Math.min(screenW - ballSizePx, ballParams.x));
        ballParams.y = Math.max(0, Math.min(screenH - ballSizePx, ballParams.y));
    }

    private final class BallTouchListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = ballParams.x;
                    startY = ballParams.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    moved = false;
                    ballView.setAlpha(0.75f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - touchX);
                    int dy = (int) (event.getRawY() - touchY);
                    if (Math.abs(dx) > dragSlopPx || Math.abs(dy) > dragSlopPx) {
                        moved = true;
                    }
                    ballParams.x = startX + dx;
                    ballParams.y = startY + dy;
                    clampBallInside();
                    moveView(ballView, ballParams);
                    // 胶囊和卡片都挂在球上，球一动它们跟着走
                    if (pillView != null) {
                        layoutPill();
                    }
                    if (cardView != null) {
                        layoutCard();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ballView.setAlpha(1f);
                    if (moved) {
                        snapBallToEdge();
                    } else {
                        onBallTap();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** 停手后横向吸到离得近的那一侧，别停在聊天区中间挡话。 */
    private void snapBallToEdge() {
        int margin = (int) (EDGE_MARGIN_DP * density);
        boolean toLeft = ballParams.x + ballSizePx / 2 < screenW / 2;
        int targetX = toLeft ? margin : screenW - ballSizePx - margin;
        commitBallPosition(targetX, ballParams.y);
    }

    private void commitBallPosition(final int targetX, final int targetY) {
        final int fromX = ballParams.x;
        if (fromX == targetX) {
            ballParams.y = targetY;
            moveView(ballView, ballParams);
            saveBallPosition();
            return;
        }
        ValueAnimator anim = ValueAnimator.ofInt(fromX, targetX);
        anim.setDuration(140);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ballParams.x = (int) animation.getAnimatedValue();
                ballParams.y = targetY;
                moveView(ballView, ballParams);
                if (pillView != null) {
                    layoutPill();
                }
                if (cardView != null) {
                    layoutCard();
                }
            }
        });
        anim.start();
        ballParams.y = targetY;
        saveBallPosition();
    }

    private void saveBallPosition() {
        if (screenW <= 0 || screenH <= 0) {
            return;
        }
        Prefs.setBallPos(this, ballParams.x / (float) screenW, ballParams.y / (float) screenH);
    }

    private void moveView(View view, WindowManager.LayoutParams params) {
        if (view == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Exception ignored) {
            // 视图已移除
        }
    }

    // ---- 判定 ----

    /**
     * 悬浮球是唯一的总开关。每次点击把状态推进一格：
     *
     * <pre>
     *   无 ──点──> 判定中（胶囊，文字在动）
     *   判定中 ──点──> 直接收掉
     *   胶囊 ──点──> 展开成完整卡片
     *   卡片 ──点──> 全部收起，回到无
     * </pre>
     *
     * <p>所以卡片上不再需要「收起」按钮，展开和收起都由球负责。
     *
     * <p>判定中也用胶囊，不用卡片：否则点一下球先弹出一大块面板、判定回来又缩成一条，
     * 一伸一缩看着像是界面出了错。等待和结果本来就该是同一个形态。
     */
    private void onBallTap() {
        switch (displayState) {
            case STATE_PILL:
                // 已有结果，展开详情
                removePill();
                showCard();
                return;
            case STATE_WAITING:
            case STATE_CARD:
                // 判定还没回来、或者正在看卡片：点一下就全收掉
                dismissed = true;
                removeCard();
                removePill();
                return;
            default:
                break;
        }

        dismissed = false;

        // 现抓当前窗口，不吃缓存——缓存可能还停在桌面或上一个 App 上
        String transcript = ChatAccessibilityService.captureNow();
        if (transcript == null) {
            transcript = ChatAccessibilityService.cachedTranscript();
        }
        if (transcript == null || transcript.length() == 0) {
            headlineText = "这个窗口读不到文字";
            bodyText = "当前界面对无障碍没有暴露文字，或者读屏服务没在运行。"
                    + "换到聊天窗口再点一次；微信整屏都是自绘的，读不到是正常的。";
            metaText = "";
            pillText = "读不到文字";
            riskHigh = true;
            retryable = false;
            showCard();
            return;
        }
        String hint = Prefs.isLocal(this)
                ? "已取最近 " + Prefs.contextLines(this) + " 行对话，本地模型判定中（首次会加载模型，稍慢）"
                : "已取最近 " + Prefs.contextLines(this) + " 行对话，正在问远端判定端点。";
        // 卡片内容照旧准备好（判定途中被展开就是这个），但等待态本身用胶囊显示
        headlineText = "正在判定";
        bodyText = hint;
        metaText = "";
        retryable = false;
        pillText = "正在判定";
        riskHigh = false;
        showPill(STATE_WAITING);
        startProgress();
        ask(transcript);
    }

    /** 上一次判定是否已经出过结果——决定再点球是收掉还是重新判。 */
    private boolean cardDataFromLastRun = false;

    private void ask(final String transcript) {
        final boolean local = Prefs.isLocal(this);
        final String endpoint = Prefs.endpoint(this);
        final String model = Prefs.model(this);
        final String key = Prefs.apiKey(this);
        final int seq = ++requestSeq;

        new Thread(new Runnable() {
            @Override
            public void run() {
                final long started = System.currentTimeMillis();
                try {
                    final JSONObject answers;
                    final String meta;
                    if (local) {
                        LocalJudge judge = LocalJudge.get(getApplicationContext());
                        answers = judge.judge(transcript);
                        meta = "本地 bge-small-zh · " + (System.currentTimeMillis() - started) + " ms";
                    } else {
                        JevClient.Result result = JevClient.decide(endpoint, key,
                                DecisionSpec.build(model, transcript));
                        if (!result.ok) {
                            final String err = result.error;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (seq != requestSeq || dismissed) {
                                        return;
                                    }
                                    headlineText = "判定失败";
                                    bodyText = err;
                                    metaText = "端点 " + endpoint;
                                    pillText = "判定失败";
                                    riskHigh = true;
                                    retryable = false;
                                    showCard();
                                }
                            });
                            return;
                        }
                        answers = result.answers;
                        meta = "远端 " + result.model + " · " + result.elapsedMs + " ms";
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != requestSeq || dismissed) {
                                return;
                            }
                            headlineText = DecisionSpec.plainHeadline(answers);
                            bodyText = DecisionSpec.plainAdvice(answers);
                            // 原始判定不再占正文，压成一行小字放在下面当依据。
                            // 依据行下面再补一行输入摘要：结论看不出读没读对，用户需要一个
                            // 能跟刚才那段聊天对上的锚点——对不上就说明这次读坏了。
                            String reading = ChatAccessibilityService.lastReading();
                            metaText = DecisionSpec.evidence(answers)
                                    + (reading.length() > 0 ? " · " + reading : "");
                            pillText = headlineText;
                            riskHigh = DecisionSpec.isRisky(answers);

                            // 上下文太短时那份结论本身就不可信，标题一并换掉。胶囊是默认
                            // 形态，只在小字里提示等于没提示——用户看的就是这一行。
                            String thin = DecisionSpec.thinContextWarning(
                                    ChatAccessibilityService.lastTranscriptLines());
                            if (thin != null) {
                                headlineText = thin;
                                pillText = thin;
                                bodyText = DecisionSpec.thinContextNote() + "\n" + bodyText;
                            }

                            retryable = true;
                            // 结论同时染到球上：球一直看得见，不用展开就知道这条要不要小心
                            tintBall(DecisionSpec.colorBand(answers));
                            // 判定完成默认只留胶囊：一大块面板在聊天里太挡人
                            showPill();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != requestSeq || dismissed) {
                                return;
                            }
                            headlineText = "本地判定失败";
                            bodyText = String.valueOf(e.getMessage());
                            metaText = "本地模型未能加载，可在设置里看具体原因。";
                            pillText = "本地判定失败";
                            riskHigh = true;
                            retryable = false;
                            showCard();
                        }
                    });
                }
            }
        }, "jev-decide").start();
    }

    // ---- 胶囊：结果默认形态 ----

    private void showPill() {
        showPill(STATE_PILL);
    }

    /**
     * @param state {@link #STATE_PILL} 是判定结果，点它展开详情；
     *              {@link #STATE_WAITING} 是判定途中，点它等同于点球——收掉
     */
    private void showPill(final int state) {
        stopProgress();
        removeCard();
        removePill();

        pillView = LayoutInflater.from(this).inflate(R.layout.decision_pill, null);
        ((TextView) pillView.findViewById(R.id.pill_text)).setText(pillText);

        // 等待态点了是收掉，没有详情可展开，所以别挂着会骗人的「展开」
        View expand = pillView.findViewById(R.id.pill_expand);
        if (expand != null) {
            expand.setVisibility(state == STATE_WAITING ? View.GONE : View.VISIBLE);
        }

        // 风险高的时候点变橙，扫一眼就知道这条要不要认真对待
        View dot = pillView.findViewById(R.id.pill_dot);
        if (dot != null && dot.getBackground() instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) dot.getBackground().mutate();
            shape.setColor(riskHigh ? 0xFFE08A3C : 0xFF2BC4A0);
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int maxW = (int) (PILL_MAX_WIDTH_DP * density);
        pillParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        pillParams.gravity = Gravity.TOP | Gravity.START;

        pillView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state == STATE_WAITING) {
                    // 结果还没回来，没有详情可展开，点了就收掉
                    dismissed = true;
                    removeCard();
                    removePill();
                    return;
                }
                // 点胶囊等同于再点一次悬浮球，直接进详情
                removePill();
                showCard();
            }
        });

        try {
            windowManager.addView(pillView, pillParams);
        } catch (Exception e) {
            pillView = null;
            return;
        }
        displayState = state;

        // 加进去之后才知道它多宽，这时才能把它摆在球旁边
        pillView.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        layoutPill();
    }

    /** 胶囊贴着球放：球在左半屏就摆到球右边，反之摆左边，垂直与球齐平。 */
    private void layoutPill() {
        if (pillView == null || pillParams == null || ballView == null) {
            return;
        }
        int gap = (int) (PILL_GAP_DP * density);
        int pillW = pillView.getMeasuredWidth() > 0
                ? pillView.getMeasuredWidth()
                : (int) (150 * density);
        int pillH = pillView.getMeasuredHeight() > 0
                ? pillView.getMeasuredHeight()
                : (int) (44 * density);

        boolean ballOnLeft = ballParams.x + ballSizePx / 2 < screenW / 2;
        int x = ballOnLeft
                ? ballParams.x + ballSizePx + gap
                : ballParams.x - pillW - gap;
        int y = ballParams.y + (ballSizePx - pillH) / 2;

        pillParams.x = Math.max(0, Math.min(screenW - pillW, x));
        pillParams.y = Math.max(0, Math.min(screenH - pillH, y));
        moveView(pillView, pillParams);
    }

    private void removePill() {
        if (pillView != null && windowManager != null) {
            try {
                windowManager.removeView(pillView);
            } catch (Exception ignored) {
                // 已移除
            }
            pillView = null;
        }
        syncDisplayState();
    }

    /** 两种视图都没了才算回到「无」——showCard 内部会先 removePill 再挂新视图。 */
    private void syncDisplayState() {
        if (cardView == null && pillView == null) {
            displayState = STATE_NONE;
        }
    }

    // ---- 卡片：点开胶囊才出现的详情 ----

    private void showCard() {
        stopProgress();
        removeCard();

        cardView = LayoutInflater.from(this).inflate(R.layout.decision_card, null);
        ((TextView) cardView.findViewById(R.id.card_headline)).setText(headlineText);
        ((TextView) cardView.findViewById(R.id.card_body)).setText(bodyText);
        ((TextView) cardView.findViewById(R.id.card_meta)).setText(metaText);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 这里刻意不用 FLAG_BLUR_BEHIND。实测在 HyperOS 上它的作用范围不稳定：
        // 同样的窗口参数（局部 735x601、blurBehindRadius=47），有时只糊卡片背后，
        // 有时把整个屏幕都糊掉，聊天界面就读不了了。一个会偶发毁掉主场景的效果不值当，
        // 所以玻璃质感全部由 bg_card 的分层来出。
        // 加宽一点：正文每行多放几个字，换行少一行就比加宽带来的面积更划算
        int cardWidth = (int) (344 * density);
        cardParams = new WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.TOP | Gravity.START;

        View header = cardView.findViewById(R.id.card_header);
        header.setOnTouchListener(new CardDragListener(cardWidth));

        cardView.findViewById(R.id.card_collapse).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseToBall();
            }
        });
        cardView.findViewById(R.id.card_again).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (retryable || ChatAccessibilityService.cachedTranscript().length() > 0) {
                    // 重判要立刻进判定流程，不等收起动画跑完
                    writeBallPosFromCard();
                    collapsing = false;
                    removeCard();
                    removePill();
                    showBall();
                    onBallTap();
                }
            }
        });

        // 长按整张卡片复制。原来这是个按钮，现在按钮位让给了「收起」：收起每次都要用，
        // 复制偶尔才用，低频动作退到手势。
        cardView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyCardToClipboard();
                return true;
            }
        });

        try {
            windowManager.addView(cardView, cardParams);
        } catch (Exception e) {
            cardView = null;
            return;
        }
        displayState = STATE_CARD;
        // 卡片已经上屏，这时才让球让位。放在 addView 之前的话，一旦添加失败球就没了，
        // 屏幕上什么都不剩。卡片是球展开后的形态，两个同时显示就变回"两个东西"，
        // 它们的位置关系又得靠算法去猜，那正是之前别扭的来源。
        removeBall();
        // 加进去之后才量得出它多高，这时才能把它摆到球旁边
        cardView.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        layoutCard();

        // 从球心那个点放大出来。缩放中心设成球在卡片里的相对位置，所以它看起来是从
        // 球原先待的地方长开的；不动画的话卡片就是"啪"地出现，位置再对也还是突兀。
        float pivotX = ballParams.x + ballSizePx / 2f - cardParams.x;
        float pivotY = ballParams.y + ballSizePx / 2f - cardParams.y;
        cardView.setPivotX(pivotX);
        cardView.setPivotY(pivotY);
        cardView.setAlpha(0f);
        cardView.setScaleX(0.72f);
        cardView.setScaleY(0.72f);
        cardView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /**
     * 收起卡片，球回到卡片所在的位置。
     *
     * <p>两者共用一个中心，所以位置是连续的：在哪展开，就在哪收起，下次展开还在原处。
     * 球的位置要写回偏好——卡片可能被拖过，拖动之后球就该从新地方出来。
     *
     * <p>卡片先缩回球心再换成球，和展开的方向相反，这样来回是一次连贯的开合。
     */
    private void collapseToBall() {
        if (collapsing) {
            return;
        }
        writeBallPosFromCard();

        if (cardView == null) {
            removePill();
            showBall();
            return;
        }

        collapsing = true;

        // 先把球放出来（就在卡片中心，位置上面已经写好了），再从透明渐显到不透明。
        // 直接等卡片缩完再显示球会闪一下：那一刻卡片已经 alpha 0、内容看不见了，
        // 却还占着窗口，屏幕上只剩聊天背景，下一帧球才出现。两个视图交叉淡化就没有
        // 这个空档——任何时刻都至少有一个可见。球后添加，所以在卡片上层。
        showBall();
        if (ballView != null) {
            ballView.setAlpha(0f);
        }

        cardView.animate()
                .alpha(0f)
                .scaleX(0.72f)
                .scaleY(0.72f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        collapsing = false;
                        removeCard();
                    }
                })
                .start();

        if (ballView != null) {
            ballView.animate()
                    .alpha(1f)
                    .setStartDelay(50)
                    .setDuration(110)
                    .start();
        }

        // 胶囊这时候就该消失，不必等卡片缩完
        removePill();
    }

    /** 把球的位置更新成卡片当前的中心，并写回偏好。 */
    private void writeBallPosFromCard() {
        if (cardParams == null || cardView == null) {
            return;
        }
        int cardH = cardView.getMeasuredHeight();
        if (cardH <= 0) {
            cardH = (int) (190 * density);
        }
        int ballX = cardParams.x + cardParams.width / 2 - ballSizePx / 2;
        int ballY = cardParams.y + cardH / 2 - ballSizePx / 2;
        Prefs.setBallPos(this,
                Math.max(0, Math.min(screenW - ballSizePx, ballX)) / (float) screenW,
                Math.max(0, Math.min(screenH - ballSizePx, ballY)) / (float) screenH);
    }

    /**
     * 卡片就长在球的位置上。
     *
     * <p>球和卡片不是两个东西，是同一个东西的两种形态：展开时球消失、卡片在它原来的
     * 位置出现，收起时反过来。位置关系由构造保证，所以这里不做任何避让。之前那套
     * "在屏幕上找一段空白"的逻辑因此失去了存在理由——而且它在密集聊天里必然失败
     * （球旁边根本放不下整张卡片），结果是把卡片推到屏幕另一头，比压着字难看得多。
     *
     * <p>中心对齐之后再夹进屏幕内：球常贴在边缘，不夹的话卡片会有一半在屏幕外。
     */
    private void layoutCard() {
        if (cardView == null || cardParams == null) {
            return;
        }
        int cardW = cardParams.width;
        int cardH = cardView.getMeasuredHeight();
        if (cardH <= 0) {
            cardH = (int) (190 * density);
        }

        int ballCx = ballParams.x + ballSizePx / 2;
        int ballCy = ballParams.y + ballSizePx / 2;
        cardParams.x = ballCx - cardW / 2;
        cardParams.y = ballCy - cardH / 2;

        clampCardInside(cardW);
        moveView(cardView, cardParams);
    }

    private void clampCardInside(int cardWidth) {
        cardParams.x = Math.max(0, Math.min(screenW - cardWidth, cardParams.x));
        cardParams.y = Math.max(0, Math.min(screenH - (int) (120 * density), cardParams.y));
    }

    private final class CardDragListener implements View.OnTouchListener {
        private final int cardWidth;
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;
        private boolean moved;

        CardDragListener(int cardWidth) {
            this.cardWidth = cardWidth;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = cardParams.x;
                    startY = cardParams.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - touchX);
                    int dy = (int) (event.getRawY() - touchY);
                    if (Math.abs(dx) > dragSlopPx || Math.abs(dy) > dragSlopPx) {
                        moved = true;
                    }
                    if (moved) {
                        cardParams.x = startX + dx;
                        cardParams.y = startY + dy;
                        clampCardInside(cardWidth);
                        moveView(cardView, cardParams);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved && screenW > 0 && screenH > 0) {
                        // 记的是相对球的偏移，不是绝对坐标：以后球挪到哪，卡片跟到哪
                        Prefs.setCardOffset(OverlayService.this,
                                (cardParams.x - ballParams.x) / (float) screenW,
                                (cardParams.y - ballParams.y) / (float) screenH);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void copyCardToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("jev", headlineText + "\n" + bodyText));
            toast("已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // 通知权限受限时忽略
        }
    }

    /** 让「正在判定」看起来在动：本地首次要加载模型，几秒空窗很容易被当成卡死。 */
    private void startProgress() {
        stopProgress();
        progressDots = 0;
        progressTick = new Runnable() {
            @Override
            public void run() {
                if (pillView == null) {
                    return;
                }
                View text = pillView.findViewById(R.id.pill_text);
                if (text instanceof TextView) {
                    StringBuilder sb = new StringBuilder("正在判定");
                    for (int i = 0; i < progressDots; i++) {
                        sb.append('.');
                    }
                    ((TextView) text).setText(sb.toString());
                }
                progressDots = (progressDots + 1) % 4;
                ui.postDelayed(this, 450);
            }
        };
        ui.post(progressTick);
    }

    private void stopProgress() {
        if (progressTick != null) {
            ui.removeCallbacks(progressTick);
            progressTick = null;
        }
    }

    private void removeCard() {
        stopProgress();
        if (cardView != null && windowManager != null) {
            try {
                windowManager.removeView(cardView);
            } catch (Exception ignored) {
                // 已移除
            }
            cardView = null;
        }
        syncDisplayState();
    }
}
