# 第二阶段：电池与过热保护设计文档

## 1. 温度监控与保护（ThermalManager）

### 设计

**温度来源：**
- 电池温度：通过 `BatteryMonitor.batteryTemperature` 获取（已有）
- CPU 温度：读取 `/sys/class/thermal/thermal_zone0/temp`（部分设备支持）

**保护策略：**

| 温度状态 | 阈值 | 动作 |
|---------|------|------|
| 正常 | < 40°C | 正常推理 |
| 警告 | >= 40°C | 降低帧率到 1fps，通知栏提示"设备过热，已降频" |
| 严重 | >= 45°C | 暂停检测（停止 CameraX 分析），保留心跳和 MQTT 连接，通知栏提示"设备过热，检测已暂停" |
| 恢复 | < 38°C | 自动恢复原始帧率 |

**实现：**
- 在 `DetectionForegroundService` 中添加温度检查协程，每 10 秒检查一次
- 使用状态机管理温度状态（NORMAL / WARNING / CRITICAL）
- 状态变化时通过 MQTT 上报 `thermal_status` 事件

## 2. 充电状态管理（PowerManager）

### 设计

**策略：**

| 充电状态 | 动作 |
|---------|------|
| 充电中 | 使用用户配置的帧率 |
| 未充电 + 电量 > 20% | 降低帧率到 1fps（省电模式） |
| 未充电 + 电量 <= 20% | 暂停检测，仅保留心跳 |

**实现：**
- 复用 `BatteryMonitor.isCharging` 和 `batteryLevel`
- 在 `DetectionForegroundService` 中监听充电状态变化
- 状态变化时自动调整 `InferenceThrottler.intervalMs`

## 3. 屏幕管理（ScreenManager）

### 设计

**目标：** 检测服务启动后自动管理屏幕，减少耗电和烧屏风险。

**策略：**
- 启动检测后 30 秒，自动降低屏幕亮度到最低（保留显示）
- 用户点击屏幕时恢复亮度
- 支持"息屏运行"模式：完全关闭屏幕，仅保留通知栏

**实现：**
- 使用 `WindowManager.LayoutParams.screenBrightness` 控制亮度
- 使用 `PowerManager.WakeLock` 控制 CPU 不休眠
- 在 `MainActivity` 中处理触摸事件恢复亮度

## 4. 环境光检测（AmbientLightManager）

### 设计

**目标：** 暗光环境自动降低帧率或暂停（夜间省电）。

**策略：**

| 环境亮度 | 动作 |
|---------|------|
| >= 50 lux | 正常推理 |
| 10 ~ 50 lux | 降低帧率到 1fps |
| < 10 lux | 暂停检测（夜间模式） |

**实现：**
- 使用 `SensorManager.TYPE_LIGHT` 光线传感器
- 在 `DetectionForegroundService` 中注册传感器监听
- 亮度变化时调整推理策略

## 5. 配置项扩展

在设置页增加：

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `thermal_protection` | SwitchPreference | true | 启用温度保护 |
| `power_save_mode` | SwitchPreference | true | 未充电时自动省电 |
| `auto_dim_screen` | SwitchPreference | true | 启动后自动降低亮度 |
| `night_mode` | SwitchPreference | true | 暗光时自动降低帧率 |

## 6. 文件变更清单

### 新建文件
- `app/src/main/java/com/example/cameradetect/ThermalManager.kt` — 温度状态管理和保护逻辑
- `app/src/main/java/com/example/cameradetect/AmbientLightManager.kt` — 光线传感器管理

### 修改文件
- `app/src/main/java/com/example/cameradetect/DetectionForegroundService.kt` — 集成温度、充电、光线管理
- `app/src/main/java/com/example/cameradetect/MainActivity.kt` — 添加屏幕亮度控制
- `app/src/main/res/xml/root_preferences.xml` — 添加新配置项
