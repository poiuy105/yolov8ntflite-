package com.example.cameradetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var segmentationMask: Array<IntArray>? = null
    private var maskBitmap: Bitmap? = null
    private var boxPaint = Paint()
    private var textPaint = Paint()
    private var maskPaint = Paint()

    init {
        initPaints()
    }

    fun clear() {
        results = emptyList()
        segmentationMask = null
        maskBitmap?.recycle()
        maskBitmap = null
        invalidate()
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    fun setSegmentationMask(mask: Array<IntArray>?) {
        segmentationMask = mask
        maskBitmap?.recycle()
        maskBitmap = null
        invalidate()
    }

    private fun initPaints() {
        boxPaint.apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }

        textPaint.apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            textSize = 50f
        }

        maskPaint.apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        segmentationMask?.let { mask ->
            drawMask(canvas, mask)
        }

        results.forEach { box ->
            val rect = RectF(
                box.x1 * width,
                box.y1 * height,
                box.x2 * width,
                box.y2 * height
            )

            canvas.drawRect(rect, boxPaint)

            val label = "${box.clsName} ${(box.cnf * 100).toInt()}%"
            canvas.drawText(label, rect.left, rect.top - 10, textPaint)
        }
    }

    private fun drawMask(canvas: Canvas, mask: Array<IntArray>) {
        val maskSize = mask.size
        if (maskSize == 0) return

        if (maskBitmap == null || maskBitmap?.width != width || maskBitmap?.height != height) {
            maskBitmap?.recycle()
            maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val maskCanvas = Canvas(maskBitmap!!)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val cellWidth = width.toFloat() / maskSize
        val cellHeight = height.toFloat() / maskSize

        val personPaint = Paint().apply {
            color = Color.argb(128, 0, 255, 0)
            style = Paint.Style.FILL
        }

        for (y in 0 until maskSize) {
            for (x in 0 until maskSize) {
                if (mask[y][x] == 15) {
                    val left = x * cellWidth
                    val top = y * cellHeight
                    val right = left + cellWidth + 1
                    val bottom = top + cellHeight + 1
                    maskCanvas.drawRect(left, top, right, bottom, personPaint)
                }
            }
        }

        canvas.drawBitmap(maskBitmap!!, 0f, 0f, maskPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        maskBitmap?.recycle()
        maskBitmap = null
    }
}
