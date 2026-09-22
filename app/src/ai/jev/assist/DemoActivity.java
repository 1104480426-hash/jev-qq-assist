package ai.jev.assist;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 演示模式：加载内置的虚构对话页面。
 *
 * <p>存在的意义有两个。一是让人不用真去翻聊天记录就能把整条链路试一遍；
 * 二是截图和录屏时有一个内容完全可控的背景，不必拿真实聊天当素材。
 * 页面里的对话是写死的假数据，不来自任何真实会话。
 *
 * <p>三份页面覆盖三种会给出不同结论的对话：有情绪的一对一（判红）、日常的一对一
 * （判绿）、带署名的群聊。要验证球上的颜色分档有没有做对，对着这三份各点一遍就清楚了。
 */
public class DemoActivity extends Activity {

    /** 选哪份演示页：默认 "private"（有情绪），另有 "calm"（日常）和 "group"（群聊）。 */
    public static final String EXTRA_PAGE = "ai.jev.assist.extra.PAGE";

    private static final String PAGE_PRIVATE = "file:///android_asset/demo-chat.html";
    private static final String PAGE_CALM = "file:///android_asset/demo-chat-calm.html";
    private static final String PAGE_GROUP = "file:///android_asset/demo-chat-group.html";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        String page = getIntent().getStringExtra(EXTRA_PAGE);
        String url;
        String title;
        if ("group".equals(page)) {
            url = PAGE_GROUP;
            title = "演示模式 · 群聊";
        } else if ("calm".equals(page)) {
            url = PAGE_CALM;
            title = "演示模式 · 私聊（日常）";
        } else {
            url = PAGE_PRIVATE;
            title = "演示模式 · 私聊（有情绪）";
        }
        setTitle(title);

        WebView web = (WebView) findViewById(R.id.demo_web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        // 无障碍节点树交给读屏服务，演示时悬浮球读到的就是这一页
        web.setImportantForAccessibility(WebView.IMPORTANT_FOR_ACCESSIBILITY_YES);

        web.loadUrl(url);
    }

    @Override
    protected void onDestroy() {
        WebView web = (WebView) findViewById(R.id.demo_web);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
