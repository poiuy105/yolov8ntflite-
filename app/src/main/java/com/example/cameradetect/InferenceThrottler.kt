package com.example.cameradetect

import android.os.SystemClock

class InferenceThrottler(var intervalMs: Long = 500L) {
    private var lastInferenceTime = 0L

    fun shouldProcess(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return if (now - lastInferenceTime >= intervalMs) {
            lastInferenceTime = now
            true
        } else {
            false
        }
    }

    fun reset() {
        lastInferenceTime = 0L
    }
}
