package com.example.cameradetect

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MqttManager(private val context: Context) {

    private var mqttClient: Mqtt3AsyncClient? = null
    private var _isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L

    var brokerHost: String = "192.168.123.49"
    var brokerPort: Int = 1883
    var clientId: String = "camera_detect_${System.currentTimeMillis()}"
    var topic: String = "home/camera/person_count"
    var username: String = "fnos"
    var password: String = "fuckfnos"

    val isConnected: Boolean get() = _isConnected

    private var lastPersonCount = -1
    private var lastPublishTime = 0L
    private val publishInterval = 5000L

    fun connect() {
        if (_isConnected) return
        scope.launch {
            try {
                disconnectInternal()

                val clientBuilder = Mqtt3Client.builder()
                    .identifier(clientId)
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)

                if (username.isNotEmpty()) {
                    clientBuilder.simpleAuth()
                        .username(username)
                        .password(password.toByteArray())
                        .applySimpleAuth()
                }

                mqttClient = clientBuilder.buildAsync()

                mqttClient?.connectWith()
                    ?.cleanSession(true)
                    ?.keepAlive(20)
                    ?.send()
                    ?.whenComplete { _, throwable ->
                        if (throwable != null) {
                            Log.e("MqttManager", "Connect failed", throwable)
                            _isConnected = false
                            scheduleReconnect()
                        } else {
                            Log.i("MqttManager", "Connected to MQTT broker")
                            _isConnected = true
                            reconnectDelayMs = 1000L
                            HaDiscoveryManager(this@MqttManager).sendDiscoveryConfigs()
                        }
                    }
            } catch (e: Exception) {
                Log.e("MqttManager", "Connect error", e)
                _isConnected = false
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i("MqttManager", "Reconnecting in ${reconnectDelayMs}ms...")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60000L)
            connect()
        }
    }

    fun publishPersonCount(count: Int, detections: List<BoundingBox> = emptyList()) {
        if (!_isConnected) {
            if (reconnectJob?.isActive != true) {
                scheduleReconnect()
            }
            return
        }

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
                    ?.whenComplete { _, throwable ->
                        if (throwable != null) {
                            Log.e("MqttManager", "Publish failed", throwable)
                            _isConnected = false
                            scheduleReconnect()
                        }
                    }
            } catch (e: Exception) {
                Log.e("MqttManager", "Publish error", e)
                _isConnected = false
                scheduleReconnect()
            }
        }
    }

    fun publishRaw(topicOverride: String, payload: String, qos: MqttQos = MqttQos.AT_LEAST_ONCE) {
        if (!_isConnected) return
        scope.launch {
            try {
                mqttClient?.publishWith()
                    ?.topic(topicOverride)
                    ?.payload(payload.toByteArray())
                    ?.qos(qos)
                    ?.send()
            } catch (e: Exception) {
                Log.e("MqttManager", "Raw publish error", e)
            }
        }
    }

    fun publishEmptyDetection() {
        publishPersonCount(0, emptyList())
    }

    private fun disconnectInternal() {
        try {
            mqttClient?.disconnect()
        } catch (_: Exception) {
        }
        _isConnected = false
    }

    fun disconnect() {
        reconnectJob?.cancel()
        disconnectInternal()
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
