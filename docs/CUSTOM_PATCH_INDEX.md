# Android 定制补丁索引

## BA-C01 和 BA-C07

`app/src/main/res/values-zh-rCN` 保留旧包中文并补齐新键。`foliate/grouping-compat.js` 及 EPUB 调用适配旧 WebView，回归入口 `node --test compat-tests/*.test.mjs`。`app/build.gradle.kts` 使用递增版本码和 `.compat` 标识，与旧包签名配合验收升级。

## BA-C02

`ReaderFont.kt`、`ReaderFontRepository.kt`、`ApiService.kt` 负责目录、账号隔离和流式下载。`ReaderWebView.kt` 的本地字体路径处理器与 `reader-styles.js` 负责字体进入阅读正文。升级时核对后端字体 DTO、端点、CSS 家族名前缀和 WebView 字体 CORS。

## BA-C03 至 BA-C05

`ReaderSettings.kt`、`ReaderSettingsSheet.kt`、`ReaderScreen.kt` 与 `bridge.js` 维护排版、工具栏和亮度。添加字段保留默认值，以读取旧版 DataStore 设置。排版 CSS 测试见 `compat-tests/reader-styles.test.mjs`。

## BA-C06

`ReaderProgressRepository.kt` 负责离开阅读页后的保存与并发排序。`ReadingSessionRecorder.kt` 和 `ReadingSessionTracker.kt` 维护日志检查点、空闲计时边界和重试。服务器接收重复 sessionId 时更新同一日志，不能改成每次重试生成新 ID。已有 BookOrbit 服务端支持 `source=android`，本轮不修改服务端数据库。

定制提交应按 `git log --oneline custom/bookorbit-android-v0.7.1` 查看，验收快照在 `VALIDATION.md`。

## BA-C11 至 BA-C16

- `ReaderAppearance.kt`、`ReaderChrome.kt`、`ReaderSettingsSheet.kt` 和 `ReaderTocSheet.kt` 维护主题、紧凑面板及底部导航。
- `ReaderPageCurl.kt` 在非输入时段缓存纯 WebView 正文，使用单张限尺寸快照、复用网格/路径/画笔和 ValueAnimator。`ReaderWebView.kt` 接入横向手势、音量键、首尾边界和释放。保留长按选字。
- `reader-navigation.js` 维护动画、自动阅读和分页边距。Foliate `gap` 必须传百分比，旋转后重算。
- `ReaderBridge.kt`、`bridge.js`、`ReaderScreen.kt` 接入当前章 href、首尾状态、自动停止、原生自动卷页与引擎导航完成事件。
- 新字段保留默认值以兼容旧 JSON，回归见 `ReaderBridgeEventsTest`。
- `ReaderInteractionTest` 检查设置和目录，`ReaderEngineTest` 检查自制 EPUB、隔离字体下载、可见绘制、章节、按键、滑动、自动阅读和帧耗时。

`ReaderThemes.kt` 与 `bridge.js` 的原生护眼绿同步维护，网页配色在服务端仓库维护。

## BA-C17 和 BA-C18

- `ReaderViewModel.load` 在公开待打开文件之前装载已选字体缓存，`OpenParams` 将字体与排版同时交给桥接。后台目录刷新保留当前已缓存的字体。
- `reader-loading.js` 等待 FontFaceSet 和布局稳定，`bridge.js` 在字体度量稳定后再次恢复 CFI。宿主等待 Chromium 可见状态和 Android 帧提交后报告完成。Android 26–28 及软件渲染使用兼容帧回调。
- `ReaderWebView` 合并重复字体和排版命令，仿真模式中提前截住小幅移动，避免 Foliate 先推移页面。
- `pageTurnCompleted` 必须在 `view.next/prev` 的 Promise 完成后发送。页码变化发生时引擎可能仍锁定，不能据此提前接收下一次导航。
- `ReaderPanel` 共用支持系统边距的底部面板。窄屏和大字号回归需检查底部导航实际可见。
- `ReaderEngineTest` 在旧版复现首次字体未就绪，并在新版检查首次打开、重新进入、非零进度恢复、加载完成前不提交临时位置、正文可见绘制、卷页、覆盖翻页、小幅拖动与帧时间。`ReaderInteractionTest` 检查背景及各内容页签。
