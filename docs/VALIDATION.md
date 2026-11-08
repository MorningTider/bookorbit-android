# Android 阅读器验收记录

更新于 2026-11-08。本轮版本为 `0.7.1-custom.1`，版本码 `702`，应用标识 `com.bookorbit.compat`。

## 自动检查

- Full 与 F-Droid 两个变体分别通过 264 项 JVM 测试，均为零失败、零错误、零跳过。
- 两个变体的 Android Lint、APK 构建均通过。Lint 错误为零，仍有依赖更新和既有代码建议类警告，未将其描述为零警告。
- Reader JS 兼容回归 9 项通过。
- 中英文资源覆盖与格式参数检查通过，共 482 项。
- Kotlin 新增文件已通过 ktfmt 格式化，相关 JS 和文档通过 Prettier，`git diff --check` 通过。
- 独立本机 Chromium 以 412×915 视口加载实际 Android Foliate 引擎和用户提供的 EPUB，成功加载本地字体处理器提供的一个字体，字号 22px、行高 39.6px，无阅读器错误。此项只验证引擎和字体通道，手机 WebView 与原生 Compose 操作另行验收。

## 新旧包兼容性

旧 `0.6.0-compat.1` 包和本次候选包的应用标识相同。本次版本码递增，二者签名证书 SHA-256 均为 `bbbb7c5af72d6774bf27930206d530b0ac09bd32a4c2402ebf77823c4779e983`。签名和版本条件支持覆盖升级，手机上的实际覆盖安装及数据保留仍待验收。

使用本机既有调试签名生成自用候选。GitHub CI 会自行生成调试密钥，因此 CI 的 APK 签名与本机自用候选不同。私钥没有上传仓库，后续正式签名方案另行配置。

## 产物

| 变体    | APK 路径                                                  | SHA-256                                                            |
| ------- | --------------------------------------------------------- | ------------------------------------------------------------------ |
| Full    | `app/build/outputs/apk/full/debug/app-full-debug.apk`     | `417cc7e864b4004927a200dc500d6b0b7bd1d631f08667980aeebfde2153f278` |
| F-Droid | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` | `509327278f636e68dea8d4872a293810656456396a0a27b7250da7d3af0edec7` |

两个 APK 均在 Git 忽略的构建目录。产物摘要和测试计数的机器记录在本机 `app/build/reports/custom-validation.json`。

## 尚未完成

本轮 ADB 没有已授权 Android 设备，未执行手机安装、Compose 界面触控、中文字体真机渲染、离线恢复与网页阅读日志端到端验证。其余待办见 `CUSTOMIZATION_REMAINING.md`。

本机 Git 初始化曾误落到父目录，随后移除了本次创建的父目录元数据，并在独立 Android 项目内恢复与 GitHub 基线完全一致的浅检出。当前父目录没有该误建 `.git`。
