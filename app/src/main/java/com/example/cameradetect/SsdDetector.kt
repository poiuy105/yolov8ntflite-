package com.example.cameradetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.NormalizeOp
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

class SsdDetector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: DetectorListener
) {

    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()

    private var inputSize = 320

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
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

        val inputShape = interpreter?.getInputTensor(0)?.shape()
        if (inputShape != null) {
            inputSize = inputShape[1]
        }

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
    }

    fun detect(frame: Bitmap) {
        interpreter ?: return

        val startTime = SystemClock.uptimeMillis()

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(frame)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 100, 7)
        val numDetections = outputShape[1]
        val output = Array(1) { Array(numDetections) { FloatArray(7) } }

        interpreter?.run(imageBuffer, output)

        val bestBoxes = extractBoxes(output[0])
        val inferenceTime = SystemClock.uptimeMillis() - startTime

        if (bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    private fun extractBoxes(detections: Array<FloatArray>): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (i in detections.indices) {
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

            if (xmin < 0f || ymin < 0f || xmax > 1f || ymax > 1f) continue

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

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val IOU_THRESHOLD = 0.45f
    }
}
