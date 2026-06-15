package com.example.cameradetect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryMonitor(private val context: Context) {

    var batteryLevel: Int = -1
        private set
    var batteryTemperature: Float = 0f
        private set
    var isCharging: Boolean = false
        private set
    var voltage: Int = 0
        private set

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryLevel = if (level >= 0) (level * 100 / scale) else -1
                val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                batteryTemperature = temp / 10.0f
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(batteryReceiver, filter)
        batteryStatus?.let { batteryReceiver.onReceive(context, it) }
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
