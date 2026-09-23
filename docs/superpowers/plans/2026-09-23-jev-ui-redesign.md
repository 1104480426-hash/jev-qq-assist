# Jev 僚机主界面重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有开发调试面板改造成以状态、权限、悬浮球启动和最近判定为中心的日常运行控制台，同时保留现有判定、抓取、本地模型和演示行为。

**Architecture:** 继续使用当前 Gradle-free 的 Android 原生 View/XML 架构，不引入第三方 UI 框架或图表库。用一张可滚动的卡片式首页承载四个区域；`MainActivity` 只负责把现有 Prefs、权限、服务和抓取缓存映射到页面状态，判定与服务逻辑保持原样。高级设置和开发诊断放入可恢复的折叠区，最近判定先使用当前会话结果与抓取快照，不新增聊天历史数据库。

**Tech Stack:** Android API 26–34、Java 8、XML Views、aapt2/javac/d8、现有 `build.ps1` 和 PowerShell/ADB 验证脚本。

**Spec:** `docs/superpowers/specs/2026-09-23-jev-ui-redesign-design.md`

## Global Constraints

- 本轮只重构 Android 原生主界面及其交互层级，不改变无障碍抓取、远端请求、本地模型和悬浮球行为。
- 页面状态由现有 `Prefs`、权限检查、`OverlayService.running`、最近抓取快照和最近判定结果组合得到，不增加第二套真相来源。
- API Key 继续遵守现有策略：空输入不覆盖已保存密钥。
- 本轮不增加图表、账号系统、云端历史同步或完整聊天记录持久化。
- 所有可点击区域保持适合触摸的高度；每个输入框都有可见标签；状态同时使用文字和状态词/图形表达。
- 现有模型、抓取、远端请求和悬浮球回归测试必须继续通过。

## Review Focus

- 权限缺失时主按钮必须显示“完成设置”，并定位到第一个缺失权限；由 Task 3 的权限状态测试和真机步骤覆盖。
- 无障碍服务未连接、悬浮窗权限已给、反向组合以及两项都已给都必须显示正确的逐行状态；由 Task 3 的 `refreshRuntimeCard()` 分支检查覆盖。
- 远端/本地切换不能改写端点、模型或 API Key，空 API Key 保存不能清空旧值；由 Task 3 的配置回归步骤覆盖。
- 最近抓取为空、短文本和超过 240 字的长文本必须分别显示空态、统计和受限预览；由 Task 4 的最近判定卡渲染检查覆盖。
- 折叠区、键盘和旋转恢复不能丢失用户正在编辑的字段或滚动位置；由 Task 2 的布局检查和 Task 5 的真机验收覆盖。

### Task 1: 建立品牌视觉与文案资源

**Files:**
- Modify: `app/res/values/styles.xml`
- Modify: `app/res/drawable/bg_card.xml`
- Modify: `app/res/drawable/bg_btn_primary.xml`
- Modify: `app/res/drawable/bg_btn_ghost.xml`
- Create: `app/res/drawable/bg_status_ready.xml`
- Create: `app/res/drawable/bg_status_warning.xml`
- Create: `app/res/drawable/bg_segment_selected.xml`
- Create: `app/res/drawable/bg_segment_unselected.xml`
- Modify: `app/res/values/strings.xml`

**Interfaces:**
- Produces drawable/style names consumed by `activity_main.xml`.
- Produces string resources for header, permission rows, runtime actions, empty state, advanced settings and diagnostics; existing manifest/service strings remain unchanged.

- [ ] **Step 1: Add the resource contract before layout edits**

  Add these string keys with the exact user-facing copy, keeping API/diagnostic details available in the folded area: `home_title`, `home_subtitle`, `status_ready`, `status_setup`, `status_running`, `runtime_stopped`, `runtime_running`, `action_start`, `action_stop`, `action_finish_setup`, `permission_a11y_title`, `permission_a11y_desc`, `permission_overlay_title`, `permission_overlay_desc`, `permission_on`, `permission_off`, `action_open_settings`, `mode_remote`, `mode_local`, `recent_title`, `recent_empty`, `recent_capture`, `more_title`, `demo_entry_title`, `demo_entry_desc`, `advanced_title`, `diagnostics_title`, and the short success/error messages used by the existing callbacks.

- [ ] **Step 2: Define the visual tokens and state backgrounds**

  Set `AppTheme` background/status/navigation colors to the icon’s deep navy header treatment plus cool gray page background, retain light-system-bar flags, and define stable text colors for primary, secondary, success, warning and error states. Keep the existing theme parent and API floors. Make `bg_card` a light card with one consistent corner radius, `bg_btn_primary` a deep-navy/teal action surface, `bg_btn_ghost` a light outlined surface, and add the three state/segmented-selector drawables using shape XML only.

- [ ] **Step 3: Run resource compilation**

  Run `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Clean` from the repository root. Expected: aapt2 compile/link completes; Java may still compile against the old layout because IDs are not removed in Task 1.

### Task 2: Replace the flat XML with the four-area console

**Files:**
- Modify: `app/res/layout/activity_main.xml`
- Create: `app/res/layout/home_permission_row.xml` only if repeated permission markup cannot remain readable inline.

**Interfaces:**
- Produces the IDs consumed by `MainActivity`: `status_badge`, `runtime_title`, `runtime_action`, `permission_a11y_status`, `permission_a11y_action`, `permission_overlay_status`, `permission_overlay_action`, `mode_remote`, `mode_local`, `recent_source`, `recent_time`, `recent_summary`, `recent_meta`, `recent_empty`, `recent_capture_toggle`, `recent_capture_content`, `demo_entry`, `advanced_toggle`, `advanced_content`, `diagnostics_toggle`, `diagnostics_content`, plus the preserved configuration IDs `endpoint`, `model`, `apikey`, `lines`, `save`, `test`, `demo`, `demo_calm`, `demo_group`, `snapshot`, `localcheck`, `accessibility`, `output`.
- Consumes the drawables and strings from Task 1.

- [ ] **Step 1: Build the scrollable page skeleton**

  Keep a root `ScrollView` with `fillViewport=true`, a cool-gray background, safe 20dp horizontal padding and a vertical content container. Add a compact brand header with the existing launcher source visual represented by the app icon drawable if available, title/subtitle, and a right-aligned status badge.

- [ ] **Step 2: Build the runtime control card**

  Add a white card containing the runtime title, one full-width primary action button, and two permission rows. Each row has a visible title, one-line explanation, text status, and a concrete settings button. Add a two-option horizontal mode selector whose selected state is expressed by both background and text; keep both options at least 48dp high.

- [ ] **Step 3: Build the recent decision and more-actions cards**

  Add the recent decision card with source/time/summary/meta fields, an empty-state TextView, and a secondary “查看抓取内容” action that reveals a bounded scrollable preview. Add the demo entry as one row, then collapsed advanced and diagnostics rows with their content containers initially hidden. Put all existing endpoint/model/key/lines/save controls in advanced content and existing test/snapshot/local-check/output controls in diagnostics content. Keep the three demo buttons inside the demo flow but expose only one home entry.

- [ ] **Step 4: Check XML-level accessibility and small-screen behavior**

  Give every EditText a visible label, every action button a specific text label/content description, use `textAllCaps=false`, avoid icon-only controls, set minimum touch heights to 48dp, set long text to wrap, and ensure the recent preview has a maximum height with its own scrolling behavior.

- [ ] **Step 5: Compile after the XML replacement**

  Run `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Clean`. Expected: aapt2 and javac fail only if a referenced ID is missing; fix all resource/ID errors before moving to Java state wiring.

### Task 3: Wire MainActivity to the new stateful controls

**Files:**
- Modify: `app/src/ai/jev/assist/MainActivity.java`

**Interfaces:**
- Consumes Task 1 strings/drawables and Task 2 IDs.
- Produces `refreshUi()`, `refreshRuntimeCard()`, `refreshRecentCard()`, `refreshModeSelector()`, `showInlineMessage(String, boolean)`, and click handlers that preserve the existing `save()`, `runTest()`, `runLocalCheck()`, `showSnapshot()`, `openAccessibilitySettings()`, and `openDemo(String)` behavior.

- [ ] **Step 1: Add view fields and restoreable UI state**

  Add fields for the new TextViews/Buttons/containers, plus `advancedExpanded`, `diagnosticsExpanded`, `captureExpanded`, and in-memory last-decision fields (headline, meta, source, timestamp). In `onSaveInstanceState`, save expansion flags, scroll position, and the latest decision display strings; restore them before the first `refreshUi()`. Do not replace Prefs or service state with a second persistence layer.

- [ ] **Step 2: Centralize listeners in onCreate**

  Keep existing field initialization for endpoint/model/key/lines and attach: runtime action to save/check permissions/open the first missing settings page or send the existing start/stop service intent; each permission row to its matching system settings; remote/local selectors to `Prefs.setMode()` only; demo entry to a small choice dialog that calls `openDemo("private"|"calm"|"group")`; fold toggles to visibility and a clear expanded/collapsed label; recent capture toggle to reveal the bounded preview and call `showSnapshot()` only when requested; advanced save/test and diagnostics actions to current methods and a short inline status message.

- [ ] **Step 3: Replace the monolithic status text with focused refresh methods**

  Implement `refreshRuntimeCard()` from `canOverlay()`, `isAccessibilityOn()`, `ChatAccessibilityService.isConnected()`, `OverlayService.running`, and `Prefs.isRemoteDegraded()`. Display setup when either required permission is missing, ready when both permissions are present and stopped, and running when `OverlayService.running` is true. The primary action must read “完成设置”, “启动悬浮球”, or “停止悬浮球” and scroll/focus the first missing permission instead of starting the service.

- [ ] **Step 4: Render the recent decision card safely**

  Use the pinned capture first, then cached capture, preserving current source/stats/relative-time logic. Show the empty message when no capture exists, show source/time/stats when present, and cap the preview to 240 characters in the card. Store only the current result headline/meta in instance state; do not write chat text, API key, or a history list to disk.

- [ ] **Step 5: Keep existing operations and error copy intact**

  Route save/test/demo/snapshot/local-check/accessibility/toggle operations through the current implementations. Change only where their result is rendered: diagnostics output must remain available, and failure messages must include a next action such as opening advanced settings or enabling a missing permission. Preserve the empty API-key behavior in `save()`.

- [ ] **Step 6: Compile and run the existing regression suite**

  Run `powershell -ExecutionPolicy Bypass -File .\\tools\\test.ps1`, then `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Clean`. Expected: both regression classes pass and the APK is produced at `build/jev-assist.apk`.

### Task 4: Collapse the old demo wall into one usable entry

**Files:**
- Modify: `app/src/ai/jev/assist/MainActivity.java`
- Modify: `app/res/layout/activity_demo.xml` only if the choice dialog needs labels shared with the demo screen.
- Modify: `app/res/values/strings.xml`

**Interfaces:**
- Consumes `demo_entry` from Task 2.
- Produces a three-choice entry that preserves the current `DemoActivity.EXTRA_PAGE` values `private`, `calm`, and `group`.

- [ ] **Step 1: Add the demo choice dialog**

  Use an `AlertDialog` with three text choices (“私聊（有情绪）”, “私聊（日常）”, “群聊”), each mapped to the existing page value. The dialog must be dismissible and must not alter Prefs or service state.

- [ ] **Step 2: Verify the demo pages remain unchanged**

  Open each choice from the new home entry and verify the same DemoActivity content and actions as before. If a shared string is needed, add it to `strings.xml` without changing page behavior.

- [ ] **Step 3: Build once after dialog integration**

  Run `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Clean`. Expected: successful APK build and no resource ID regressions.

### Task 5: Verify the rebuilt console on the phone

**Files:**
- Modify: none; verification only.
- Artifact: `build/jev-assist.apk`; optional notes in `docs/superpowers/verification/2026-09-23-jev-ui-redesign.md`.

**Interfaces:**
- Consumes the APK from Task 3/4 and the already connected ADB device.
- Produces a repeatable smoke-test record covering setup, ready, running, error and empty states.

- [ ] **Step 1: Install the rebuilt APK**

  Run `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Install`; verify ADB reports `Success`, then launch `ai.jev.assist`.

- [ ] **Step 2: Verify first-open/setup state**

  With permissions disabled, confirm the badge says “需要设置”, the runtime card points to the first missing permission, each row’s action opens the correct system settings, and the primary action does not start the service.

- [ ] **Step 3: Verify ready/running and mode states**

  Enable the existing accessibility and overlay permissions, return to the app, confirm “启动悬浮球”, start it, confirm “运行中/停止悬浮球”, and switch remote/local twice. Verify endpoint/model/key fields remain unchanged and the selected segment follows `Prefs.isLocal()`.

- [ ] **Step 4: Verify recent capture, diagnostics and demo**

  Use the existing capture/test/local-check actions from the folded diagnostics section, confirm empty and non-empty recent-card states, inspect the bounded text preview, open all three demo choices, and verify failure messages tell the user what to do next.

- [ ] **Step 5: Verify rotation, keyboard and system theme**

  Focus endpoint/API Key, open the keyboard, scroll to save, rotate or recreate the activity, and confirm text/expansion/scroll position survive. Repeat in light and dark system themes and check status-bar contrast, safe area and touch targets.

- [ ] **Step 6: Run final regression checks**

  Run `powershell -ExecutionPolicy Bypass -File .\\tools\\test.ps1` and `powershell -ExecutionPolicy Bypass -File .\\build.ps1 -Clean -Install`. Record the APK path and any device limitation (for example, a locked screen or unavailable permission settings) in the verification note.

## Self-review

- Spec coverage: all four page areas, state transitions, folded settings/diagnostics, demo consolidation, visual system, accessibility, no-history chart boundary, and phone verification are assigned to Tasks 1–5.
- Placeholder scan: no TODO/TBD or undefined follow-up work is used; every task names concrete files, IDs, commands, and expected outcomes.
- Interface consistency: Task 2 defines every new ID consumed by Task 3; Task 4 uses the exact existing `DemoActivity.EXTRA_PAGE` values; Task 3 preserves the existing operation method names and Prefs semantics.
- Review focus coverage: the five failure modes are pinned to Task 2/3/5 checks, including permission combinations, API-key preservation, long capture text, and configuration restoration.
