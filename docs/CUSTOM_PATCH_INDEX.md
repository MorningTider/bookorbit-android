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

## BA-C19 至 BA-C23

- `foliate/paginator.js` 的章节 `View.load` 等待异步 `afterLoad`，`bridge.js` 设置 `prepareDocument`，`reader-loading.js` 等待该章节所选字重和独立 FontFaceSet。升级 Foliate 时必须保留此等待顺序。`font-display:block` 配合显示门控。
- `ReaderPageCurlHost` 维护拖动、完成和恢复状态，`ReaderNativeWebView` 接收触摸与速度。预览页位置在 `ReaderController` 内缓冲，取消通过 `restorePage` 恢复 CFI 后结束，提交才转交进度。不要用页码变化代替引擎导航完成。
- `ReaderProgressRepository` 串行本地检查点和远端合并，使用文本时间戳、保留回退位置，已确认记录按实际位置比较。`ReaderViewModel/ReaderScreen` 在恢复及活动轮询时检查当前屏幕位置，接续提示支持保留当前页并保存。
- `ReaderTocSheet` 的封面和书名通过 `ReaderDestination/AuthenticatedApp/MainShell` 回到实际书籍详情，使用 SavedStateHandle 传递目标书籍。
- `HomeShortcuts` 复用现有搜索、书库、下载、笔记与统计。`BookDetailScreen` 的紧凑概览和固定按钮保留原有文件格式选择逻辑。
- `BookReadingLog`、`ApiService`、`BookDetailRepository/ViewModel` 和 `ReadingLogPanel` 对齐服务端书籍进度及阅读会话端点。每页20条，保留失败提示和重试。
- `ReaderProgressSyncTest` 与 `ReaderSyncHttpTest` 覆盖跨端位置、离线、回退和实际HTTP序列化。`ReaderEngineTest` 验证章节字体及手持卷页，`BookExperienceTest` 验证首页与详情组件。

## BA-C24

`app_name`中英文均为BookOrbit，主资源 `ic_launcher_background` 为蓝色 `#2563EB`，复用白色轨道前景。移除 `app/src/debug` 中的名称清单和图标覆盖，两个发行变体均继承主资源。升级后复验编译标签、安装后的普通/圆形图标以及原签名和兼容应用标识。测试入口为 `AppBrandingTest`。

## BA-C25 至 BA-C28

- `feature/comments`、`core/model/BookComment`与`ApiService`维护公共评论和回复，包含保留草稿、幂等请求、游标、删除确认和旧服务端提示。对应服务端实现位于BookOrbit的`book-comment`模块和`0130_book-comments`迁移。
- `ReadingDisplay`统一进度精度，`BookDetailRepository/ViewModel`合并本机检查点时比较读取快照，避免依赖设备与服务器时钟一致。书架恢复先等待已排队的读取报告，按页批量查询Room，每批最多900个文件。
- `reader-pagination.js`复用同一Paginator配置与字体逐章测量，`ReaderBridge.Pages`传递真实页码。章节加载器的根引用与并发创建经过独立回归。
- `ReaderEngineTest`覆盖正文/顶部标题、整本页数、快速跨章后定位和字体就绪；`BookCommentsTest`覆盖公开书评、失败重试、回复和删除确认。

## BA-C29

`CompatibleApiService`在Hilt网络入口包装原Retrofit接口。`ServerCompatibility`声明3.7公共DTO。阅读器、详情和智能书架调用继续复用`ApiService`。`CompatibleApiServiceTest`覆盖3.7传输、旧路由兼容、错误保留、分页和统计失败，`ReaderSyncHttpTest`覆盖既有跨端读取流程。7项适配器测试和1项既有HTTP同步测试通过。Server37IntegrationTest经真实NAS隔离服务验证公共进度、智能书架、统计、会话和评论，默认未准备隔离凭据时跳过。

## 官方0.8合并保留点

- Room版本8及MIGRATION_7_8必须与DatabaseModule一同迁入。DatabaseUpgradeTest使用旧版SQLite结构验证5至8和7至8，保留下载字节、路径、进度和离线队列。
- ReadingSessionTracker保留Channel与Mutex串行检查点，再合入userId归属和isPermanentRejection。读取队列时发生账号切换的回归位于ReadingSessionTrackerTest。
- ReaderTocSheet的倒序使用SwapVert图标和无障碍标签，章节数量与页签单行限制防止大字号英文挤占目录。ReaderInteractionTest在320dp、1.5倍字号核对当前章、搜索、系统边距和底部导航。
- BookCommentsTest使用资源键定位控件，支持英文与中文系统。两个0.8新增设置键保留中英文和格式参数。
