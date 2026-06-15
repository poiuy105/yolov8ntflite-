# 功能增强设计文档：HA 自动发现 + 前后摄像头切换 + 日志面板

## 1. MQTT Home Assistant 自动发现

### 设计

Home Assistant MQTT Discovery 协议要求设备在启动时向特定 Topic 发送配置 JSON，HA 会自动创建对应的实体。

**Discovery Topic 格式：** `homeassistant/<component>/<node_id>/<object_id>/config`

**自动发现实体列表：**

| 实体 | 类型 | Discovery Topic | 状态 Topic |
|------|------|----------------|-----------|
| 人数计数 | sensor | `homeassistant/sensor/camera_detect/person_count/config` | `home/camera/person_count` |
| 设备在线状态 | binary_sensor | `homeassistant/binary_sensor/camera_detect/online/config` | `home/camera/device_status` |
| 电池电量 | sensor | `homeassistant/sensor/camera_detect/battery_level/config` | `home/camera/device_status` |
| 电池温度 | sensor | `homeassistant/sensor/camera_detect/battery_temp/config` | `home/camera/device_status` |

**配置消息示例（人数计数）：**
```json
{
  "name": "客厅人数",
  "state_topic": "home/camera/person_count",
  "value_template": "{{ value_json.person_count }}",
  "unit_of_measurement": "人",
  "icon": "mdi:account-group",
  "unique_id": "camera_detect_person_count",
  "device": {
    "identifiers": ["camera_detect_1234567890"],
    "name": "人员检测摄像头",
    "model": "YOLOv8n-TFLite",
    "manufacturer": "CameraDetect"
  }
}
```

**实现：** 在 `MqttManager.connect()` 成功后，发送 4 个 discovery 配置消息。

## 2. 前后摄像头切换

### 设计

在 MainActivity 添加切换按钮，通过 Service Binder 调用切换方法。

**Service 侧：**
- 添加 `switchCamera()` 方法
- 维护当前摄像头状态（front/back）
- 切换时 unbind 旧用例，重新 bind 新 CameraSelector

**UI 侧：**
- 在底部按钮栏添加"切换摄像头"按钮
- 按钮图标根据当前状态变化

## 3. 可收起日志面板

### 设计

在主页底部添加一个可展开/收起的日志面板，显示检测历史。

**数据结构：**
- `DetectionLogEntry(timestamp: String, personCount: Int)`
- 使用 `ArrayDeque` 保存最近 100 条记录
- 只在人数变化时添加新记录

**UI 设计：**
- 收起状态：底部显示一个小条，显示最新一条记录摘要
- 展开状态：从底部滑出，显示完整日志列表（RecyclerView）
- 展开/收起通过点击或滑动触发

**与 Service 通信：**
- Service 在 `onDetect`/`onEmptyDetect` 回调中通知 Activity
- Activity 通过 Binder 注册回调监听器

## 4. MQTT 默认配置更新

将默认配置改为：
- 地址：`192.168.123.49`
- 端口：`1883`
- 账号：`fnos`
- 密码：`fuckfnos`

---

## 文件变更清单

### 新建文件
- `app/src/main/java/com/example/cameradetect/HaDiscoveryManager.kt` — HA 自动发现配置管理
- `app/src/main/java/com/example/cameradetect/DetectionLogAdapter.kt` — 日志列表 RecyclerView Adapter
- `app/src/main/java/com/example/cameradetect/DetectionLogEntry.kt` — 日志数据类

### 修改文件
- `app/src/main/java/com/example/cameradetect/MqttManager.kt` — 添加 discovery 发送方法
- `app/src/main/java/com/example/cameradetect/DetectionForegroundService.kt` — 添加摄像头切换、日志通知
- `app/src/main/java/com/example/cameradetect/MainActivity.kt` — 添加日志面板、摄像头切换按钮
- `app/src/main/res/layout/activity_main.xml` — 添加日志面板和摄像头切换按钮
- `app/src/main/res/xml/root_preferences.xml` — 更新默认 MQTT 配置
- `app/src/main/res/values/strings.xml` — 添加新字符串
