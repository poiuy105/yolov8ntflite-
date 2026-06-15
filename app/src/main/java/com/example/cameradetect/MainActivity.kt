package com.example.cameradetect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.cameradetect.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), DetectionForegroundService.DetectionCallback {

    private lateinit var binding: ActivityMainBinding
    private var detectionService: DetectionForegroundService? = null
    private var serviceBinder: DetectionForegroundService.LocalBinder? = null
    private var serviceBound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logAdapter = DetectionLogAdapter()
    private var isLogExpanded = false
    private var isScreenDimmed = false

    companion object {
        private const val TAG = "CameraDetect"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private val dimScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.cameradetect.DIM_SCREEN") {
                dimScreen()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DetectionForegroundService.LocalBinder
            serviceBinder = binder
            detectionService = binder.getService()
            serviceBound = true
            binder.setPreviewSurfaceProvider(binding.viewFinder.surfaceProvider)
            binder.setDetectionCallback(this@MainActivity)
            updateUIState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            detectionService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(dimScreenReceiver, IntentFilter("com.example.cameradetect.DIM_SCREEN"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dimScreenReceiver, IntentFilter("com.example.cameradetect.DIM_SCREEN"))
        }

        if (allPermissionsGranted()) {
            bindToService()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (isScreenDimmed && event?.action == MotionEvent.ACTION_DOWN) {
            restoreScreenBrightness()
        }
        return super.onTouchEvent(event)
    }

    private fun dimScreen() {
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
        isScreenDimmed = true
    }

    private fun restoreScreenBrightness() {
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        isScreenDimmed = false
    }

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnToggleDetection.setOnClickListener {
            if (isServiceRunning()) {
                stopDetectionService()
            } else {
                startDetectionService()
            }
        }

        binding.btnSwitchModel.setOnClickListener {
            showModelSelectionDialog()
        }

        binding.btnSwitchCamera.setOnClickListener {
            showCameraSelectionDialog()
        }

        binding.logHeader.setOnClickListener {
            toggleLogPanel()
        }

        binding.tvLogToggle.setOnClickListener {
            toggleLogPanel()
        }

        binding.rvLogList.layoutManager = LinearLayoutManager(this)
        binding.rvLogList.adapter = logAdapter
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("YOLOv8n (快速检测)", "SSD MobileNet V2 (省电检测)", "DeepLab V3 (像素分割)")
        val modelFiles = arrayOf("yolov8n.tflite", "ssd_mobilenetv2_fpnlite.tflite", "deeplabv3.tflite")

        val currentModel = serviceBinder?.getCurrentModel() ?: "yolov8n.tflite"
        val selectedIndex = modelFiles.indexOf(currentModel)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择检测模型")
            .setSingleChoiceItems(models, selectedIndex) { dialog, which ->
                val selectedFile = modelFiles[which]
                serviceBinder?.switchModel(selectedFile)
                binding.overlayView.clear()
                binding.tvInferenceTime.text = ""
                dialog.dismiss()
                Toast.makeText(this, "已切换到: ${models[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCameraSelectionDialog() {
        val cameras = CameraEnumerator(this).enumerateCameras()
        if (cameras.isEmpty()) {
            Toast.makeText(this, "未检测到摄像头", Toast.LENGTH_SHORT).show()
            return
        }

        val displayNames = cameras.map { it.getDisplayName() }.toTypedArray()
        val cameraIds = cameras.map { it.cameraId }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择摄像头")
            .setItems(displayNames) { _, which ->
                val selectedId = cameraIds[which]
                serviceBinder?.switchToCamera(selectedId)
                Toast.makeText(this, "已切换到: ${displayNames[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleLogPanel() {
        isLogExpanded = !isLogExpanded
        if (isLogExpanded) {
            binding.rvLogList.visibility = View.VISIBLE
            binding.tvLogToggle.text = "收起 ▼"
        } else {
            binding.rvLogList.visibility = View.GONE
            binding.tvLogToggle.text = "展开 ▲"
        }
    }

    private fun isServiceRunning(): Boolean {
        return detectionService != null
    }

    private fun startDetectionService() {
        val intent = Intent(this, DetectionForegroundService::class.java).apply {
            action = DetectionForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindToService()
        Toast.makeText(this, "检测服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetectionService() {
        val intent = Intent(this, DetectionForegroundService::class.java).apply {
            action = DetectionForegroundService.ACTION_STOP
        }
        startService(intent)
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        serviceBound = false
        serviceBinder = null
        detectionService = null
        updateUIState()
        Toast.makeText(this, "检测服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun bindToService() {
        val intent = Intent(this, DetectionForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUIState() {
        val running = isServiceRunning()
        binding.btnToggleDetection.text = if (running) "停止检测" else "开始检测"
        binding.tvPersonCount.text = if (running) "服务运行中" else "服务已停止"
        binding.tvInferenceTime.text = ""
    }

    override fun onPersonCountChanged(count: Int, timestamp: String) {
        scope.launch {
            binding.tvPersonCount.text = "人数: $count"
            val entry = DetectionLogEntry(timestamp, count)
            logAdapter.addLog(entry)
            val latest = logAdapter.getLatest()
            binding.tvLogSummary.text = latest?.let { "${it.timestamp} 检测到 ${it.personCount} 人" } ?: "检测日志"
        }
    }

    override fun onDetectionResults(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        scope.launch {
            binding.overlayView.setResults(boundingBoxes)
            binding.tvInferenceTime.text = "推理: ${inferenceTime}ms"
        }
    }

    override fun onSegmentationMask(mask: Array<IntArray>?) {
        scope.launch {
            binding.overlayView.setSegmentationMask(mask)
        }
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
                bindToService()
            } else {
                Toast.makeText(this, "需要摄像头和通知权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (serviceBound) {
            updateUIState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            unregisterReceiver(dimScreenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        scope.cancel()
    }
}
