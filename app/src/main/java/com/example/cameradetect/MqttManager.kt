package com.example.cameradetect

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Qos
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MqttManager(private val context: Context) {

    private var mqttClient: Mqtt3AsyncClient? = null
    private var _isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var brokerHost: String = "192.168.1.100"
    var brokerPort: Int = 1883
    var clientId: String = "camera_detect_${System.currentTimeMillis()}"
    var topic: String = "home/camera/person_count"
    var username: String = ""
    var password: String = ""

    val isConnected: Boolean get() = _isConnected

    private var lastPersonCount = -1
    private var lastPublishTime = 0L
    private val publishInterval = 5000L

    fun connect() {
        scope.launch {
            try {
                disconnect()

                val builder = Mqtt3AsyncClient.builder()
                    .identifier(clientId)
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)

                if (username.isNotEmpty()) {
                    builder.simpleAuth()
                        .username(username)
                        .password(password.toByteArray())
                        .applySimpleAuth()
                }

                mqttClient = builder.buildAsync()

                mqttClient?.connectWith()
                    ?.cleanSession(true)
                    ?.keepAlive(20)
                    ?.send()
                    ?.whenComplete { result, throwable ->
                        if (throwable != null) {
                            Log.e("MqttManager", "Connect failed", throwable)
                            _isConnected = false
                        } else {
                            Log.i("MqttManager", "Connected to MQTT broker")
                            _isConnected = true
                        }
                    }
            } catch (e: Exception) {
                Log.e("MqttManager", "Connect error", e)
                _isConnected = false
            }
        }
    }

    fun publishPersonCount(count: Int, detections: List<BoundingBox> = emptyList()) {
        if (!_isConnected) return

        val currentTime = System.currentTimeMillis()
        if (count == lastPersonCount && (currentTime - lastPublishTime) < publishInterval) {
            return
        }

        lastPersonCount = count
        lastPublishTime = currentTime

        scope.launch {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                val timestamp = sdf.format(Date())

                val json = JSONObject().apply {
                    put("device_id", clientId)
                    put("timestamp", timestamp)
                    put("person_count", count)

                    val detectionsArray = JSONArray()
                    detections.forEach { box ->
                        val detectionObj = JSONObject().apply {
                            put("confidence", box.cnf)
                            put("bbox", JSONArray().apply {
                                put(box.x1)
                                put(box.y1)
                                put(box.x2)
                                put(box.y2)
                            })
                        }
                        detectionsArray.put(detectionObj)
                    }
                    put("detections", detectionsArray)
                }

                mqttClient?.publishWith()
                    ?.topic(topic)
                    ?.payload(json.toString().toByteArray())
                    ?.qos(MqttQos.AT_LEAST_ONCE)
                    ?.send()
            } catch (e: Exception) {
                Log.e("MqttManager", "Publish error", e)
            }
        }
    }

    fun publishEmptyDetection() {
        publishPersonCount(0, emptyList())
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            _isConnected = false
        } catch (e: Exception) {
            Log.e("MqttManager", "Disconnect error", e)
        }
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
