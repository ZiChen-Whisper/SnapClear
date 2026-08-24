# SnapClear

专为 OPPO / ColorOS 设计的截图监听与快捷清理工具。SnapClear 会在后台检测新截图，通过通知或 ColorOS 流体云提供“拷贝并删除”操作：图片会先复制到独立缓存并写入系统剪贴板，再由系统确认是否将原截图移入回收站。

> 当前版本主要面向 OPPO / OnePlus / realme 的 ColorOS 系统。其他 Android 设备可能可以运行，但暂未针对其后台限制和通知行为进行适配。

## 功能

- 后台持续监听新截图，并通过多层检测机制降低漏检概率
- 通知快捷操作：拷贝并删除、忽略
- Android 16 / ColorOS 16 流体云（Live Updates）通知
- 最近截图卡片与列表视图、分页浏览和截图详情
- OPPO 权限页面引导、电池优化与精确闹钟状态检查
- 深色模式、沉浸式界面与自适应应用图标
- 开机后恢复已启用的截图监听

## 系统要求

- Android 12（API 31）及以上
- 推荐 OPPO / OnePlus / realme 的 ColorOS 系统
- 完整体验需要媒体、通知、无障碍、精确闹钟和电池优化豁免等权限；应用内会逐项引导

无障碍服务仅监听系统窗口变化，用于在 ColorOS 截图浮层出现时及时唤醒检测，不读取窗口内容、屏幕文字或输入内容，也不会代替用户操作其他应用。

## 安装

1. 在仓库的 [Releases](https://github.com/ZiChen-Whisper/SnapClear/releases) 页面下载最新 APK。
2. 在手机上允许从当前来源安装应用，然后安装 APK。
3. 打开 SnapClear，按照“权限”页面完成必要授权。
4. 返回首页并启用截图监听。

升级安装必须使用由同一发布密钥签名的新版本。若系统提示签名不一致，请先确认下载来源，不要直接卸载旧版本，以免丢失应用设置。

## 工作方式

SnapClear 综合使用文件事件、MediaStore 变化、进程内定时检查以及系统调度任务检测截图。检测到截图后，应用发送快捷通知；选择“拷贝并删除”时，原图不会被直接静默删除，而是交由 Android 系统显示确认界面并移入回收站。

为了保持后台监听，应用会运行前台服务，并按系统与 ColorOS 的限制使用唤醒和调度能力，因此可能产生一定后台耗电。实际可靠性和功耗会受机型、ColorOS 版本及系统权限设置影响。

## 隐私说明

- 截图识别和列表查询均在设备本地完成。
- 应用不上传截图，也不包含账号系统或云端同步功能。
- “拷贝并删除”会将图片副本写入应用缓存和系统剪贴板；原截图的回收操作由系统确认。
- 应用源码中未集成广告或统计 SDK。

## 从源码构建

项目使用 Android Gradle Plugin、Kotlin 和 Jetpack Compose。请先安装 JDK 11 及 Android SDK 36，然后在仓库根目录执行：

```bash
./gradlew assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

正式发布包需要使用自己的长期发布密钥签名。开启 release 优化后可执行：

```bash
./gradlew assembleRelease
```

请妥善离线备份发布密钥；Android 后续版本必须使用相同密钥签名才能覆盖安装。

## 反馈

如遇到漏检、通知未出现或 ColorOS 后台限制问题，请在 [Issues](https://github.com/ZiChen-Whisper/SnapClear/issues) 中提供手机型号、Android/ColorOS 版本、复现步骤和相关日志。

## 免责声明

本项目仍处于早期版本。涉及截图移动到回收站的操作前，请确认系统提示中的文件信息，并自行保留重要图片备份。
