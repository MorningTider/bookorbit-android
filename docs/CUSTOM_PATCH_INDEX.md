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
