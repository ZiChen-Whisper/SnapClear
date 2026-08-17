# SnapClear — AI Agent 导航指南

## 项目概述

OPPO/ColorOS 截图监听与剪贴板管理工具。后台持续检测用户截图事件，即时弹出通知并提供"拷贝并删除"快捷操作。针对 ColorOS 深度定制，集成流体云（Live Updates）通知、OPPO 权限路径引导等 OEM 专属适配。

## 核心入口

```
MainActivity（仪表盘）
  └─ 启动/停止 → ScreenshotMonitorService（前台服务，specialUse 类型）
       ├─ 四层检测引擎（见下方）
       ├─ WakeLock（PARTIAL，30 分钟超时，防止 CPU 休眠导致进程冻结）
       ├─ START_STICKY（系统杀死后自动重启）
       └─ 开机自启（BootCompletedReceiver） + 闹钟唤醒（ScreenshotAlarmReceiver）
```

## 模块边界

### `screenshot/` — 四层检测引擎

| 层 | 机制 | 文件 | 间隔 | 说明 |
|---|------|------|------|------|
| 1 | FileObserver | `ScreenshotFileObserver.kt` | 事件驱动 | Linux inotify，监听 `Pictures/Screenshots` + `DCIM/Screenshots`，500ms 防抖，过滤 `.pending-` 临时文件 |
| 2 | ContentObserver | `ScreenshotObserver.kt` | 事件驱动 | 监听 `MediaStore.Images`，含 1s 延迟重试（IS_PENDING 补偿） |
| 3 | Handler 轮询 | `ScreenshotMonitorService.kt` (pollRunnable) | 10s | 进程内存活时最可靠兜底，国产 ROM 后台 FileObserver/ContentObserver 可能被延迟投递 |
| 4 | AlarmManager | `ScreenshotAlarmReceiver.kt` | 30s | 进程被杀后最终兜底，三级回退策略（见下方） |

- `BootCompletedReceiver.kt`：BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 恢复监听
- 统一检测入口：`ScreenshotObserver.detectAndAdvance()`，四层共用 `detectionLock` 互斥锁
- 截图判定：路径含 `screenshots` 关键字 OR 文件名含 `screenshot/截图/截屏`

### `notification/` — 通知渠道 + 流体云

- `NotificationHelper.kt`：
  - 三个通知渠道：`screenshot_action`（HIGH，每次重建防 OEM 降级）、`monitor_service`（LOW）、`live_update_channel`（HIGH）
  - API 36+ 前台：流体云 Live Updates（折叠态 + 展开态 30s 倒计时进度条）
  - API 36+ 后台 / 低版本：回退 `PRIORITY_MAX + fullScreenIntent`（绕过国产 ROM 后台通知抑制）
- `NotificationActionReceiver`（`exported="false"`）：处理通知 Action（拷贝并删除 / 忽略）

### `clipboard/` — 剪贴板操作

- `ClipboardHelper.kt`：
  - 先复制图片数据到缓存 → FileProvider URI → 系统剪贴板（独立于原文件）
  - 再弹出 `MediaStore.createDeleteRequest()`（API 30+）系统删除确认框

### `permission/` — 权限管理 + OPPO 特殊路径

- `PermissionManager.kt`：
  - 运行时权限：`READ_MEDIA_IMAGES`（API 33+）/ `READ_EXTERNAL_STORAGE`（API 31-32）、`POST_NOTIFICATIONS`
  - 特殊权限：`SCHEDULE_EXACT_ALARM`、电池优化豁免、`POST_PROMOTED_NOTIFICATIONS`（API 36+ 流体云）
  - `isOppoDevice()`：检测 OPPO / OnePlus / Realme
  - OPPO 权限页面路径：`com.coloros.safecenter` / `com.oppo.safe` 的 PermissionManagerActivity

### `diagnostic/` — 日志诊断

- `DiagnosticLogger.kt`：内存环形缓冲区（100 条），同步输出 logcat（tag = `SnapClear Diag`）
- `DiagnosticsActivity.kt` / `DiagnosticsScreen.kt`：诊断面板，手动触发检测 / 创建测试截图 / 发送测试通知

## 关键风险路径

### 1. `lastDetectedId` 持久化

- 存储于 `SharedPreferences("snapclear_prefs")` 的 `last_detected_id` key
- **持久化恢复时不推进到 MediaStore 最大 ID**：如果推进到 max，进程被杀期间的截图会因 `_ID <= lastDetectedId` 被跳过（漏检）
- **首次运行推进到 max**：避免对历史所有图片触发通知风暴
- 每处理一张图片（无论是否截图）都推进 lastDetectedId，防止非截图图片阻塞检测流水线

### 2. AlarmManager 三级回退

```
setExactAndAllowWhileIdle（需 SCHEDULE_EXACT_ALARM，穿透 Doze）
  ↓ SecurityException 或其他异常
setAndAllowWhileIdle（无需权限，但有 9 分钟批处理窗口）
  ↓ 异常
setAlarmClock（最后兜底，状态栏显示闹钟图标，使用墙上时间 System.currentTimeMillis()）
```

- 使用 `ELAPSED_REALTIME_WAKEUP`，非 RTC
- 同时声明 `USE_EXACT_ALARM`（API 33+ 自动授予）使 `canScheduleExactAlarms()` 恒定返回 true

### 3. OPPO/ColorOS 后台限制策略

- FileObserver / ContentObserver 在后台可能被延迟投递（仅回到前台时批量投递）→ Handler 轮询是真正可靠的后台检测手段
- 后台通知可能被静默拦截 → `fullScreenIntent + PRIORITY_MAX + CATEGORY_REMINDER` 绕过
- 前台服务可能被杀死 → `START_STICKY` + AlarmManager 自动拉起 + WakeLock 防 CPU 休眠
- 通知渠道可能被 OEM 降级 → 每次 `createChannels()` 先 `deleteNotificationChannel()` 再重建

## 核心安全约束

| 组件 | exported | 说明 |
|------|----------|------|
| `NotificationActionReceiver` | `false` | 内部广播接收器，仅处理通知 Action |
| `FileProvider` | `false` | 仅用于应用内部缓存共享到剪贴板，不对外暴露 |
| `ScreenshotMonitorService` | `false` | 仅由应用内部启动 |
| `ScreenshotAlarmReceiver` | `false` | 仅接收自身发送的 AlarmManager 广播 |
| `BootCompletedReceiver` | `true` | 仅响应系统 BOOT_COMPLETED 等受保护广播 |
| `MainActivity` | `true` | 唯一导出组件，仅响应 LAUNCHER 和 `COPY_DELETE` action |

- 前台服务 + WakeLock（PARTIAL，30 分钟）持续占用 CPU，需关注电池影响
- 闹钟 30s 间隔，每天 2880 次 MediaStore 查询（每次 <1ms），功耗可忽略

## 构建与运行

```bash
# 编译 Debug APK
./gradlew assembleDebug

# 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

- AGP 9.x + Kotlin Compose + Compose BOM
- `minSdk = 31`，`targetSdk = 36`（Android 16 / ColorOS 16）
- Compose BOM 版本对齐约束：`implementation(platform(libs.androidx.compose.bom))`

## 非目标

- **非 OPPO/ColorOS 设备**的截图检测不在当前范围内。虽然基础检测逻辑在 AOSP 上可用，但流体云、OPPO 权限路径、ColorOS 后台限制适配均为 ColorOS 专属。在 MIUI / HyperOS / OriginOS 等国产 ROM 上可能存在通知不显示、服务被杀等未适配问题。
