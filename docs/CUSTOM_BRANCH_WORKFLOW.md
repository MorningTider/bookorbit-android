# Android fork 与上游升级

本仓库沿用服务端 BookOrbit 的定制分支维护方式。

- `origin` 指向 `https://github.com/MorningTider/bookorbit-android.git`。
- `upstream` 指向 `https://github.com/deranjer/bookorbit-android.git`。
- 当前基线为 `0.7.1`，提交 `18335ca80a1dec76997eefdcb3871877670f68b2`。
- 本轮定制分支为 `custom/bookorbit-android-v0.7.1`，上游 `main` 保留。

## 升级步骤

1. 获取上游正式 tag，记录版本、提交、依赖和发布时间。
2. 保存并提交当前定制改动，从定制分支创建升级分支。
3. 合并目标正式 tag，逐项处理冲突，结合需求清单和补丁索引检查功能。
4. 核对 API DTO、Android SDK、依赖锁、应用标识、版本码与签名。
5. 运行中文键和格式占位符校验、JS 兼容测试、两种 flavor 的单元测试、Lint 与 APK 构建。
6. 运行隔离模拟器的 `ReaderInteractionTest`、`ReaderEngineTest` 与 `BookExperienceTest`，检查首次及每章字体就绪、重新进入非零进度、按住纸张、松手提交与拖回取消、连续导航、目录详情点击、首页快捷入口、阅读记录以及窄屏大字号搜索点选。保存截图和帧耗时，再完成真机覆盖安装、字体离线、进度日志、PDF/漫画和有声书回归。
7. 验收通过后合回定制分支，更新需求矩阵和验收文档，再推送用户 fork。

```sh
git fetch upstream --tags
git switch custom/bookorbit-android-v0.7.1
git switch -c upgrade/android-next-release
git merge --no-ff <verified-upstream-tag>
node --test compat-tests/*.test.mjs
./gradlew :app:testFullDebugUnitTest :app:testFdroidDebugUnitTest :app:lintFullDebug :app:lintFdroidDebug :app:assembleFullDebug :app:assembleFdroidDebug
```

当前初始检出为精确浅检出，联网合并其他历史前先运行 `git fetch --unshallow origin`。获取完整历史后才判断合并关系。不要重建 fork、覆盖定制分支或强推共享历史。

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
