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

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
        dragSlopPx = (int) (DRAG_SLOP_DP * density);
        ballSizePx = (int) (BALL_SIZE_DP * density);
        syncScreenSize();
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
        }
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

    private void onBallTap() {
        // 有结果（胶囊或卡片）就全部收掉，再点一次重新判定。
        // 因为不能开 FLAG_WATCH_OUTSIDE_TOUCH（会把窗口表面撑到全屏），
        // 显示状态就统一由悬浮球切换，和 iOS 的辅助触控一个逻辑。
        if (cardView != null || pillView != null) {
            dismissed = true;
            removeCard();
            removePill();
            return;
        }
        dismissed = false;

        String transcript = ChatAccessibilityService.cachedTranscript();
        if (transcript.length() == 0) {
            headlineText = "还没抓到聊天内容";
            bodyText = "先在设置里开启「Jev 聊天参谋」无障碍服务，然后切到聊天窗口停一下，再点我。";
            metaText = "";
            pillText = "还没抓到聊天内容";
            riskHigh = false;
            retryable = false;
            showCard();
            return;
        }
        String hint = Prefs.isLocal(this)
                ? "已取最近 " + Prefs.contextLines(this) + " 行对话，本地模型判定中（首次会加载模型，稍慢）"
                : "已取最近 " + Prefs.contextLines(this) + " 行对话，正在问远端判定端点。";
        headlineText = "正在判定";
        bodyText = hint;
        metaText = "";
        retryable = false;
        showCard();
        startProgress();
        ask(transcript);
    }

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
                            headlineText = DecisionSpec.headline(answers);
                            bodyText = DecisionSpec.renderAll(answers);
                            metaText = meta;
                            pillText = DecisionSpec.pillSummary(answers);
                            riskHigh = DecisionSpec.isRisky(answers);
                            retryable = true;
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
        stopProgress();
        removeCard();
        removePill();

        pillView = LayoutInflater.from(this).inflate(R.layout.decision_pill, null);
        ((TextView) pillView.findViewById(R.id.pill_text)).setText(pillText);

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
        int cardWidth = (int) (280 * density);
        cardParams = new WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.TOP | Gravity.START;

        View header = cardView.findViewById(R.id.card_header);
        header.setOnTouchListener(new CardDragListener(cardWidth));

        cardView.findViewById(R.id.card_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 有结果就退回胶囊，纯错误信息才整个关掉
                if (retryable) {
                    removeCard();
                    showPill();
                } else {
                    removeCard();
                }
            }
        });
        cardView.findViewById(R.id.card_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCardToClipboard();
            }
        });
        cardView.findViewById(R.id.card_again).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (retryable || ChatAccessibilityService.cachedTranscript().length() > 0) {
                    onBallTap();
                }
            }
        });

        try {
            windowManager.addView(cardView, cardParams);
        } catch (Exception e) {
            cardView = null;
            return;
        }
        // 加进去之后才量得出它多高，这时才能把它摆到球旁边
        cardView.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        layoutCard();
    }

    /**
     * 卡片位置 = 悬浮球位置 + 相对偏移。
     *
     * <p>这样球一挪卡片就跟过去，展开态和胶囊的行为一致。用户没手动拖过卡片时，
     * 偏移由代码算：贴在球背向屏幕中心的那一侧，垂直与球齐平。
     */
    private void layoutCard() {
        if (cardView == null || cardParams == null || ballView == null) {
            return;
        }
        int cardW = cardParams.width;
        int cardH = cardView.getMeasuredHeight();
        if (cardH <= 0) {
            cardH = (int) (190 * density);
        }
        int gap = (int) (PILL_GAP_DP * density);

        if (Prefs.cardPinned(this)) {
            cardParams.x = ballParams.x + (int) (Prefs.cardOffsetX(this) * screenW);
            cardParams.y = ballParams.y + (int) (Prefs.cardOffsetY(this) * screenH);
        } else {
            boolean ballOnLeft = ballParams.x + ballSizePx / 2 < screenW / 2;
            cardParams.x = ballOnLeft
                    ? ballParams.x + ballSizePx + gap
                    : ballParams.x - cardW - gap;
            cardParams.y = ballParams.y + (ballSizePx - cardH) / 2;
        }
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
                if (cardView == null) {
                    return;
                }
                View headline = cardView.findViewById(R.id.card_headline);
                if (headline instanceof TextView) {
                    StringBuilder sb = new StringBuilder("正在判定");
                    for (int i = 0; i < progressDots; i++) {
                        sb.append('.');
                    }
                    ((TextView) headline).setText(sb.toString());
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
    }
}
