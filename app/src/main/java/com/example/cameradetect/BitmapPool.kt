package com.example.cameradetect

import android.graphics.Bitmap
import java.util.ArrayDeque

class BitmapPool(private val width: Int, private val height: Int, private val config: Bitmap.Config) {
    private val pool = ArrayDeque<Bitmap>(3)
    private val maxSize = 3

    init {
        for (i in 0 until maxSize) {
            pool.addLast(Bitmap.createBitmap(width, height, config))
        }
    }

    @Synchronized
    fun acquire(): Bitmap {
        return if (pool.isNotEmpty()) {
            pool.removeFirst()
        } else {
            Bitmap.createBitmap(width, height, config)
        }
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        if (pool.size < maxSize && bitmap.width == width && bitmap.height == height && bitmap.config == config) {
            bitmap.eraseColor(0)
            pool.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        pool.forEach { it.recycle() }
        pool.clear()
    }
}
