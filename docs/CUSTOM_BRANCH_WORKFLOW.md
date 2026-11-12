# Android fork 与上游升级

本仓库沿用服务端 BookOrbit 的定制分支维护方式。

- `origin` 指向 `https://github.com/MorningTider/bookorbit-android.git`。
- `upstream` 指向 `https://github.com/deranjer/bookorbit-android.git`。
- 当前基线为官方 `v0.8.0`，提交 `9cbc76a7c8b1a5a4e6453484ee46eba768b1a6d1`。
- 定制分支沿用 `custom/bookorbit-android-v0.7.1`，0.8整合分支为 `upgrade/upstream-v0.8.0`。分支名保留历史，实际基线以上述tag为准。

## 升级步骤

1. 获取上游正式 tag，记录版本、提交、依赖和发布时间。
2. 保存并提交当前定制改动，从定制分支创建升级分支。
3. 合并目标正式 tag，逐项处理冲突，结合需求清单和补丁索引检查功能。
4. 核对 API DTO、Android SDK、依赖锁、应用标识、版本码与签名。
5. 运行中文键和格式占位符校验、JS 兼容测试、两种 flavor 的单元测试、Lint 与 APK 构建。
6. 运行隔离模拟器的 `ReaderInteractionTest`、`ReaderEngineTest`、`BookExperienceTest` 与 `BookCommentsTest`，检查首次及每章字体就绪、重新进入非零进度、按住纸张、松手提交与拖回取消、连续导航、目录详情点击、首页快捷入口、阅读记录以及窄屏大字号搜索点选。保存截图和帧耗时，再完成真机覆盖安装、字体离线、进度日志、PDF/漫画和有声书回归。
7. 验收通过后合回定制分支，更新需求矩阵和验收文档，再推送用户 fork。

```sh
git fetch upstream --tags
git switch custom/bookorbit-android-v0.7.1
git switch -c upgrade/android-next-release
git merge --no-ff <verified-upstream-tag>
node --test compat-tests/*.test.mjs
./gradlew :app:testFullDebugUnitTest :app:testFdroidDebugUnitTest :app:lintFullDebug :app:lintFdroidDebug :app:assembleFullDebug :app:assembleFdroidDebug
```

当前已获取升级所需历史。若未来检出为浅克隆，先确认 `git rev-parse --is-shallow-repository`，再按需获取完整历史。不要重建 fork、覆盖定制分支或强推共享历史。

密钥、登录信息、真实图书、APK、构建目录和本机 `local.properties` 留在 Git 跟踪之外。现有自用包使用本机调试签名，正式分发签名和公开发行单独处理。

## 阅读器原生验收

```sh
./gradlew :app:connectedFdroidDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bookorbit.feature.reader.ReaderInteractionTest,com.bookorbit.feature.reader.ReaderEngineTest,com.bookorbit.feature.bookdetail.BookExperienceTest
```

在独立模拟器执行。引擎测试读取系统 Noto Serif 字体，使用本机 MockWebServer、独立加密凭据空间与自制 EPUB。字体通道和中文家族实际字形分别验收。

截图和测量写入测试应用的 `reader-ui-validation` 目录。Gradle 测试工具可能在完成后卸载应用，需要截图时可手动安装两个测试 APK，以 `am instrument` 运行后取出。产物保存在 Git 忽略的构建目录。

跨端同步回归同时运行 `ReaderProgressSyncTest` 和 `ReaderSyncHttpTest`，核对 `textUpdatedAt`、`source=text`、旧离线检查点、网页主动回退及本机时钟偏差。模拟HTTP与实际NAS的手机联调分别记录。

安装标识回归运行 `com.bookorbit.app.AppBrandingTest`，Full与F-Droid分别检查实际名称、中英文资源、普通与圆形图标。主名称为BookOrbit，背景为蓝色 `#2563EB`，前景为白色轨道。上游合并后检查 `src/debug` 是否重新覆盖这些主资源。

共享评论升级需同时核对服务端生成迁移、DTO中的可空权限字段、回复归属、删除权限、幂等ID，以及切换书籍/讨论时旧请求不能覆盖新目标。真实页码需检查根资源引用计数，后台排版释放不能撤销正在阅读章节的Blob。服务端书架按最近阅读格式选取进度，手机显示保留一位小数。

## 0.8与服务端3.7验收

上游两处冲突为版本号和阅读会话仓库。保留801版本码、兼容标识与定制串行检查点，合入官方离线队列归属。除页面和引擎验收外，同时执行DatabaseUpgradeTest。3.7旧进度、智能书架和部分统计端点受到App Attest保护，Android通过CompatibleApiService调用公开等价接口。认证与权限失败继续返回调用方。

Server37IntegrationTest采用可选隔离凭据文件，在目标测试应用私有files目录中提供server37-validation.json。字段为url、username、password、bookId、fileId和scopeId，账号前缀为android08_。使用独立原生服务、数据库和自制EPUB，经普通setup、书库、上传和智能书架API准备。凭据经SSH传输，设备使用adb reverse接入本机回环通道，验收后清除两端文件、临时数据库、账号、应用进程与通道。禁止使用生产账号或生产书目作为此测试夹具。

这项接口联调与用户手机的阅读位置、网页接续和流畅度验收分别记录。当前证据见VALIDATION.md。


## custom.2升级保留点

逐项复验BA-C30至BA-C33。服务器端TXT转换器或临时阅读缓存发生变更时，同时核对TXT适配器的编码、100000字符分段、h1/p结构、章节路径和CFI。保留节点与锚点互开测试，避免只比较百分比或章节序号。

阅读偏好同步沿用服务端严格字段表，发送变化字段的PATCH。验重策略由服务器返回，未确认重复处理方式时不发送强制新建。封面经过服务器代理。新版本保留账号隔离的缓存、待同步记录以及页面退出后的持久写入。

完整回归新增SourceContractTest、SourcesViewModelTest、ReaderSourceTest、SourceCoverUrlTest、ReaderPreferenceCodecTest、ReaderPreferenceSyncTest与SourcesScreenTest。保留纸张实际绘制帧、按住和松手、字体就绪及窄屏大字号检查。卷页使用postOnAnimation并限制单帧推进，延迟排版后仍需实际绘制中间纸张状态。排版缓存需核对textIndent与paragraphIndent的双向转换，包含断网重开。
