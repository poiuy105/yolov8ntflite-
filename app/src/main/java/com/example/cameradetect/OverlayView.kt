package com.example.cameradetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var boxPaint = Paint()
    private var textPaint = Paint()

    init {
        initPaints()
    }

    fun clear() {
        results = emptyList()
        invalidate()
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
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
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

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
}
