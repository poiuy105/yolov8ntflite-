package com.example.cameradetect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
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

    companion object {
        private const val TAG = "CameraDetect"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        )
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

        if (allPermissionsGranted()) {
            bindToService()
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
            if (isServiceRunning()) {
                stopDetectionService()
            } else {
                startDetectionService()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            serviceBinder?.let { binder ->
                binder.switchCamera()
                val isFront = binder.isFrontCamera()
                Toast.makeText(this, if (isFront) "已切换到前置摄像头" else "已切换到后置摄像头", Toast.LENGTH_SHORT).show()
            }
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
        scope.cancel()
    }
}
