package com.example.cameradetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameradetect.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), YoloDetector.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloDetector: YoloDetector
    private lateinit var mqttManager: MqttManager

    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isDetecting = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "CameraDetect"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        yoloDetector = YoloDetector(
            context = this,
            modelPath = "yolov8n.tflite",
            labelPath = "labels.txt",
            detectorListener = this
        )

        mqttManager = MqttManager(this)
        loadMqttSettings()

        setupUI()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnToggleDetection.setOnClickListener {
            isDetecting = !isDetecting
            binding.btnToggleDetection.text = if (isDetecting) "停止检测" else "开始检测"
            if (isDetecting) {
                Toast.makeText(this, "开始人员检测", Toast.LENGTH_SHORT).show()
            } else {
                binding.overlayView.clear()
                binding.tvPersonCount.text = "人数: 0"
                binding.tvInferenceTime.text = ""
            }
        }
    }

    private fun loadMqttSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val broker = prefs.getString("mqtt_broker", "192.168.1.100") ?: "192.168.1.100"
        val port = prefs.getString("mqtt_port", "1883") ?: "1883"
        mqttManager.brokerHost = broker
        mqttManager.brokerPort = port.toIntOrNull() ?: 1883
        mqttManager.topic = prefs.getString("mqtt_topic", "home/camera/person_count") ?: "home/camera/person_count"
        mqttManager.username = prefs.getString("mqtt_username", "") ?: ""
        mqttManager.password = prefs.getString("mqtt_password", "") ?: ""
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isDetecting) {
                            processImage(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = toBitmap(imageProxy)
        yoloDetector.detect(bitmap)
        imageProxy.close()
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val pixelStride = imageProxy.planes[0].pixelStride
        val rowStride = imageProxy.planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlayView.clear()
            binding.tvPersonCount.text = "人数: 0"
            binding.tvInferenceTime.text = ""
        }
        mqttManager.publishEmptyDetection()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val personCount = boundingBoxes.size

        runOnUiThread {
            binding.tvPersonCount.text = "人数: $personCount"
            binding.tvInferenceTime.text = "推理: ${inferenceTime}ms"
            binding.overlayView.setResults(boundingBoxes)
        }

        mqttManager.publishPersonCount(personCount, boundingBoxes)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadMqttSettings()
        mqttManager.connect()
        yoloDetector.setup()
    }

    override fun onPause() {
        super.onPause()
        isDetecting = false
        binding.btnToggleDetection.text = "开始检测"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        yoloDetector.close()
        mqttManager.cleanup()
        scope.cancel()
    }
}
