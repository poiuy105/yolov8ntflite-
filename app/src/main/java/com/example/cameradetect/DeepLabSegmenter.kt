package com.example.cameradetect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class DeepLabSegmenter(
    private val context: Context,
    private val modelPath: String,
    private val detectorListener: YoloDetector.DetectorListener,
    private val maskCallback: ((Array<IntArray>) -> Unit)? = null
) {

    private var interpreter: Interpreter? = null
    private val inputSize = 257
    private val numClasses = 21
    private val personClassId = 15

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    fun setup() {
        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()

        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatList.bestOptionsForThisDevice
            options.addDelegate(GpuDelegate(delegateOptions))
        } else {
            options.setNumThreads(4)
        }

        interpreter = Interpreter(model, options)
    }

    fun detect(frame: Bitmap) {
        interpreter ?: return

        val startTime = SystemClock.uptimeMillis()

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(frame)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output = Array(1) { Array(inputSize) { IntArray(inputSize) } }
        interpreter?.run(imageBuffer, output)

        val mask = output[0]
        val boundingBoxes = extractPersonBoxes(mask)
        val inferenceTime = SystemClock.uptimeMillis() - startTime

        maskCallback?.invoke(mask)

        if (boundingBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        detectorListener.onDetect(boundingBoxes, inferenceTime)
    }

    private fun extractPersonBoxes(mask: Array<IntArray>): List<BoundingBox> {
        val visited = Array(inputSize) { BooleanArray(inputSize) }
        val boxes = mutableListOf<BoundingBox>()

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                if (mask[y][x] == personClassId && !visited[y][x]) {
                    val (minX, minY, maxX, maxY) = floodFill(mask, visited, x, y)
                    val w = (maxX - minX).toFloat() / inputSize
                    val h = (maxY - minY).toFloat() / inputSize
                    val cx = (minX + maxX).toFloat() / 2f / inputSize
                    val cy = (minY + maxY).toFloat() / 2f / inputSize

                    if (w > 0.05f && h > 0.05f) {
                        boxes.add(BoundingBox(
                            x1 = cx - w / 2,
                            y1 = cy - h / 2,
                            x2 = cx + w / 2,
                            y2 = cy + h / 2,
                            cx = cx,
                            cy = cy,
                            w = w,
                            h = h,
                            cnf = 0.85f,
                            cls = personClassId,
                            clsName = "person"
                        ))
                    }
                }
            }
        }

        return boxes
    }

    private fun floodFill(mask: Array<IntArray>, visited: Array<BooleanArray>, startX: Int, startY: Int): Quad {
        var minX = startX
        var minY = startY
        var maxX = startX
        var maxY = startY

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(startX to startY)
        visited[startY][startX] = true

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)

            val neighbors = listOf(
                x - 1 to y, x + 1 to y,
                x to y - 1, x to y + 1
            )
            for ((nx, ny) in neighbors) {
                if (nx in 0 until inputSize && ny in 0 until inputSize
                    && !visited[ny][nx] && mask[ny][nx] == personClassId) {
                    visited[ny][nx] = true
                    stack.add(nx to ny)
                }
            }
        }

        return Quad(minX, minY, maxX, maxY)
    }

    data class Quad(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
