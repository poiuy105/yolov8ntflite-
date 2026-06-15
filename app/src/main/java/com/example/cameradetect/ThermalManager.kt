package com.example.cameradetect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class ThermalManager(
    private val context: Context,
    private val batteryMonitor: BatteryMonitor,
    private val onThermalStateChanged: (ThermalState) -> Unit
) {

    enum class ThermalState {
        NORMAL, WARNING, CRITICAL
    }

    var currentState = ThermalState.NORMAL
        private set

    private var checkJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val WARNING_TEMP = 40.0f
    private val CRITICAL_TEMP = 45.0f
    private val RECOVERY_TEMP = 38.0f

    fun start() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                checkTemperature()
                delay(10000L)
            }
        }
    }

    fun stop() {
        checkJob?.cancel()
    }

    private fun checkTemperature() {
        val batteryTemp = batteryMonitor.batteryTemperature
        val cpuTemp = readCpuTemperature()
        val maxTemp = maxOf(batteryTemp, cpuTemp)

        val newState = when {
            maxTemp >= CRITICAL_TEMP -> ThermalState.CRITICAL
            maxTemp >= WARNING_TEMP -> ThermalState.WARNING
            currentState == ThermalState.CRITICAL && maxTemp < RECOVERY_TEMP -> ThermalState.NORMAL
            currentState == ThermalState.WARNING && maxTemp < RECOVERY_TEMP -> ThermalState.NORMAL
            else -> currentState
        }

        if (newState != currentState) {
            currentState = newState
            Log.i("ThermalManager", "Thermal state changed to $currentState (battery: ${batteryTemp}°C, cpu: ${cpuTemp}°C)")
            onThermalStateChanged(currentState)
        }
    }

    private fun readCpuTemperature(): Float {
        return try {
            val thermalZonePaths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp"
            )

            var maxTemp = 0f
            for (path in thermalZonePaths) {
                val file = File(path)
                if (file.exists()) {
                    val tempStr = file.readText().trim()
                    val temp = tempStr.toFloat() / 1000f
                    if (temp > maxTemp) {
                        maxTemp = temp
                    }
                }
            }
            maxTemp
        } catch (e: Exception) {
            Log.w("ThermalManager", "Failed to read CPU temperature", e)
            0f
        }
    }

    fun getStatusText(): String {
        return when (currentState) {
            ThermalState.NORMAL -> "温度正常"
            ThermalState.WARNING -> "设备过热，已降频"
            ThermalState.CRITICAL -> "设备过热，检测已暂停"
        }
    }
}
