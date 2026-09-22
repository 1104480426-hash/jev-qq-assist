package ai.jev.assist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 悬浮球常驻服务：点一下就拿当前聊天窗口的转录去问 Jev，把类型化判定摊在屏幕上。
 *
 * <p>它只读不写：不注入文本、不点发送。决策给你，动作你自己做。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "ai.jev.assist.action.START";
    public static final String ACTION_STOP = "ai.jev.assist.action.STOP";

    /** 悬浮球是否在运行，供设置界面显示状态。 */
    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "jev_assist_overlay";
    private static final int NOTIFICATION_ID = 4401;
    private static final int DRAG_SLOP_DP = 6;

    private WindowManager windowManager;
    private View ballView;
    private View cardView;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cardParams;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int dragSlopPx;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        dragSlopPx = (int) (DRAG_SLOP_DP * getResources().getDisplayMetrics().density);
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
        removeCard();
        if (ballView != null && windowManager != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Exception ignored) {
                // 已移除
            }
            ballView = null;
        }
        super.onDestroy();
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

    private void showBall() {
        ballView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 尺寸在这里定死：inflate(res, null) 会丢掉 XML 里的 layout_width/height，
        // 只靠 wrap_content 会让球被挤成几十像素的一条。
        float density = getResources().getDisplayMetrics().density;
        int ballSize = (int) (52 * density);

        ballParams = new WindowManager.LayoutParams(
                ballSize,
                ballSize,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = (int) (12 * getResources().getDisplayMetrics().density);
        ballParams.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);

        ballView.setOnTouchListener(new BallTouchListener());

        try {
            windowManager.addView(ballView, ballParams);
        } catch (Exception e) {
            // 悬浮窗权限被拒时会走到这里
            stopSelf();
        }
    }

    /** 拖动悬浮球，位移小于阈值则算点击。 */
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
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - touchX);
                    int dy = (int) (event.getRawY() - touchY);
                    if (Math.abs(dx) > dragSlopPx || Math.abs(dy) > dragSlopPx) {
                        moved = true;
                    }
                    ballParams.x = startX + dx;
                    ballParams.y = startY + dy;
                    try {
                        windowManager.updateViewLayout(ballView, ballParams);
                    } catch (Exception ignored) {
                        // 视图已移除
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        onBallTap();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

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
        showCard("正在判定…", hint, "", false);
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

    private void showCard(String headline, String body, String meta, final boolean retryable) {
        removeCard();

        cardView = LayoutInflater.from(this).inflate(R.layout.decision_card, null);
        ((TextView) cardView.findViewById(R.id.card_headline)).setText(headline);
        ((TextView) cardView.findViewById(R.id.card_body)).setText(body);
        ((TextView) cardView.findViewById(R.id.card_meta)).setText(meta);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 同理固定宽度；高度交给 AT_MOST 测量，卡片本身是 LinearLayout，能正确撑开。
        int cardWidth = (int) (300 * getResources().getDisplayMetrics().density);
        cardParams = new WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        cardParams.gravity = Gravity.CENTER;

        cardView.findViewById(R.id.card_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeCard();
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

    private void removeCard() {
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
