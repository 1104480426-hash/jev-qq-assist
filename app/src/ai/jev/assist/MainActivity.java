package ai.jev.assist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private ScrollView homeScroll;
    private LinearLayout a11yRow;
    private LinearLayout overlayRow;
    private LinearLayout recentContent;
    private LinearLayout advancedContent;
    private LinearLayout diagnosticsContent;
    private ScrollView captureContent;
    private TextView statusBadge;
    private TextView runtimeTitle;
    private TextView runtimeHint;
    private TextView a11yStatus;
    private TextView overlayStatus;
    private TextView recentEmpty;
    private TextView recentSummary;
    private TextView recentSource;
    private TextView recentTime;
    private TextView recentMeta;
    private TextView recentCaptureText;
    private Button runtimeAction;
    private Button a11yAction;
    private Button overlayAction;
    private Button modeRemoteButton;
    private Button modeLocalButton;
    private Button recentCaptureToggle;
    private Button demoEntry;
    private Button advancedToggle;
    private Button diagnosticsToggle;
    private boolean advancedExpanded;
    private boolean diagnosticsExpanded;
    private boolean captureExpanded;
    private int restoredScrollY;
    private String latestHeadline = "";
    private String latestMeta = "";
    private String latestSource = "";
    private String latestTranscript = "";
    private long latestAt;
    private boolean decisionInFlight;
    private boolean activityDestroyed;

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
        modeButton = (Button) findViewById(R.id.mode);
        homeScroll = (ScrollView) findViewById(R.id.home_scroll);
        runtimeAction = (Button) findViewById(R.id.runtime_action);
        statusBadge = (TextView) findViewById(R.id.status_badge);
        runtimeTitle = (TextView) findViewById(R.id.runtime_title);
        runtimeHint = (TextView) findViewById(R.id.runtime_hint);
        a11yRow = (LinearLayout) findViewById(R.id.permission_a11y_row);
        overlayRow = (LinearLayout) findViewById(R.id.permission_overlay_row);
        a11yStatus = (TextView) a11yRow.findViewById(R.id.permission_status);
        overlayStatus = (TextView) overlayRow.findViewById(R.id.permission_status);
        a11yAction = (Button) a11yRow.findViewById(R.id.permission_action);
        overlayAction = (Button) overlayRow.findViewById(R.id.permission_action);
        TextView a11yTitle = (TextView) a11yRow.findViewById(R.id.permission_title);
        TextView a11yDesc = (TextView) a11yRow.findViewById(R.id.permission_desc);
        TextView overlayTitle = (TextView) overlayRow.findViewById(R.id.permission_title);
        TextView overlayDesc = (TextView) overlayRow.findViewById(R.id.permission_desc);
        a11yTitle.setText("读取聊天内容");
        a11yDesc.setText("让 Jev 读取当前聊天窗口，点悬浮球时才会抓取。");
        overlayTitle.setText("显示悬浮球");
        overlayDesc.setText("允许悬浮球出现在 QQ 等聊天应用上方。");
        modeRemoteButton = (Button) findViewById(R.id.mode_remote);
        modeLocalButton = (Button) findViewById(R.id.mode_local);
        recentContent = (LinearLayout) findViewById(R.id.recent_content);
        recentEmpty = (TextView) findViewById(R.id.recent_empty);
        recentSummary = (TextView) findViewById(R.id.recent_summary);
        recentSource = (TextView) findViewById(R.id.recent_source);
        recentTime = (TextView) findViewById(R.id.recent_time);
        recentMeta = (TextView) findViewById(R.id.recent_meta);
        recentCaptureToggle = (Button) findViewById(R.id.recent_capture_toggle);
        captureContent = (ScrollView) findViewById(R.id.recent_capture_content);
        recentCaptureText = (TextView) findViewById(R.id.recent_capture_text);
        demoEntry = (Button) findViewById(R.id.demo_entry);
        advancedToggle = (Button) findViewById(R.id.advanced_toggle);
        advancedContent = (LinearLayout) findViewById(R.id.advanced_content);
        diagnosticsToggle = (Button) findViewById(R.id.diagnostics_toggle);
        diagnosticsContent = (LinearLayout) findViewById(R.id.diagnostics_content);

        if (savedInstanceState != null) {
            advancedExpanded = savedInstanceState.getBoolean("advancedExpanded", false);
            diagnosticsExpanded = savedInstanceState.getBoolean("diagnosticsExpanded", false);
            captureExpanded = savedInstanceState.getBoolean("captureExpanded", false);
            restoredScrollY = savedInstanceState.getInt("homeScrollY", 0);
            latestHeadline = savedInstanceState.getString("latestHeadline", "");
            latestMeta = savedInstanceState.getString("latestMeta", "");
            latestSource = savedInstanceState.getString("latestSource", "");
            latestTranscript = savedInstanceState.getString("latestTranscript", "");
            latestAt = savedInstanceState.getLong("latestAt", 0L);
            if (savedInstanceState.getBoolean("decisionInFlight", false)) {
                latestHeadline = "判定已取消";
                latestMeta = "页面重建后请重新判定";
                latestTranscript = "";
                decisionInFlight = false;
            }
        }

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
                showInlineMessage("已保存。", false);
                refreshUi();
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
                openDemo("private");
            }
        });

        findViewById(R.id.demo_calm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDemo("calm");
            }
        });

        findViewById(R.id.demo_group).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDemo("group");
            }
        });

        findViewById(R.id.snapshot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSnapshot();
            }
        });

        modeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode();
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
                toggleService();
            }
        });
        runtimeAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        a11yAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        overlayAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        modeRemoteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setMode(MainActivity.this, Prefs.MODE_REMOTE);
                refreshUi();
            }
        });
        modeLocalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setMode(MainActivity.this, Prefs.MODE_LOCAL);
                refreshUi();
            }
        });
        demoEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDemoChooser();
            }
        });
        advancedToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                advancedExpanded = !advancedExpanded;
                refreshFoldState();
            }
        });
        diagnosticsToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnosticsExpanded = !diagnosticsExpanded;
                refreshFoldState();
            }
        });
        recentCaptureToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!captureExpanded && currentCapturedTranscript().length() == 0) {
                    showSnapshot();
                }
                captureExpanded = !captureExpanded;
                if (captureExpanded) {
                    renderCapturePreview();
                }
                refreshFoldState();
            }
        });

        requestNotificationPermissionIfNeeded();
        refreshUi();
        homeScroll.post(new Runnable() {
            @Override
            public void run() {
                homeScroll.scrollTo(0, restoredScrollY);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("advancedExpanded", advancedExpanded);
        outState.putBoolean("diagnosticsExpanded", diagnosticsExpanded);
        outState.putBoolean("captureExpanded", captureExpanded);
        outState.putInt("homeScrollY", homeScroll.getScrollY());
        outState.putString("latestHeadline", latestHeadline);
        outState.putString("latestMeta", latestMeta);
        outState.putString("latestSource", latestSource);
        outState.putString("latestTranscript", latestTranscript);
        outState.putLong("latestAt", latestAt);
        outState.putBoolean("decisionInFlight", decisionInFlight);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        super.onDestroy();
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
        refreshUi();
    }

    private void refreshUi() {
        refreshRuntimeCard();
        refreshModeSelector();
        refreshRecentCard();
        refreshFoldState();
    }

    private void refreshRuntimeCard() {
        boolean overlay = canOverlay();
        boolean a11y = isAccessibilityOn();
        boolean connected = ChatAccessibilityService.isConnected();
        boolean running = OverlayService.running;
        boolean ready = a11y && overlay && connected;
        String state = HomeUiState.status(a11y, overlay, connected, running);
        statusBadge.setText(state);
        statusBadge.setBackgroundResource(running || ready
                ? R.drawable.bg_status_ready : R.drawable.bg_status_warning);
        statusBadge.setTextColor(running || ready
                ? Color.rgb(14, 128, 111) : Color.rgb(155, 92, 18));
        runtimeTitle.setText(running ? "悬浮球运行中" : "悬浮球已停止");
        if (!a11y) {
            runtimeHint.setText("先开启读取聊天内容，Jev 才能知道当前对话。");
        } else if (!overlay) {
            runtimeHint.setText("再允许显示悬浮球，就可以在聊天窗口使用。");
        } else if (running) {
            runtimeHint.setText("去聊天窗口点悬浮球，查看当前对话的决策建议。");
        } else if (!connected) {
            runtimeHint.setText("无障碍权限已开，但服务还未连接，请稍候或重新开启。");
        } else {
            runtimeHint.setText("权限已齐，可以随时启动悬浮球。");
        }
        runtimeAction.setText(HomeUiState.action(a11y, overlay, connected, running));
        a11yStatus.setText(a11y ? (connected ? "已开启" : "已开启 · 未连接") : "未开启");
        overlayStatus.setText(overlay ? "已开启" : "未开启");
        a11yStatus.setTextColor(a11y && connected ? Color.rgb(14, 128, 111) : Color.rgb(179, 90, 24));
        overlayStatus.setTextColor(overlay ? Color.rgb(14, 128, 111) : Color.rgb(179, 90, 24));
        a11yAction.setText(a11y && !connected ? "重新连接" : (a11y ? "已开启" : "去开启"));
        overlayAction.setText(overlay ? "已开启" : "去开启");
        a11yAction.setEnabled(!a11y || !connected);
        overlayAction.setEnabled(!overlay);
    }

    private void refreshModeSelector() {
        boolean local = Prefs.isLocal(this);
        modeRemoteButton.setBackgroundResource(local
                ? R.drawable.bg_segment_unselected : R.drawable.bg_segment_selected);
        modeLocalButton.setBackgroundResource(local
                ? R.drawable.bg_segment_selected : R.drawable.bg_segment_unselected);
        modeRemoteButton.setTextColor(local ? Color.rgb(113, 129, 152) : Color.WHITE);
        modeLocalButton.setTextColor(local ? Color.WHITE : Color.rgb(113, 129, 152));
        modeRemoteButton.setSelected(!local);
        modeLocalButton.setSelected(local);
        modeRemoteButton.setContentDescription("远端 Jev" + (local ? "，未选中" : "，已选中"));
        modeLocalButton.setContentDescription("本地模型" + (local ? "，已选中" : "，未选中"));
        modeButton.setText("切换判定模式（当前：" + (local ? "本地" : "远端") + "）");
    }

    private void refreshRecentCard() {
        String captured = currentCapturedTranscript();
        boolean hasCapture = captured.length() > 0;
        String displayHeadline = "";
        String displayMeta = "";
        long displayAt = currentCaptureAt();
        String serviceTranscript = OverlayService.lastDecisionTranscript();
        if (HomeUiState.resultMatchesCapture(serviceTranscript, captured)) {
            displayHeadline = OverlayService.lastDecisionHeadline();
            displayMeta = OverlayService.lastDecisionMeta();
            displayAt = OverlayService.lastDecisionAt();
        } else if (HomeUiState.resultMatchesCapture(latestTranscript, captured)) {
            displayHeadline = latestHeadline;
            displayMeta = latestMeta;
            displayAt = latestAt > 0L ? latestAt : displayAt;
        }
        recentEmpty.setVisibility(hasCapture ? View.GONE : View.VISIBLE);
        recentContent.setVisibility(hasCapture ? View.VISIBLE : View.GONE);
        if (hasCapture) {
            recentSummary.setText(displayHeadline.length() > 0 ? displayHeadline : "已抓取一段聊天内容");
            String source = currentCaptureSource();
            recentSource.setText(source.length() > 0 ? "来源 · " + source : "来源 · 当前聊天窗口");
            recentTime.setText(displayAt > 0L ? ago(displayAt) : captured.length() + " 字");
            String stats = currentCaptureStats();
            recentMeta.setText(displayMeta.length() > 0 ? displayMeta : stats);
        }
        recentCaptureToggle.setText(hasCapture ? "查看抓取内容" : "抓取当前窗口");
        recentCaptureToggle.setEnabled(true);
        if (captureExpanded) {
            renderCapturePreview();
        }
    }

    private void refreshFoldState() {
        advancedContent.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        diagnosticsContent.setVisibility(diagnosticsExpanded ? View.VISIBLE : View.GONE);
        captureContent.setVisibility(captureExpanded ? View.VISIBLE : View.GONE);
        advancedToggle.setText(advancedExpanded ? "高级设置 · 收起" : "高级设置");
        diagnosticsToggle.setText(diagnosticsExpanded ? "开发诊断 · 收起" : "开发诊断");
        recentCaptureToggle.setText(captureExpanded ? "收起抓取内容"
                : (currentCapturedTranscript().length() > 0 ? "查看抓取内容" : "抓取当前窗口"));
    }

    private void renderCapturePreview() {
        String captured = currentCapturedTranscript();
        String stats = currentCaptureStats();
        if (captured.length() == 0) {
            recentCaptureText.setText("当前还没有抓取内容。点“抓取当前窗口”可以现场检查。");
            return;
        }
        String preview = captured.length() > 900 ? captured.substring(0, 900)
                + "\n…（还有 " + (captured.length() - 900) + " 字）" : captured;
        recentCaptureText.setText((stats.length() > 0 ? stats + "\n\n" : "") + preview);
    }

    private String currentCapturedTranscript() {
        String pinned = ChatAccessibilityService.pinnedTranscript();
        return pinned.length() > 0 ? pinned : ChatAccessibilityService.cachedTranscript();
    }

    private String currentCaptureSource() {
        return ChatAccessibilityService.pinnedTranscript().length() > 0
                ? ChatAccessibilityService.pinnedSourceName()
                : ChatAccessibilityService.captureSourceName();
    }

    private String currentCaptureStats() {
        return ChatAccessibilityService.pinnedTranscript().length() > 0
                ? ChatAccessibilityService.pinnedStats() : ChatAccessibilityService.lastStats();
    }

    private long currentCaptureAt() {
        return ChatAccessibilityService.pinnedTranscript().length() > 0
                ? ChatAccessibilityService.pinnedAt() : 0L;
    }

    private void showInlineMessage(String message, boolean error) {
        outputView.setText(message);
        if (error) {
            diagnosticsExpanded = true;
            refreshFoldState();
        }
    }

    private void switchMode() {
        Prefs.setMode(this, Prefs.isLocal(this) ? Prefs.MODE_REMOTE : Prefs.MODE_LOCAL);
        refreshUi();
    }

    private void toggleService() {
        save();
        if (OverlayService.running) {
            showInlineMessage("正在停止悬浮球…", false);
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
            runtimeAction.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshUi();
                }
            }, 400);
            return;
        }
        if (!isAccessibilityOn()) {
            showInlineMessage("请先开启“读取聊天内容”权限。", true);
            focusMissingPermission(0);
            openAccessibilitySettings();
            return;
        }
        if (!canOverlay()) {
            showInlineMessage("请先开启“显示悬浮球”权限。", true);
            focusMissingPermission(1);
            requestOverlay();
            return;
        }
        if (!ChatAccessibilityService.isConnected()) {
            showInlineMessage("无障碍服务还未连接，请点“重新连接”后再启动。", true);
            openAccessibilitySettings();
            return;
        }
        showInlineMessage("正在启动悬浮球…", false);
        Intent intent = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        runtimeAction.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUi();
            }
        }, 400);
    }

    private void focusMissingPermission(final int which) {
        final View target = which == 0 ? a11yRow : overlayRow;
        target.post(new Runnable() {
            @Override
            public void run() {
                homeScroll.smoothScrollTo(0, target.getTop());
                target.requestFocus();
            }
        });
    }

    private void showDemoChooser() {
        final String[] pages = {"private", "calm", "group"};
        new AlertDialog.Builder(this)
                .setTitle("选择演示")
                .setItems(new String[]{"私聊（有情绪）", "私聊（日常）", "群聊"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                openDemo(pages[which]);
                            }
                        })
                .show();
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
            refreshRecentCard();
            return;
        }
        if (live.length() == 0) {
            outputView.setText("当前窗口没有读到任何文字。\n\n"
                    + "如果是微信，这是正常的——它的界面全自绘，不向无障碍暴露内容。");
            refreshRecentCard();
            return;
        }
        String preview = live.length() > 900 ? live.substring(0, 900) + "\n…（还有 "
                + (live.length() - 900) + " 字）" : live;
        String stats = ChatAccessibilityService.lastStats();
        outputView.setText("—— 当前窗口实际读到 " + live.length() + " 字 ——\n"
                + (stats.length() > 0 ? stats + "\n" : "")
                + "\n" + preview);
        refreshRecentCard();
        if (captureExpanded) {
            renderCapturePreview();
        }
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

    /** 打开演示页。三份页面分别对应有情绪的私聊、日常私聊、群聊。 */
    private void openDemo(String page) {
        Intent intent = new Intent(MainActivity.this, DemoActivity.class);
        intent.putExtra(DemoActivity.EXTRA_PAGE, page);
        startActivity(intent);
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
        latestHeadline = "正在判定…";
        latestMeta = "";
        latestSource = local ? "本地模型" : "远端 Jev";
        latestTranscript = sample;
        latestAt = System.currentTimeMillis();
        decisionInFlight = true;
        refreshRecentCard();

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
                                    if (activityDestroyed) {
                                        return;
                                    }
                                    decisionInFlight = false;
                                    latestHeadline = "判定失败";
                                    latestMeta = "请到高级设置检查端点和 API Key";
                                    latestTranscript = sample;
                                    latestAt = System.currentTimeMillis();
                                    outputView.setText("判定失败：" + err + "\n\n端点：" + endpoint
                                            + "\n\n请到高级设置检查端点和 API Key。");
                                    refreshRecentCard();
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
                            if (activityDestroyed) {
                                return;
                            }
                            decisionInFlight = false;
                            latestHeadline = DecisionSpec.headline(answers);
                            latestMeta = meta;
                            latestSource = local ? "本地模型" : "远端 Jev";
                            latestTranscript = sample;
                            latestAt = System.currentTimeMillis();
                            outputView.setText(latestHeadline + "\n\n"
                                    + DecisionSpec.renderAll(answers) + "\n\n" + meta);
                            refreshRecentCard();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activityDestroyed) {
                                return;
                            }
                            decisionInFlight = false;
                            latestHeadline = "判定异常";
                            latestMeta = "请到开发诊断查看错误详情";
                            latestTranscript = sample;
                            latestAt = System.currentTimeMillis();
                            outputView.setText("判定异常：" + e.getClass().getSimpleName()
                                    + ": " + e.getMessage()
                                    + "\n\n请到开发诊断查看错误详情。");
                            refreshRecentCard();
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
