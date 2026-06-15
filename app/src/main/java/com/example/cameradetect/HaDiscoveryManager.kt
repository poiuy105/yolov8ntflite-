package com.example.cameradetect

import org.json.JSONObject

class HaDiscoveryManager(private val mqttManager: MqttManager) {

    fun sendDiscoveryConfigs() {
        val deviceJson = JSONObject().apply {
            put("identifiers", jsonArray(mqttManager.clientId))
            put("name", "人员检测摄像头")
            put("model", "YOLOv8n-TFLite")
            put("manufacturer", "CameraDetect")
            put("sw_version", "1.1.0")
        }

        // 1. 人数计数传感器
        sendDiscovery(
            component = "sensor",
            objectId = "person_count",
            config = JSONObject().apply {
                put("name", "客厅人数")
                put("state_topic", mqttManager.topic)
                put("value_template", "{{ value_json.person_count }}")
                put("unit_of_measurement", "人")
                put("icon", "mdi:account-group")
                put("unique_id", "${mqttManager.clientId}_person_count")
                put("device", deviceJson)
            }
        )

        // 2. 设备在线状态
        sendDiscovery(
            component = "binary_sensor",
            objectId = "online",
            config = JSONObject().apply {
                put("name", "监控设备在线")
                put("state_topic", "home/camera/device_status")
                put("value_template", "{{ 'ON' if value_json.status == 'online' else 'OFF' }}")
                put("payload_on", "online")
                put("payload_off", "offline")
                put("device_class", "connectivity")
                put("unique_id", "${mqttManager.clientId}_online")
                put("device", deviceJson)
            }
        )

        // 3. 电池电量
        sendDiscovery(
            component = "sensor",
            objectId = "battery_level",
            config = JSONObject().apply {
                put("name", "监控设备电量")
                put("state_topic", "home/camera/device_status")
                put("value_template", "{{ value_json.battery_level }}")
                put("unit_of_measurement", "%")
                put("icon", "mdi:battery")
                put("unique_id", "${mqttManager.clientId}_battery_level")
                put("device", deviceJson)
            }
        )

        // 4. 电池温度
        sendDiscovery(
            component = "sensor",
            objectId = "battery_temp",
            config = JSONObject().apply {
                put("name", "监控设备温度")
                put("state_topic", "home/camera/device_status")
                put("value_template", "{{ value_json.battery_temperature }}")
                put("unit_of_measurement", "°C")
                put("icon", "mdi:thermometer")
                put("unique_id", "${mqttManager.clientId}_battery_temp")
                put("device", deviceJson)
            }
        )
    }

    private fun sendDiscovery(component: String, objectId: String, config: JSONObject) {
        val topic = "homeassistant/$component/${mqttManager.clientId}/$objectId/config"
        mqttManager.publishRaw(topic, config.toString())
    }

    private fun jsonArray(vararg items: String): org.json.JSONArray {
        return org.json.JSONArray().apply {
            items.forEach { put(it) }
        }
    }
}
