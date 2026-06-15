package com.example.cameradetect

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

@OptIn(ExperimentalCamera2Interop::class)
class CameraEnumerator(private val context: Context) {

    data class CameraInfo(
        val cameraId: String,
        val lensFacing: String,
        val focalLength: Float,
        val focalLengths: FloatArray,
        val isFixedFocus: Boolean,
        val hardwareLevel: String
    ) {
        fun getDisplayName(): String {
            val facing = when (lensFacing) {
                "FRONT" -> "前置"
                "BACK" -> "后置"
                else -> "外部"
            }
            val focalInfo = if (focalLengths.size > 1) {
                "变焦 ${focalLength}mm"
            } else {
                "定焦 ${focalLength}mm"
            }
            return "$facing 摄像头 $cameraId ($focalInfo)"
        }
    }

    fun enumerateCameras(): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = mutableListOf<CameraInfo>()

        for (cameraId in cameraManager.cameraIdList) {
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    else -> "EXTERNAL"
                }

                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: floatArrayOf(0f)
                val focalLength = focalLengths.firstOrNull() ?: 0f

                val isFixedFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) == 0f

                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val levelStr = when (hardwareLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "UNKNOWN"
                }

                cameraList.add(CameraInfo(
                    cameraId = cameraId,
                    lensFacing = facingStr,
                    focalLength = focalLength,
                    focalLengths = focalLengths,
                    isFixedFocus = isFixedFocus,
                    hardwareLevel = levelStr
                ))
            } catch (e: Exception) {
                Log.e("CameraEnumerator", "Error reading camera $cameraId", e)
            }
        }

        return cameraList.sortedByDescending { it.focalLength }
    }

    fun createCameraSelector(cameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { cameraInfo ->
                    Camera2CameraInfo.from(cameraInfo).cameraId == cameraId
                }
            }
            .build()
    }
}
