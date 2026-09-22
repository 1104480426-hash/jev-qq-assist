package ai.jev.assist;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

/** 设置与自检界面。 */
public class MainActivity extends Activity {

    private EditText endpointBox;
    private EditText modelBox;
    private EditText keyBox;
    private EditText linesBox;
    private TextView statusView;
    private TextView outputView;
    private Button toggleButton;
    private Button modeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        endpointBox = (EditText) findViewById(R.id.endpoint);
        modelBox = (EditText) findViewById(R.id.model);
        keyBox = (EditText) findViewById(R.id.apikey);
        linesBox = (EditText) findViewById(R.id.lines);
        statusView = (TextView) findViewById(R.id.status);
        outputView = (TextView) findViewById(R.id.output);
        toggleButton = (Button) findViewById(R.id.toggle);

        endpointBox.setText(Prefs.endpoint(this));
        modelBox.setText(Prefs.model(this));
        // 不回填密钥明文：这张界面很容易被截图或录屏，密钥不该出现在上面。
        // 留空表示保持不变，输入新值才覆盖。
        keyBox.setText("");
        keyBox.setHint(Prefs.apiKey(this).length() > 0
                ? "已保存（留空不变，输入新值可覆盖）"
                : "可留空");
        linesBox.setText(String.valueOf(Prefs.contextLines(this)));

        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                refreshStatus();
                outputView.setText("已保存。");
            }
        });

        findViewById(R.id.test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                runTest();
            }
        });

        findViewById(R.id.demo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DemoActivity.class));
            }
        });

        findViewById(R.id.demo_group).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, DemoActivity.class);
                intent.putExtra(DemoActivity.EXTRA_GROUP, true);
                startActivity(intent);
            }
        });

        findViewById(R.id.snapshot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSnapshot();
            }
        });

        modeButton = (Button) findViewById(R.id.mode);
        modeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setMode(MainActivity.this,
                        Prefs.isLocal(MainActivity.this) ? Prefs.MODE_REMOTE : Prefs.MODE_LOCAL);
                refreshStatus();
            }
        });

        findViewById(R.id.localcheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runLocalCheck();
            }
        });

        findViewById(R.id.accessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                if (OverlayService.running) {
                    startService(new Intent(MainActivity.this, OverlayService.class)
                            .setAction(OverlayService.ACTION_STOP));
                } else {
                    if (!canOverlay()) {
                        requestOverlay();
                        return;
                    }
                    Intent intent = new Intent(MainActivity.this, OverlayService.class);
                    intent.setAction(OverlayService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                }
                statusView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                }, 400);
            }
        });

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void save() {
        Prefs.setEndpoint(this, endpointBox.getText().toString());
        Prefs.setModel(this, modelBox.getText().toString());
        // 只有真正输入了新密钥才写入，空着就是"别动它"
        String typedKey = keyBox.getText().toString().trim();
        if (typedKey.length() > 0) {
            Prefs.setApiKey(this, typedKey);
            keyBox.setText("");
            keyBox.setHint("已保存（留空不变，输入新值可覆盖）");
        }
        String lines = linesBox.getText().toString().trim();
        if (!TextUtils.isEmpty(lines)) {
            try {
                Prefs.setContextLines(this, Integer.parseInt(lines));
            } catch (NumberFormatException ignored) {
                // 保持原值
            }
        }
        linesBox.setText(String.valueOf(Prefs.contextLines(this)));
    }

    private void refreshStatus() {
        boolean overlay = canOverlay();
        boolean a11y = isAccessibilityOn();
        StringBuilder sb = new StringBuilder();
        sb.append("悬浮窗权限：").append(overlay ? "已授予" : "未授予");
        sb.append('\n').append("读屏无障碍：").append(a11y ? "已开启" : "未开启");
        sb.append('\n').append("按需抓取：")
                .append(ChatAccessibilityService.isConnected() ? "可用" : "不可用（服务未连上）");
        sb.append('\n').append("悬浮球：").append(OverlayService.running ? "运行中" : "未运行");
        if (Prefs.isRemoteDegraded(this)) {
            sb.append('\n').append("判定模式：远端未填 key，已暂用本地模型")
                    .append('\n').append("            填上 API Key 后自动切回远端");
        } else {
            sb.append('\n').append("判定模式：").append(
                    Prefs.isLocal(this) ? "本地（模型在手机里，不联网）" : "远端 Jev 兼容端点");
        }

        String loadErr = LocalJudge.loadError();
        if (loadErr != null) {
            sb.append('\n').append("本地模型：加载失败 · ").append(loadErr);
        } else if (LocalJudge.peek() != null) {
            sb.append('\n').append("本地模型：已就绪");
        } else {
            sb.append('\n').append("本地模型：未加载（首次判定时自动加载）");
        }

        // 优先展示点球那一次读了什么。被动缓存会被路上经过的窗口改写——用户从聊天窗口
        // 切回这里，中途经过桌面，缓存就变成应用图标名了，那不是他要看的东西。
        String pinned = ChatAccessibilityService.pinnedTranscript();
        boolean fromBall = pinned.length() > 0;
        String captured = fromBall ? pinned : ChatAccessibilityService.cachedTranscript();
        String src = fromBall
                ? ChatAccessibilityService.pinnedSourceName()
                : ChatAccessibilityService.captureSourceName();
        String stats = fromBall
                ? ChatAccessibilityService.pinnedStats()
                : ChatAccessibilityService.lastStats();

        if (captured.length() > 0) {
            sb.append('\n').append(fromBall ? "上次判定读到：" : "最近读自：");
            if (src.length() > 0) {
                sb.append(src).append(" · ");
            }
            sb.append(captured.length()).append(" 字");
            if (fromBall) {
                String ago = ago(ChatAccessibilityService.pinnedAt());
                if (ago.length() > 0) {
                    sb.append(" · ").append(ago);
                }
            }
        } else {
            sb.append('\n').append("上次判定：暂无（去聊天窗口点一下悬浮球）");
        }

        // 把实际抓到的文字摊出来。判定不对时，先看这里：是读错了窗口，
        // 还是说话人认反了，一眼能分清，不用去猜。
        if (captured.length() > 0) {
            if (stats.length() > 0) {
                sb.append('\n').append(stats);
            }
            String preview = captured.length() > 240 ? captured.substring(0, 240) + " …" : captured;
            sb.append("\n\n—— 实际读到 ——\n").append(preview);
        }

        statusView.setText(sb.toString());
        toggleButton.setText(OverlayService.running ? "停止悬浮球" : "启动悬浮球");
        modeButton.setText("切换判定模式（当前："
                + (Prefs.isLocal(this) ? "本地" : "远端") + "）");
    }

    /** 把时间戳讲成人话，让"上次判定读到"带上新鲜度。 */
    private static String ago(long at) {
        if (at <= 0L) {
            return "";
        }
        long sec = (System.currentTimeMillis() - at) / 1000L;
        if (sec < 60) {
            return "刚刚";
        }
        if (sec < 3600) {
            return (sec / 60) + " 分钟前";
        }
        if (sec < 86400) {
            return (sec / 3600) + " 小时前";
        }
        return (sec / 86400) + " 天前";
    }

    /**
     * 现场抓一次当前窗口并原样打印。
     *
     * <p>状态区里那份是缓存，可能停在别的窗口上（实测出现过停在桌面），看它容易误判。
     * 这个按钮走的是和点悬浮球完全相同的那条路径，显示什么，判定就基于什么。
     */
    private void showSnapshot() {
        String live = ChatAccessibilityService.captureNow();
        if (live == null) {
            outputView.setText("读屏服务未连上，先在系统设置里开启无障碍，再回来点这个按钮。");
            return;
        }
        if (live.length() == 0) {
            outputView.setText("当前窗口没有读到任何文字。\n\n"
                    + "如果是微信，这是正常的——它的界面全自绘，不向无障碍暴露内容。");
            return;
        }
        String preview = live.length() > 900 ? live.substring(0, 900) + "\n…（还有 "
                + (live.length() - 900) + " 字）" : live;
        String stats = ChatAccessibilityService.lastStats();
        outputView.setText("—— 当前窗口实际读到 " + live.length() + " 字 ——\n"
                + (stats.length() > 0 ? stats + "\n" : "")
                + "\n" + preview);
    }

    /** 加载本地模型并跑一次自检，全程在后台线程。 */
    private void runLocalCheck() {
        outputView.setText("正在加载本地模型（23MB，首次约需几秒）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long started = System.currentTimeMillis();
                try {
                    LocalJudge judge = LocalJudge.get(getApplicationContext());
                    final long loadMs = System.currentTimeMillis() - started;

                    LocalDecisionSpec.Question probe = LocalDecisionSpec.QUESTIONS[1];
                    String sample = "对方：你这两天怎么回事，消息也不回\n我：在忙\n对方：忙什么，比我还忙吗";
                    long t2 = System.currentTimeMillis();
                    final JSONObject answers = judge.judge(sample);
                    final long judgeMs = System.currentTimeMillis() - t2;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            outputView.setText("本地模型加载完成 · " + loadMs + " ms"
                                    + "\n本次判定耗时 " + judgeMs + " ms"
                                    + "\n\n" + DecisionSpec.headline(answers)
                                    + "\n\n" + DecisionSpec.renderAll(answers));
                            refreshStatus();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            outputView.setText("本地模型加载失败："
                                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                                    + "\n\n--- assets 诊断 ---\n" + diagnoseAssets());
                            refreshStatus();
                        }
                    });
                }
            }
        }, "jev-local-check").start();
    }

    /** 打印 assets 树并逐个试 open，用于定位资源找不到的真实原因。 */
    private String diagnoseAssets() {
        StringBuilder sb = new StringBuilder();
        String[] dirs = {"", "models", "models/bge-small-zh"};
        for (String dir : dirs) {
            try {
                String[] list = getAssets().list(dir);
                sb.append(dir.length() == 0 ? "/" : dir).append(" -> ")
                        .append(list == null ? "null" : java.util.Arrays.toString(list))
                        .append('\n');
            } catch (Exception e) {
                sb.append(dir).append(" -> list 失败 ").append(e).append('\n');
            }
        }
        String[] files = {
                "models/bge-small-zh/vocab.txt",
                "models/bge-small-zh/config.json",
                "models/bge-small-zh/model_quantized.onnx",
        };
        for (String path : files) {
            java.io.InputStream in = null;
            try {
                in = getAssets().open(path);
                sb.append("open ").append(path).append(" -> OK (first byte ")
                        .append(in.read()).append(")\n");
            } catch (Exception e) {
                sb.append("open ").append(path).append(" -> ")
                        .append(e.getClass().getSimpleName()).append('\n');
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
        return sb.toString();
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
                outputView.setText("请在系统里允许「显示在其他应用上层」，然后回来再点启动。");
            } catch (Exception e) {
                outputView.setText("跳转悬浮窗设置失败：" + e.getMessage());
            }
        }
    }

    private boolean isAccessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String me = new ComponentName(this, ChatAccessibilityService.class).flattenToString();
        String meShort = new ComponentName(this, ChatAccessibilityService.class).flattenToShortString();
        return enabled.contains(me) || enabled.contains(meShort);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            outputView.setText("在无障碍列表里找到「Jev 僚机 · 读取聊天内容」并打开。");
        } catch (Exception e) {
            outputView.setText("跳转无障碍设置失败：" + e.getMessage());
        }
    }

    private void runTest() {
        final String sample = ChatAccessibilityService.cachedTranscript().length() > 0
                ? ChatAccessibilityService.cachedTranscript()
                : "对方：你这两天怎么回事，消息也不回\n我：在忙\n对方：忙什么，比我还忙吗";
        outputView.setText("判定中…");
        final String endpoint = Prefs.endpoint(this);
        final String model = Prefs.model(this);
        final String key = Prefs.apiKey(this);
        final boolean local = Prefs.isLocal(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject answers;
                    final String meta;
                    if (local) {
                        LocalJudge judge = LocalJudge.get(getApplicationContext());
                        long t = System.currentTimeMillis();
                        answers = judge.judge(sample);
                        meta = "本地 bge-small-zh · " + (System.currentTimeMillis() - t) + " ms";
                    } else {
                        JevClient.Result result = JevClient.decide(endpoint, key,
                                DecisionSpec.build(model, sample));
                        if (!result.ok) {
                            final String err = result.error;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    outputView.setText("判定失败：" + err + "\n\n端点：" + endpoint);
                                }
                            });
                            return;
                        }
                        answers = result.answers;
                        meta = "远端 " + result.model + " · " + result.elapsedMs + " ms";
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            outputView.setText(DecisionSpec.headline(answers) + "\n\n"
                                    + DecisionSpec.renderAll(answers) + "\n\n" + meta);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            outputView.setText("判定异常：" + e.getClass().getSimpleName()
                                    + ": " + e.getMessage());
                        }
                    });
                }
            }
        }, "jev-test").start();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            } catch (Exception ignored) {
                // 拒绝也不影响判定
            }
        }
    }
}
