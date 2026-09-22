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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 悬浮球常驻服务：点一下就拿当前聊天窗口的转录去问 Jev，把类型化判定摊在屏幕上。
 *
 * <p>它只读不写：不注入文本、不点发送。决策给你，动作你自己做。
 *
 * <p>交互上按聊天场景调过几轮：球松手吸附到最近的侧边并且位置会记住，卡片默认落在
 * 屏幕上方（最新消息在底部，压住它最难受）、可以拖、点卡片外面就收起。
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

    private WindowManager windowManager;
    private View ballView;
    private View cardView;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cardParams;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int dragSlopPx;
    private int ballSizePx;
    private int screenW;
    private int screenH;
    private float density;

    /** 判定进行中的点点动画，避免首次加载模型时看着像卡死。 */
    private Runnable progressTick;
    private int progressDots;

    private String cardHeadline = "";
    private String cardBody = "";

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

        // 位置按比例还原，转屏换分辨率后仍落在原来那一侧
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

    /** 拖动悬浮球；位移小于阈值算点击，松手吸附到最近的侧边。 */
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

    /** 把球移到目标位置并落盘。用一段很短的横向动画，比瞬移自然。 */
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
        String transcript = ChatAccessibilityService.cachedTranscript();
        if (transcript.length() == 0) {
            showCard("还没抓到聊天内容",
                    "先在设置里开启「Jev 聊天参谋」无障碍服务，然后切到聊天窗口停一下，再点我。",
                    "", false);
            return;
        }
        String hint = Prefs.isLocal(this)
                ? "已取最近 " + Prefs.contextLines(this) + " 行对话，本地模型判定中（首次会加载模型，稍慢）"
                : "已取最近 " + Prefs.contextLines(this) + " 行对话，正在问远端判定端点。";
        showCard("正在判定", hint, "", false);
        startProgress();
        ask(transcript);
    }

    private void ask(final String transcript) {
        final boolean local = Prefs.isLocal(this);
        final String endpoint = Prefs.endpoint(this);
        final String model = Prefs.model(this);
        final String key = Prefs.apiKey(this);

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
                                    showCard("判定失败", err, "端点 " + endpoint, false);
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
                            showCard(DecisionSpec.headline(answers),
                                    DecisionSpec.renderAll(answers), meta, true);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCard("本地判定失败", String.valueOf(e.getMessage()),
                                    "本地模型未能加载，可在设置里看具体原因。", false);
                        }
                    });
                }
            }
        }, "jev-decide").start();
    }

    // ---- 卡片 ----

    private void showCard(String headline, String body, String meta, final boolean retryable) {
        stopProgress();
        removeCard();

        cardHeadline = headline;
        cardBody = body;

        cardView = LayoutInflater.from(this).inflate(R.layout.decision_card, null);
        ((TextView) cardView.findViewById(R.id.card_headline)).setText(headline);
        ((TextView) cardView.findViewById(R.id.card_body)).setText(body);
        ((TextView) cardView.findViewById(R.id.card_meta)).setText(meta);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 固定宽度；高度交给 AT_MOST 测量，卡片本身是 LinearLayout，能正确撑开。
        int cardWidth = (int) (280 * density);
        cardParams = new WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.TOP | Gravity.START;

        // 默认落在屏幕上方：聊天窗口的最新消息在底部，压住它最难受
        cardParams.x = (int) (Prefs.cardX(this) * screenW) - cardWidth / 2;
        cardParams.y = (int) (Prefs.cardY(this) * screenH);
        clampCardInside(cardWidth);

        View header = cardView.findViewById(R.id.card_header);
        header.setOnTouchListener(new CardDragListener(cardWidth));

        // 点卡片外面就收起，不用专门去够那个按钮
        cardView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    removeCard();
                    return true;
                }
                return false;
            }
        });

        cardView.findViewById(R.id.card_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeCard();
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
        }
    }

    private void clampCardInside(int cardWidth) {
        cardParams.x = Math.max(0, Math.min(screenW - cardWidth, cardParams.x));
        cardParams.y = Math.max(0, Math.min(screenH - (int) (120 * density), cardParams.y));
    }

    /** 按住卡片顶部那一条拖动，松手记住位置。 */
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
                        Prefs.setCardPos(OverlayService.this,
                                (cardParams.x + cardWidth / 2f) / screenW,
                                cardParams.y / (float) screenH);
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
            String text = cardHeadline + "\n" + cardBody;
            cm.setPrimaryClip(ClipData.newPlainText("jev", text));
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
