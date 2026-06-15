package com.example.cameradetect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SsdDetector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: YoloDetector.DetectorListener
) {

    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()
    private var inputSize = 320

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
            Log.i("SsdDetector", "Model loaded: inputSize=$inputSize, inputType=${interpreter?.getInputTensor(0)?.dataType()}, outputShape=${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")

            try {
                val inputStream: InputStream = context.assets.open(labelPath)
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String? = reader.readLine()
                while (line != null && line != "") {
                    labels.add(line)
                    line = reader.readLine()
                }
                reader.close()
                inputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            Log.e("SsdDetector", "Failed to setup SSD model", e)
        }
    }

    fun detect(frame: Bitmap) {
        try {
            interpreter ?: return

            val startTime = SystemClock.uptimeMillis()

            val resized = Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToBuffer(resized)
            resized.recycle()

            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 100, 7)
            val numDetections = outputShape[1]
            val output = Array(1) { Array(numDetections) { FloatArray(7) } }

            interpreter?.run(inputBuffer, output)

            val bestBoxes = extractBoxes(output[0])
            val inferenceTime = SystemClock.uptimeMillis() - startTime

            if (bestBoxes.isEmpty()) {
                detectorListener.onEmptyDetect()
                return
            }

            detectorListener.onDetect(bestBoxes, inferenceTime)
        } catch (e: Exception) {
            Log.e("SsdDetector", "Detection error", e)
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

    private fun extractBoxes(detections: Array<FloatArray>): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (i in detections.indices) {
            if (i >= detections.size) break

            val ymin = detections[i][0]
            val xmin = detections[i][1]
            val ymax = detections[i][2]
            val xmax = detections[i][3]
            val score = detections[i][4]
            val classId = detections[i][5].toInt()

            if (score < CONFIDENCE_THRESHOLD) continue
            if (classId >= labels.size) continue

            val clsName = labels[classId]
            if (clsName != "person") continue

            val w = xmax - xmin
            val h = ymax - ymin
            val cx = xmin + w / 2f
            val cy = ymin + h / 2f

            boundingBoxes.add(
                BoundingBox(
                    x1 = xmin, y1 = ymin, x2 = xmax, y2 = ymax,
                    cx = cx, cy = cy, w = w, h = h,
                    cnf = score, cls = classId, clsName = clsName
                )
            )
        }

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)

        val intersectionArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h

        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val IOU_THRESHOLD = 0.45f
    }
}
