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
 */
public class DemoActivity extends Activity {

    /** 一对一的虚构对话，没有昵称行，靠气泡左右位置区分说话人。 */
    private static final String PAGE_PRIVATE = "file:///android_asset/demo-chat.html";
    /** 群聊的虚构对话，每条消息上方带昵称。 */
    private static final String PAGE_GROUP = "file:///android_asset/demo-chat-group.html";

    /** 置为 true 时打开群聊示例。 */
    public static final String EXTRA_GROUP = "ai.jev.assist.extra.GROUP";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        boolean group = getIntent().getBooleanExtra(EXTRA_GROUP, false);
        setTitle(group ? "演示模式 · 群聊" : "演示模式 · 私聊");

        WebView web = (WebView) findViewById(R.id.demo_web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        // 无障碍节点树交给读屏服务，演示时悬浮球读到的就是这一页
        web.setImportantForAccessibility(WebView.IMPORTANT_FOR_ACCESSIBILITY_YES);

        web.loadUrl(group ? PAGE_GROUP : PAGE_PRIVATE);
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
