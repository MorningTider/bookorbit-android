# Android 阅读器验收记录

更新于 2026-11-09，候选 `0.7.1-custom.4`，版本码 `705`，应用标识 `com.bookorbit.compat`。

## 自动检查

- Full 与 F-Droid 各通过 270 项 JVM 测试，零失败、零错误、零跳过。
- JS 兼容与阅读交互回归 21 项通过。
- 中英文覆盖和格式参数检查通过，共 526 项资源。
- 双变体 Lint、APK 与原生测试包构建通过。两种 Lint 均零错误、144 项警告。
- `git diff --check` 通过。本轮主要阅读界面和新文件经过 ktfmt 或 Prettier，其他区域保持原有格式。

## 问题复现与修复验收

- 在旧 `custom.3` 上加入首屏字体就绪断言，实际失败。记录为 `custom4-baseline/reader-ui-validation/startup-regression.txt`，值为 `fontReadyAtLoaded=false`。旧流程先报告已加载，随后异步注入字体。
- 新版在打开图书前装载缓存字体，桥接等待字体与布局，Android 宿主等待可见帧提交。首次打开和重新创建 WebView 均检查字体已加载且正文使用所选家族。恢复约 42% 的实际 CFI 位置通过，已保存文字位于可见页面，截图包含正文。百分比会随分页重排取整，验收以实际文字锚点为准。加载过程的临时位置在宿主内合并，就绪后才通知进度存储。
- 卷页原流程在输入后查询边界、等待目标页并截取整个窗口。新版在正文稳定后预缓存纯正文，输入直接开始原生动画，小幅拖动的底层正文偏移断言通过。
- 连续翻页等待 Foliate 导航 Promise 完成，避免页码事件早于内部解锁而丢失下一次操作。
- 设置面板按参考图使用紧凑行、圆形颜色、翻页分段选择和底部导航。新增覆盖翻页、纸纹/柔光背景、带封面作者的详情/目录/金句/笔记页签。

帧提交使用 Android 官方说明中的硬件帧提交回调，旧 API 与软件渲染保留兼容路径，这两条路径尚未在本轮设备运行。该回调对应已渲染并提交的帧。[Android ViewTreeObserver](<https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)>)

## 原生模拟器验收

环境为 Android 15 / API 35、arm64、独立 AVD `bookorbit-reader-api35`，1080×2400 像素，宿主 GPU。

- 6 项 Compose 交互测试通过。字号/字体、间距与翻页模式、护眼、自动阅读、当前章/搜索/选择、覆盖与背景设置、详情/金句/笔记页签均检查。
- 1 项真实 WebView 引擎测试通过，包含自制 EPUB、隔离字体下载、本地字体处理器、首次与重新进入、非零阅读位置、正文宽度和可见绘制、护眼底色、音量键、自动阅读与停止、连续及反向卷页、横向滑动、覆盖翻页和章节跳转。
- 相同 6 项交互在 320dp 窄屏和 1.5 倍系统字号下通过，底部导航断言通过。
- 测试使用隔离会话、MockWebServer 和自制文本。字体文件为模拟器的真实 Noto Serif，中文回退字形与用户服务器上的各中文字体仍需在手机逐项验收。

本机截图在 `app/build/reports/custom4-reader-ui/reader-ui-validation/`，窄屏在 `custom4-reader-narrow`。旧版失败证据在 `custom4-baseline`。构建报告受 Git 忽略。

## 流畅度测量

使用 Window FrameMetrics，宿主 GPU 后端日志确认 Apple M4 Pro。当前版本先验证首次卷页并截图，随后清空帧样本，采集六次正向及一次反向操作。相邻正向操作间保留 150ms 以覆盖缓存生成，截图操作不计入本轮动画帧采样。

| 版本与后端                 | 帧样本 | 输入至起动 | 绘制中位数 | 帧间隔中位数 | 总帧中位数 | 总帧 P95 |
| -------------------------- | ------ | ---------- | ---------- | ------------ | ---------- | -------- |
| custom.3 宿主 GPU 历史记录 | 200    | 未记录     | 0.212 ms   | 16.67 ms     | 17.10 ms   | 41.99 ms |
| custom.4 宿主 GPU          | 129    | 34.96 ms   | 0.201 ms   | 16.67 ms     | 17.02 ms   | 17.58 ms |

当前样本中 129 帧超过 16.7ms，仍有超预算帧。两次测量包含不同的快照与交互时序，表格作为各版本记录，不能据此推算手机帧率。实际手机的帧率、触控响应与内存验收待完成。

快照最长边 1280 像素，一次只持有一张。缓存生成与纹理准备放在输入路径以外，缺少缓存时普通翻页，结束、尺寸变化和退出回收。

## 新旧包兼容性

与旧 `0.6.0-compat.1` 中文包应用标识一致，版本码递增。旧包与本机新包证书 SHA-256 均为 `bbbb7c5af72d6774bf27930206d530b0ac09bd32a4c2402ebf77823c4779e983`。签名与版本条件支持覆盖升级，手机实际安装和数据保留仍待验收。

使用本机既有调试签名生成自用候选。GitHub CI 自行生成调试密钥，其 APK 签名可能不同。本机私钥未上传。

## 产物

| 变体    | APK                                                       | SHA-256                                                            |
| ------- | --------------------------------------------------------- | ------------------------------------------------------------------ |
| Full    | `app/build/outputs/apk/full/debug/app-full-debug.apk`     | `f9d7ed7c1e6735becfdc2da68b8cb78bac4c96454139edeb61973f53aef4a2f2` |
| F-Droid | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` | `86deb39447074be494eaacca1897026f6b168c6140b93d2c4702f7236fcec04b` |

自用 Full 包保存为 `app/build/outputs/custom/BookOrbit-0.7.1-custom.4-zh-CN.apk`，摘要一致。机器记录为本机 `app/build/reports/custom-validation.json`。

## 尚待完成

手机覆盖安装、真实中文字体、离线恢复、网页端 Android 阅读日志，以及其他文件引擎的手机验收。仿真目前在完成滑动后播放。跟手拖动取消、图片背景、章节字数与精确页数等差距见 `CUSTOMIZATION_REMAINING.md`。
