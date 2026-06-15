package com.example.cameradetect

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class AmbientLightManager(
    private val context: Context,
    private val onLightLevelChanged: (LightLevel) -> Unit
) {

    enum class LightLevel {
        BRIGHT, DIM, DARK
    }

    var currentLevel = LightLevel.BRIGHT
        private set

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null

    private val DIM_THRESHOLD = 50f
    private val DARK_THRESHOLD = 10f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                val lux = it.values[0]
                val newLevel = when {
                    lux < DARK_THRESHOLD -> LightLevel.DARK
                    lux < DIM_THRESHOLD -> LightLevel.DIM
                    else -> LightLevel.BRIGHT
                }
                if (newLevel != currentLevel) {
                    currentLevel = newLevel
                    Log.i("AmbientLightManager", "Light level changed to $currentLevel ($lux lux)")
                    onLightLevelChanged(currentLevel)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        lightSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(sensorListener)
    }

    fun getStatusText(): String {
        return when (currentLevel) {
            LightLevel.BRIGHT -> "光线充足"
            LightLevel.DIM -> "光线较暗，已降频"
            LightLevel.DARK -> "夜间模式，检测已暂停"
        }
    }
}
