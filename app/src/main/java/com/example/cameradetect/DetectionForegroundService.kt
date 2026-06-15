package com.example.cameradetect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionForegroundService : LifecycleService(), YoloDetector.DetectorListener {

    companion object {
        const val CHANNEL_ID = "camera_detect_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.cameradetect.START"
        const val ACTION_STOP = "com.example.cameradetect.STOP"
        private const val TAG = "DetectionService"
    }

    private val binder = LocalBinder()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloDetector: YoloDetector
    private lateinit var mqttManager: MqttManager
    private lateinit var bitmapPool: BitmapPool
    private lateinit var throttler: InferenceThrottler
    private lateinit var batteryMonitor: BatteryMonitor

    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isDetecting = false
    private var currentPersonCount = 0
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var serviceStartTime = 0L

    private var previewSurfaceProvider: Preview.SurfaceProvider? = null

    inner class LocalBinder : Binder() {
        fun getService(): DetectionForegroundService = this@DetectionForegroundService
        fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            updatePreview()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        yoloDetector = YoloDetector(this, "yolov8n.tflite", "labels.txt", this)
        mqttManager = MqttManager(this)
        bitmapPool = BitmapPool(640, 480, Bitmap.Config.ARGB_8888)
        throttler = InferenceThrottler(500L)
        batteryMonitor = BatteryMonitor(this)

        loadSettings()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopDetection()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startDetection()
                val notification = buildNotification("正在初始化...", "人数: 0")
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun startDetection() {
        if (isDetecting) return
        isDetecting = true
        serviceStartTime = System.currentTimeMillis()

        yoloDetector.setup()
        mqttManager.connect()
        batteryMonitor.start()
        startCamera()
        startHeartbeat()

        Log.i(TAG, "Detection started")
    }

    private fun stopDetection() {
        isDetecting = false
        heartbeatJob?.cancel()
        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        yoloDetector.close()
        mqttManager.disconnect()
        batteryMonitor.stop()
        bitmapPool.clear()
        Log.i(TAG, "Detection stopped")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                previewSurfaceProvider?.let { provider ->
                    it.setSurfaceProvider(provider)
                }
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isDetecting && throttler.shouldProcess()) {
                            processImage(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updatePreview() {
        previewSurfaceProvider?.let { provider ->
            cameraProvider?.let { provider2 ->
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(provider)
                }
                try {
                    provider2.unbindAll()
                    provider2.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = toBitmap(imageProxy)
        yoloDetector.detect(bitmap)
        bitmapPool.release(bitmap)
        imageProxy.close()
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val pixelStride = imageProxy.planes[0].pixelStride
        val rowStride = imageProxy.planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = bitmapPool.acquire()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && isDetecting) {
                delay(30000L)
                sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        val uptime = (System.currentTimeMillis() - serviceStartTime) / 1000
        val json = JSONObject().apply {
            put("device_id", mqttManager.clientId)
            put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date()))
            put("status", "online")
            put("uptime_seconds", uptime)
            put("battery_level", batteryMonitor.batteryLevel)
            put("battery_temperature", batteryMonitor.batteryTemperature)
            put("is_charging", batteryMonitor.isCharging)
            put("inference_fps", 1000L / throttler.intervalMs.coerceAtLeast(1))
            put("last_detection_count", currentPersonCount)
            put("mqtt_connected", mqttManager.isConnected)
            put("app_version", "1.1.0")
        }
        mqttManager.publishRaw("home/camera/device_status", json.toString())
    }

    override fun onEmptyDetect() {
        currentPersonCount = 0
        mqttManager.publishEmptyDetection()
        updateNotification("运行中", "人数: 0")
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        currentPersonCount = boundingBoxes.size
        mqttManager.publishPersonCount(currentPersonCount, boundingBoxes)
        updateNotification("运行中", "人数: $currentPersonCount | 推理: ${inferenceTime}ms")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "人员检测服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持人员检测后台运行"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, DetectionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        mqttManager.brokerHost = prefs.getString("mqtt_broker", "192.168.1.100") ?: "192.168.1.100"
        mqttManager.brokerPort = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        mqttManager.topic = prefs.getString("mqtt_topic", "home/camera/person_count") ?: "home/camera/person_count"
        mqttManager.username = prefs.getString("mqtt_username", "") ?: ""
        mqttManager.password = prefs.getString("mqtt_password", "") ?: ""

        val fps = prefs.getString("inference_fps", "2")?.toIntOrNull() ?: 2
        throttler.intervalMs = 1000L / fps
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraExecutor.shutdown()
        serviceScope.cancel()
    }
}
