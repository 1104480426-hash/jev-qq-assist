![Jev 僚机](docs/banner.jpg)

# Jev 僚机

基于 Jev 的手机聊天决策辅助。它读取当前聊天窗口，给出结构化判断：对方是否在等回复、对方意图、情绪强度、说错话风险和建议策略。

它只提供判断，不生成回复，不自动发送消息。

[下载最新版 APK](https://github.com/1104480426-hash/jev-wingman/releases/latest) · [查看更新记录](https://github.com/1104480426-hash/jev-wingman/releases)

> English: Jev Wingman is an Android chat decision assistant. It reads the active chat window through Android Accessibility and returns typed decisions instead of generated replies. It never sends messages.

## 1.9.1 现在有什么

- 全新的运行控制台：首页先显示权限、悬浮球状态和最近判定，端点、模型和诊断收进折叠区。
- 新版 J 双翼图标和深蓝、青绿色视觉系统。
- 远端 Jev / 本地模型双模式切换；没有 API Key 时仍可使用本地模型。
- 最近判定会绑定实际抓取到的那段对话，避免旧结论配到新窗口。
- 权限行提供就地修复入口；服务未连接时不会误报“可使用”。
- 演示入口合并为一个选择器，保留私聊有情绪、私聊日常和群聊三种样例。
- 旋转页面或离开设置时，不会把进行中的判定错误地留成永久加载状态。

## 适合什么场景

Jev 僚机适合你想先判断局面、再自己决定怎么回复的时候。它会把一段聊天压缩成可扫描的判断和依据，结果显示在聊天窗口旁边的悬浮卡片中。

它不是聊天机器人，也不是 Jev 官方客户端：

- 不登录账号，不接管 QQ、微信或其他聊天 App。
- 不注入文字，不点击发送，不代替你回复。
- 不保存完整聊天历史；首页只展示当前抓取和最近一次匹配的结果。
- 本地模式不联网；远端模式会把最近几行对话发送到你配置的端点。

## 使用步骤

1. 从 Releases 下载 jev-assist-v1.9.1.apk。
2. 安装后打开应用，在首页开启“读取聊天内容”和“显示悬浮球”。
3. 选择“远端 Jev”或“本地模型”。远端模式在“高级设置”中填写端点、模型和 API Key；本地模式可留空。
4. 返回聊天窗口，点悬浮球获取判定。
5. 第一次使用建议打开“试用演示”，先用内置的三段虚构对话确认权限和显示效果。

API Key 不会回填到界面。保存时留空表示沿用已保存的值。

## 首页怎么读

- **需要设置**：至少一项权限缺失，点“完成设置”会带你到第一项缺失权限。
- **可使用**：权限已授予且无障碍服务已连接，可以启动悬浮球。
- **运行中**：悬浮球正在工作，点“停止悬浮球”即可关闭。
- **最近判定**：显示来源、时间、摘要和耗时；抓取内容放在有高度限制的预览中。
- **高级设置**：端点、模型、API Key 和上下文行数。
- **开发诊断**：现场抓取、测试判定、本地模型自检和原始输出。

## 支持情况

应用依赖 Android Accessibility 暴露的文字节点，不使用截图 OCR。

| 应用类型 | 状态 |
| --- | --- |
| QQ 私聊、群聊 | 已在真机验证 |
| 飞书、抖音等暴露文字的界面 | 通常可用 |
| 微信自绘聊天界面 | 通常读不到文字 |

如果某个界面是全自绘、没有把文字交给无障碍服务，应用无法从中获得可靠输入。

## 本地与远端

| 模式 | 数据是否离开手机 | 适合 |
| --- | --- | --- |
| 本地模型 | 否 | 离线使用、隐私优先 |
| 远端 Jev | 是，发送到你配置的端点 | 更看重远端判定质量 |

本地模型使用量化的 bge-small-zh-v1.5 句向量近似实现，不是 TypeSafe Jev 的权重，也不声称复现官方精度。

## 从源码构建

要求：Windows、Android SDK（build-tools 34、android-34）、JDK 17、Python 3。不需要 Gradle。

    git clone https://github.com/1104480426-hash/jev-wingman.git
    cd jev-wingman
    powershell -ExecutionPolicy Bypass -File fetch_deps.ps1
    powershell -ExecutionPolicy Bypass -File build.ps1 -Clean

安装到已连接的 Android 手机：

    powershell -ExecutionPolicy Bypass -File build.ps1 -Clean -Install

SDK 或 JDK 不在默认位置时，传入 -Sdk <path> 和 -Jdk <path>。

## 验证

    powershell -ExecutionPolicy Bypass -File tools/test.ps1
    powershell -ExecutionPolicy Bypass -File build.ps1 -Clean

真机验证记录见 [docs/qa-1.9.1.md](docs/qa-1.9.1.md)。行为分析见 [docs/jev-behavior-notes.md](docs/jev-behavior-notes.md)。

## 隐私与限制

无障碍服务只读取当前窗口文字，不执行点击、输入或发送。远端模式的隐私边界取决于你填写的服务端点，请先确认端点的日志和保留策略。

判定不是事实核验。上下文过短、发言人识别失败、图片消息或无障碍节点缺失都会降低质量。应用会在读到的行数不足时提示不可靠，但你仍应把结果当作参考。

## 许可证

本项目代码采用仓库中的 LICENSE。模型和第三方运行库遵循各自的许可证。
