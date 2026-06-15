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
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionForegroundService : Service(), YoloDetector.DetectorListener, LifecycleOwner {

    companion object {
        const val CHANNEL_ID = "camera_detect_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.cameradetect.START"
        const val ACTION_STOP = "com.example.cameradetect.STOP"
        private const val TAG = "DetectionService"
    }

    private val binder = LocalBinder()
    private lateinit var cameraExecutor: ExecutorService
    private var yoloDetector: YoloDetector? = null
    private var ssdDetector: SsdDetector? = null
    private var deepLabSegmenter: DeepLabSegmenter? = null
    private var detectorType = "yolo"
    private lateinit var mqttManager: MqttManager
    private lateinit var bitmapPool: BitmapPool
    private lateinit var throttler: InferenceThrottler
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalManager: ThermalManager
    private lateinit var ambientLightManager: AmbientLightManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isDetecting = false
    private var isPausedByThermal = false
    private var isPausedByLight = false
    private var isPausedByPower = false
    private var currentPersonCount = 0
    private var originalFps = 2
    private var currentModelPath = "yolov8n.tflite"
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var dimScreenJob: Job? = null
    private var serviceStartTime = 0L

    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var isUsingFrontCamera = false
    private var currentCameraId = "0"
    private var detectionCallback: DetectionCallback? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    interface DetectionCallback {
        fun onPersonCountChanged(count: Int, timestamp: String)
        fun onDetectionResults(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    inner class LocalBinder : Binder() {
        fun getService(): DetectionForegroundService = this@DetectionForegroundService
        fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            updatePreview()
        }
        fun switchCamera() = this@DetectionForegroundService.switchCamera()
        fun isFrontCamera(): Boolean = isUsingFrontCamera
        fun setDetectionCallback(callback: DetectionCallback?) {
            detectionCallback = callback
        }
        fun switchModel(modelPath: String) = this@DetectionForegroundService.switchModel(modelPath)
        fun getCurrentModel(): String = currentModelPath
        fun switchToCamera(cameraId: String) = this@DetectionForegroundService.switchToCamera(cameraId)
        fun getCurrentCameraId(): String = currentCameraId
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
        mqttManager = MqttManager(this)
        bitmapPool = BitmapPool(640, 480, Bitmap.Config.ARGB_8888)
        throttler = InferenceThrottler(500L)
        batteryMonitor = BatteryMonitor(this)
        thermalManager = ThermalManager(this, batteryMonitor) { state ->
            onThermalStateChanged(state)
        }
        ambientLightManager = AmbientLightManager(this) { level ->
            onLightLevelChanged(level)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraDetect::WakeLock")

        loadSettings()
        initYoloDetector()
        createNotificationChannel()
    }

    private fun initYoloDetector() {
        yoloDetector?.close()
        ssdDetector?.close()
        deepLabSegmenter?.close()
        yoloDetector = null
        ssdDetector = null
        deepLabSegmenter = null

        detectorType = when {
            currentModelPath.contains("ssd") -> "ssd"
            currentModelPath.contains("deeplab") -> "deeplab"
            else -> "yolo"
        }
        when (detectorType) {
            "ssd" -> {
                ssdDetector = SsdDetector(this, currentModelPath, "labels.txt", this)
                if (isDetecting) ssdDetector?.setup()
            }
            "deeplab" -> {
                deepLabSegmenter = DeepLabSegmenter(this, currentModelPath, this)
                if (isDetecting) deepLabSegmenter?.setup()
            }
            else -> {
                yoloDetector = YoloDetector(this, currentModelPath, "labels.txt", this)
                if (isDetecting) yoloDetector?.setup()
            }
        }
        Log.i(TAG, "Initialized detector: $currentModelPath (type=$detectorType)")
    }

    fun switchModel(modelPath: String) {
        if (modelPath == currentModelPath) return
        currentModelPath = modelPath
        initYoloDetector()
        Log.i(TAG, "Switched to model: $modelPath")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDetection()
                stopForeground(STOP_FOREGROUND_REMOVE)
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
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        yoloDetector?.setup()
        mqttManager.connect()
        batteryMonitor.start()
        thermalManager.start()
        ambientLightManager.start()
        wakeLock.acquire(10*60*1000L)
        startCamera()
        startHeartbeat()
        startPowerMonitoring()
        scheduleDimScreen()

        Log.i(TAG, "Detection started")
    }

    private fun stopDetection() {
        isDetecting = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        heartbeatJob?.cancel()
        dimScreenJob?.cancel()
        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        yoloDetector?.close()
        ssdDetector?.close()
        deepLabSegmenter?.close()
        mqttManager.disconnect()
        batteryMonitor.stop()
        thermalManager.stop()
        ambientLightManager.stop()
        if (wakeLock.isHeld) wakeLock.release()
        bitmapPool.clear()
        Log.i(TAG, "Detection stopped")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
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

        val cameraSelector = CameraEnumerator(this).createCameraSelector(currentCameraId)

        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    fun switchCamera() {
        isUsingFrontCamera = !isUsingFrontCamera
        cameraProvider?.unbindAll()
        bindCameraUseCases()
    }

    fun switchToCamera(cameraId: String) {
        if (cameraId == currentCameraId) return
        currentCameraId = cameraId
        cameraProvider?.unbindAll()
        bindCameraUseCases()
        Log.i(TAG, "Switched to camera: $cameraId")
    }

    private fun updatePreview() {
        previewSurfaceProvider?.let { provider ->
            cameraProvider?.let { provider2 ->
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(provider)
                }
                try {
                    provider2.unbindAll()
                    provider2.bindToLifecycle(
                        this,
                        if (isUsingFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = toBitmap(imageProxy)
        when (detectorType) {
            "ssd" -> ssdDetector?.detect(bitmap)
            "deeplab" -> deepLabSegmenter?.detect(bitmap)
            else -> yoloDetector?.detect(bitmap)
        }
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
        notifyCallback(0, emptyList(), 0)
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        currentPersonCount = boundingBoxes.size
        mqttManager.publishPersonCount(currentPersonCount, boundingBoxes)
        updateNotification("运行中", "人数: $currentPersonCount | 推理: ${inferenceTime}ms")
        notifyCallback(currentPersonCount, boundingBoxes, inferenceTime)
    }

    private fun notifyCallback(count: Int, boxes: List<BoundingBox>, inferenceTime: Long) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        detectionCallback?.onPersonCountChanged(count, timestamp)
        detectionCallback?.onDetectionResults(boxes, inferenceTime)
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

    private fun onThermalStateChanged(state: ThermalManager.ThermalState) {
        when (state) {
            ThermalManager.ThermalState.NORMAL -> {
                isPausedByThermal = false
                restoreOriginalFps()
                updateNotification("运行中", "温度正常 | 人数: $currentPersonCount")
            }
            ThermalManager.ThermalState.WARNING -> {
                isPausedByThermal = false
                setFps(1)
                updateNotification("警告", "${thermalManager.getStatusText()} | 人数: $currentPersonCount")
            }
            ThermalManager.ThermalState.CRITICAL -> {
                isPausedByThermal = true
                updateNotification("过热保护", "${thermalManager.getStatusText()} | 人数: $currentPersonCount")
            }
        }
    }

    private fun onLightLevelChanged(level: AmbientLightManager.LightLevel) {
        when (level) {
            AmbientLightManager.LightLevel.BRIGHT -> {
                isPausedByLight = false
                if (!isPausedByThermal && !isPausedByPower) {
                    restoreOriginalFps()
                }
            }
            AmbientLightManager.LightLevel.DIM -> {
                isPausedByLight = false
                if (!isPausedByThermal) {
                    setFps(1)
                }
            }
            AmbientLightManager.LightLevel.DARK -> {
                isPausedByLight = true
            }
        }
    }

    private fun startPowerMonitoring() {
        serviceScope.launch {
            while (isActive && isDetecting) {
                checkPowerState()
                delay(30000L)
            }
        }
    }

    private fun checkPowerState() {
        if (batteryMonitor.isCharging) {
            if (isPausedByPower) {
                isPausedByPower = false
                if (!isPausedByThermal && !isPausedByLight) {
                    restoreOriginalFps()
                }
            }
        } else {
            when {
                batteryMonitor.batteryLevel <= 20 -> {
                    isPausedByPower = true
                }
                batteryMonitor.batteryLevel <= 50 && !isPausedByThermal -> {
                    isPausedByPower = false
                    setFps(1)
                }
                else -> {
                    if (isPausedByPower) {
                        isPausedByPower = false
                        if (!isPausedByThermal && !isPausedByLight) {
                            restoreOriginalFps()
                        }
                    }
                }
            }
        }
    }

    private fun scheduleDimScreen() {
        dimScreenJob?.cancel()
        dimScreenJob = serviceScope.launch {
            delay(30000L)
            val intent = Intent("com.example.cameradetect.DIM_SCREEN")
            sendBroadcast(intent)
        }
    }

    private fun setFps(fps: Int) {
        throttler.intervalMs = 1000L / fps
    }

    private fun restoreOriginalFps() {
        throttler.intervalMs = 1000L / originalFps
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        mqttManager.brokerHost = prefs.getString("mqtt_broker", "192.168.123.49") ?: "192.168.123.49"
        mqttManager.brokerPort = prefs.getString("mqtt_port", "1883")?.toIntOrNull() ?: 1883
        mqttManager.topic = prefs.getString("mqtt_topic", "home/camera/person_count") ?: "home/camera/person_count"
        mqttManager.username = prefs.getString("mqtt_username", "fnos") ?: "fnos"
        mqttManager.password = prefs.getString("mqtt_password", "fuckfnos") ?: "fuckfnos"

        originalFps = prefs.getString("inference_fps", "2")?.toIntOrNull() ?: 2
        throttler.intervalMs = 1000L / originalFps
        currentModelPath = prefs.getString("model_file", "yolov8n.tflite") ?: "yolov8n.tflite"
        currentCameraId = prefs.getString("camera_id", "0") ?: "0"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraExecutor.shutdown()
        serviceScope.cancel()
    }
}
