# 第一阶段：核心稳定性优化设计文档

> **目标：** 将当前 Demo 级应用升级为可 7x24 小时稳定运行的工业级边缘 AI 监控节点。

---

## 1. 当前问题分析

### 1.1 内存问题：每帧创建 Bitmap

**现状：** `processImage()` 中每帧都调用 `Bitmap.createBitmap()` 和 `bitmap.copyPixelsFromBuffer()`，帧处理完后 Bitmap 被丢弃等待 GC。

**风险：**
- 30fps 场景下每秒创建 30 个 Bitmap，堆内存剧烈波动
- GC 频繁触发导致卡顿（推理时间不稳定）
- 长时间运行后可能触发 OOM（尤其旧手机内存小）

**方案：** 引入 Bitmap 对象池，复用固定数量的 Bitmap 实例。

### 1.2 发热问题：全帧率推理

**现状：** `ImageAnalysis` 使用 `STRATEGY_KEEP_ONLY_LATEST`，每来一帧就推理一帧。

**风险：**
- 手机摄像头通常输出 30fps，意味着每秒 30 次 AI 推理
- CPU/GPU 持续满载，旧手机几分钟就会过热降频
- 长期插电运行可能损坏电池

**方案：** 引入可配置的推理帧率限制器，默认 2fps（每 500ms 推理一次），兼顾实时性和功耗。

### 1.3 生命周期问题：Activity 绑定的相机

**现状：** 相机和检测逻辑全部在 `MainActivity` 中，Activity 退到后台或被系统杀死后，检测停止。

**风险：**
- 无法息屏运行（用户需要常亮屏幕）
- 系统内存紧张时会杀死后台 Activity
- 不符合 24 小时无人值守要求

**方案：** 将核心检测逻辑迁移到 Android Foreground Service，Activity 仅作为配置/监控界面。

### 1.4 网络问题：MQTT 无重连机制

**现状：** `MqttManager.connect()` 只在 `onResume()` 调用一次，断网后不会自动重连。

**风险：**
- WiFi 短暂断开后，MQTT 永久断开
- 用户无法感知设备离线（Home Assistant 看不到数据）

**方案：** 实现指数退避重连策略，断线后自动尝试重连。

### 1.5 可观测性问题：系统不知道设备是否活着

**现状：** 只有检测到人才发 MQTT 消息，如果画面里一直没人，Home Assistant 无法区分"设备正常但没人"和"设备已离线"。

**风险：**
- 设备死机或断网数小时都无法发现
- 无法远程诊断问题

**方案：** 引入看门狗心跳机制，每 30 秒发送一次 `device_status` 消息，包含设备健康信息。

---

## 2. 设计方案

### 2.1 Bitmap 对象池（BitmapPool）

```
┌─────────────────────────────────────────┐
│           BitmapPool (单例)              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │ Bitmap  │ │ Bitmap  │ │ Bitmap  │   │
│  │  (可用)  │ │  (可用)  │ │ (使用中) │   │
│  └─────────┘ └─────────┘ └─────────┘   │
│                                         │
│  acquire(width, height) -> Bitmap       │
│  release(bitmap) -> 归还到池             │
└─────────────────────────────────────────┘
```

**设计要点：**
- 池大小固定为 3（当前帧 + 预备帧 + 安全缓冲）
- `acquire()` 时如果池空，阻塞等待（或用同步队列）
- `release()` 时清空 Bitmap 像素数据后归还
- 尺寸变化时（如切换摄像头）重建池

### 2.2 推理帧率限制器（InferenceThrottler）

```
CameraX (30fps)
    │
    ▼
┌─────────────────┐
│ 帧率限制器       │  检查：距离上次推理是否 >= intervalMs?
│ (默认 500ms)    │  是 -> 放行到推理
└─────────────────┘  否 -> 丢弃帧，直接 close imageProxy
    │
    ▼
YOLO 推理
```

**设计要点：**
- 可配置间隔：1fps(1000ms) / 2fps(500ms) / 5fps(200ms) / 10fps(100ms)
- 使用 `SystemClock.elapsedRealtime()` 计时，不受系统时间调整影响
- 丢弃的帧立即 `imageProxy.close()`，不进入后续处理

### 2.3 后台服务化（DetectionForegroundService）

```
┌─────────────────────────────────────────────┐
│         DetectionForegroundService            │
│  ┌─────────────┐  ┌─────────────┐           │
│  │  CameraX    │  │  YoloDetector│           │
│  │  (后台运行)  │  │  (推理)      │           │
│  └─────────────┘  └─────────────┘           │
│         │                │                   │
│         └────────────────┘                   │
│                   │                          │
│              ┌─────────┐                     │
│              │MqttManager│ (发送结果)         │
│              └─────────┘                     │
│                   │                          │
│              ┌─────────┐                     │
│              │Watchdog │ (30s 心跳)          │
│              └─────────┘                     │
└─────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────┐
│         MainActivity (UI 界面)               │
│  - 显示实时预览 (可选)                        │
│  - 显示当前人数                              │
│  - 启动/停止服务按钮                          │
│  - 配置参数                                  │
└─────────────────────────────────────────────┘
```

**设计要点：**
- Service 类型：`android.app.Service` + `startForeground()`
- 通知栏显示：运行状态、当前人数、MQTT 连接状态
- Activity 绑定 Service 获取实时数据，但 Service 不依赖 Activity
- 支持"仅后台运行"模式（不启动预览，进一步省电）

### 2.4 MQTT 断线重连（ExponentialBackoffReconnect）

```
连接成功
    │
    ▼
正常运行 ◄────────────────────┐
    │                         │
    ▼                         │
连接断开                      │
    │                         │
    ▼                         │
等待 1s 重试 ──► 失败 ──► 等待 2s ──► 失败 ──► 等待 4s ──► ... ──► 成功
```

**设计要点：**
- 初始重试间隔：1 秒
- 最大重试间隔：60 秒
- 退避因子：2x（指数增长）
- 使用 `kotlinx.coroutines` 的 `delay()` 实现非阻塞等待
- 连接成功后重置退避计数器

### 2.5 看门狗心跳（WatchdogHeartbeat）

**MQTT Topic：** `home/camera/device_status`

**消息格式（JSON）：**
```json
{
  "device_id": "camera_detect_1234567890",
  "timestamp": "2026-06-15T10:30:00+08:00",
  "status": "online",
  "uptime_seconds": 3600,
  "battery_level": 85,
  "battery_temperature": 35.5,
  "is_charging": true,
  "inference_fps": 2,
  "last_detection_count": 3,
  "mqtt_connected": true,
  "app_version": "1.1.0"
}
```

**设计要点：**
- 发送间隔：30 秒（可配置）
- 使用 `ACTION_BATTERY_CHANGED` 广播接收器获取电池信息
- 使用 `/sys/class/thermal/thermal_zone*/temp` 获取 CPU 温度（需要适配不同设备）
- Home Assistant 可配置自动化：超过 90 秒未收到心跳则告警

---

## 3. 配置项扩展

在现有设置页增加以下选项：

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `inference_fps` | ListPreference | 2 | 推理帧率：1/2/5/10 fps |
| `enable_preview` | SwitchPreference | true | 是否显示相机预览 |
| `heartbeat_interval` | EditTextPreference | 30 | 心跳间隔（秒） |
| `auto_start_on_boot` | SwitchPreference | false | 开机自启 |
| `enable_watchdog` | SwitchPreference | true | 启用心跳 |

---

## 4. Home Assistant 配置示例

```yaml
# configuration.yaml

# 传感器：人数计数
mqtt:
  - sensor:
      name: "客厅人数"
      state_topic: "home/camera/person_count"
      value_template: "{{ value_json.person_count }}"
      unit_of_measurement: "人"
      icon: mdi:account-group

  # 传感器：设备状态（心跳）
  - sensor:
      name: "监控设备状态"
      state_topic: "home/camera/device_status"
      value_template: "{{ value_json.status }}"
      json_attributes_topic: "home/camera/device_status"
      json_attributes_template: "{{ value_json | tojson }}"

  # 二进制传感器：设备在线状态
  - binary_sensor:
      name: "监控设备在线"
      state_topic: "home/camera/device_status"
      value_template: "{{ 'ON' if value_json.status == 'online' else 'OFF' }}"
      payload_available: "online"
      payload_not_available: "offline"
      device_class: connectivity

# 自动化：设备离线告警
automation:
  - alias: "监控设备离线告警"
    trigger:
      - platform: state
        entity_id: binary_sensor.monitoring_device_online
        to: "unavailable"
        for: "00:02:00"
    action:
      - service: notify.mobile_app_my_phone
        data:
          message: "监控设备已离线超过 2 分钟！"
          title: "设备告警"
```

---

## 5. 文件变更清单

### 新建文件
- `DetectionForegroundService.kt` — 前台服务，承载核心检测逻辑
- `BitmapPool.kt` — Bitmap 对象池
- `InferenceThrottler.kt` — 推理帧率限制器
- `WatchdogHeartbeat.kt` — 看门狗心跳发送器
- `BatteryMonitor.kt` — 电池状态监控

### 修改文件
- `MainActivity.kt` — 改为 Service 控制界面，移除检测逻辑
- `MqttManager.kt` — 添加断线重连机制
- `AndroidManifest.xml` — 添加 Service 声明、前台服务权限
- `root_preferences.xml` — 添加新配置项
- `app/build.gradle.kts` — 如需新增依赖

---

## 6. 验收标准

- [ ] 应用可在息屏状态下持续运行检测
- [ ] 拔掉 WiFi 后 5 分钟内恢复，MQTT 自动重连并恢复数据上报
- [ ] 连续运行 24 小时无崩溃、无 OOM
- [ ] Home Assistant 能实时显示人数，并在设备离线时收到告警
- [ ] 旧手机（Android 8+）运行时机身温度不超过 45°C
