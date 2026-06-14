# CameraDetect - Android人员检测应用

基于YOLOv8的Android实时人员检测应用，通过MQTT将检测结果推送到Home Assistant。

## 功能特性

- 实时摄像头预览
- YOLOv8人员检测（仅检测person类别）
- 实时显示检测到的人数
- 通过MQTT推送检测结果到Home Assistant
- 可配置的MQTT Broker设置

## 技术栈

- Kotlin
- CameraX
- TensorFlow Lite
- Eclipse Paho MQTT
- YOLOv8 (COCO预训练模型)

## 模型准备

1. 下载YOLOv8n PyTorch模型：
   ```bash
   wget https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt
   ```

2. 转换为TensorFlow Lite格式：
   ```bash
   pip install ultralytics
   yolo export model=yolov8n.pt format=tflite
   ```

3. 将生成的 `yolov8n_float16.tflite` 重命名为 `yolov8n.tflite`，放入 `app/src/main/assets/` 目录

## 构建

### 本地构建

```bash
./gradlew assembleDebug
```

### GitHub Actions自动构建

推送代码到main分支，GitHub Actions会自动编译并上传APK。

## Home Assistant配置

在Home Assistant的 `configuration.yaml` 中添加：

```yaml
mqtt:
  sensor:
    - name: "客厅人数"
      state_topic: "home/camera/person_count"
      value_template: "{{ value_json.person_count }}"
      unit_of_measurement: "人"
      json_attributes_topic: "home/camera/person_count"
      json_attributes_template: "{{ value_json | tojson }}"
```

## MQTT消息格式

```json
{
  "device_id": "camera_detect_1234567890",
  "timestamp": "2026-06-14T10:30:00+08:00",
  "person_count": 2,
  "detections": [
    {
      "confidence": 0.92,
      "bbox": [0.1, 0.2, 0.3, 0.5]
    }
  ]
}
```

## 使用说明

1. 安装APK并打开应用
2. 点击"设置"配置MQTT Broker地址
3. 返回主界面，点击"开始检测"
4. 应用会自动检测画面中的人员并推送数据

## 权限

- 摄像头权限（必需）
- 网络权限（必需）
