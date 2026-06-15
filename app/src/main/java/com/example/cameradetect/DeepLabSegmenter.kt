package com.example.cameradetect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class DeepLabSegmenter(
    private val context: Context,
    private val modelPath: String,
    private val detectorListener: YoloDetector.DetectorListener,
    private val maskCallback: ((Array<IntArray>) -> Unit)? = null
) {

    private var interpreter: Interpreter? = null
    private var inputSize = 257
    private var outputSize = 257
    private var numClasses = 21
    private val personClassId = 15

    fun setup() {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(model, options)

            val inputShape = interpreter?.getInputTensor(0)?.shape()
            if (inputShape != null) {
                inputSize = inputShape[1]
            }

            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.i("DeepLab", "inputShape=${inputShape?.contentToString()}, outputShape=${outputShape?.contentToString()}, outputType=${interpreter?.getOutputTensor(0)?.dataType()}")

            if (outputShape != null) {
                outputSize = outputShape[1]
                if (outputShape.size == 4) {
                    numClasses = outputShape[3]
                } else if (outputShape.size == 3) {
                    numClasses = outputShape[2]
                }
            }
            Log.i("DeepLab", "inputSize=$inputSize, outputSize=$outputSize, numClasses=$numClasses")
        } catch (e: Exception) {
            Log.e("DeepLab", "Failed to setup DeepLab model", e)
        }
    }

    fun detect(frame: Bitmap) {
        try {
            interpreter ?: return

            val startTime = SystemClock.uptimeMillis()

            val resized = Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToBuffer(resized)
            resized.recycle()

            val outputBuffer = ByteBuffer.allocateDirect(outputSize * outputSize * numClasses * 4)
            outputBuffer.order(ByteOrder.nativeOrder())

            interpreter?.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val mask = argmaxToMask(outputBuffer)
            val boundingBoxes = extractPersonBoxes(mask)
            val inferenceTime = SystemClock.uptimeMillis() - startTime

            maskCallback?.invoke(mask)

            if (boundingBoxes.isEmpty()) {
                detectorListener.onEmptyDetect()
                return
            }

            detectorListener.onDetect(boundingBoxes, inferenceTime)
        } catch (e: Exception) {
            Log.e("DeepLab", "Detection error", e)
        }
    }

    private fun convertBitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val color = pixels[pixel++]
                byteBuffer.putFloat(((color shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((color shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((color and 0xFF) / 255.0f)
            }
        }

        return byteBuffer
    }

    private fun argmaxToMask(buffer: ByteBuffer): Array<IntArray> {
        val mask = Array(outputSize) { IntArray(outputSize) }

        for (y in 0 until outputSize) {
            for (x in 0 until outputSize) {
                var maxIdx = 0
                var maxVal = buffer.getFloat()
                for (c in 1 until numClasses) {
                    val v = buffer.getFloat()
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                mask[y][x] = maxIdx
            }
        }

        return mask
    }

    private fun extractPersonBoxes(mask: Array<IntArray>): List<BoundingBox> {
        val visited = Array(outputSize) { BooleanArray(outputSize) }
        val boxes = mutableListOf<BoundingBox>()

        for (y in 0 until outputSize) {
            for (x in 0 until outputSize) {
                if (mask[y][x] == personClassId && !visited[y][x]) {
                    val (minX, minY, maxX, maxY) = floodFill(mask, visited, x, y)
                    val w = (maxX - minX).toFloat() / outputSize
                    val h = (maxY - minY).toFloat() / outputSize
                    val cx = (minX + maxX).toFloat() / 2f / outputSize
                    val cy = (minY + maxY).toFloat() / 2f / outputSize

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
                if (nx in 0 until outputSize && ny in 0 until outputSize
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
