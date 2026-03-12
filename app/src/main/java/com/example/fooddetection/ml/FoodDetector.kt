package com.example.fooddetection.ml

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.fooddetection.data.Detection
import com.example.fooddetection.data.DetectionResult
import com.example.fooddetection.data.SnappedResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.io.BufferedReader
import java.io.InputStreamReader

class FoodDetector(
    private val context: Context,
    private val modelPath: String,
    var threshold: Float = THRESHOLD_DEFAULT,
    var maxResults: Int = MAX_RESULTS_DEFAULT,
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    private val _detectionResult = MutableSharedFlow<DetectionResult>(extraBufferCapacity = 1)
    val detectionResult: SharedFlow<DetectionResult> = _detectionResult

    private val _snappedResult = MutableSharedFlow<SnappedResult>(extraBufferCapacity = 1)
    val snappedResult: SharedFlow<SnappedResult> = _snappedResult

    var isSnapping: Boolean = false

    private val detectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val imageChannel = Channel<ImageProxy>(capacity = Channel.CONFLATED) { it.close() }

    init {
        detectorScope.launch {
            Log.d(TAG, "Detector worker loop started")
            for (imageProxy in imageChannel) {
                processImageProxy(imageProxy)
            }
        }
    }

    suspend fun setup() {
        withContext(Dispatchers.IO) {
            try {
                val litertBuffer = FileUtil.loadMappedFile(context, modelPath)
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseXNNPACK(true)
                }
                interpreter = Interpreter(litertBuffer, options)
                labels = loadLabelsFromYaml()
                Log.i(TAG, "Successfully initialized model: $modelPath")
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
            }
        }
    }

    private fun loadLabelsFromYaml(): List<String> {
        val labelMap = mutableMapOf<Int, String>()
        try {
            context.assets.open("metadata.yaml").use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                var inNamesBlock = false
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: ""
                    if (trimmed == "names:") {
                        inNamesBlock = true
                        continue
                    }
                    if (inNamesBlock) {
                        val match = YAML_NAME_REGEX.find(trimmed)
                        if (match != null) {
                            labelMap[match.groupValues[1].toInt()] = match.groupValues[2]
                        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !line!!.startsWith(" ")) {
                            inNamesBlock = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing metadata.yaml", e)
        }
        return labelMap.entries.sortedBy { it.key }.map { it.value }
    }

    fun detect(imageProxy: ImageProxy) {
        if (interpreter == null) {
            imageProxy.close()
            return
        }
        val result = imageChannel.trySend(imageProxy)
        if (!result.isSuccess) {
            imageProxy.close()
        }
    }

    private suspend fun processImageProxy(imageProxy: ImageProxy) {
        val currInterpreter = interpreter ?: run {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees



            val inputTensor = currInterpreter.getInputTensor(0)
            val h = inputTensor.shape()[1]
            val w = inputTensor.shape()[2]

            val tensorImage = TensorImage(inputTensor.dataType())
            tensorImage.load(bitmap)

            val rotation = -rotationDegrees / 90
            val rotatedWidth = if (rotation % 2 != 0) bitmap.height else bitmap.width
            val rotatedHeight = if (rotation % 2 != 0) bitmap.width else bitmap.height
            val cropSize = minOf(rotatedWidth, rotatedHeight)

            val processor = ImageProcessor.Builder()
                .add(Rot90Op(rotation))
                .add(ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(ResizeOp(h, w, ResizeOp.ResizeMethod.BILINEAR))
                .apply {
                    if (inputTensor.dataType() == DataType.FLOAT32) {
                        add(NormalizeOp(0f, 255f))
                    }
                }
                .build()

            val processedImage = processor.process(tensorImage)

            val output = Array(1) { Array(300) { FloatArray(6) } }

            val startTime = SystemClock.uptimeMillis()
            currInterpreter.run(processedImage.buffer, output)

            val inferenceTime = SystemClock.uptimeMillis() - startTime
            val detections = getDetections(output[0])

            val result = DetectionResult(
                detections = detections,
                inferenceTime = inferenceTime,
                inputImageWidth = w,
                inputImageHeight = h,
            )

            // Emit live results
            _detectionResult.emit(result)

            // Search-for-detection logic: If snapping is active and we found objects, "snap" this frame.
            if (isSnapping && detections.isNotEmpty()) {
                isSnapping = false // Stop searching
                _snappedResult.emit(SnappedResult(tensorImage.bitmap, result))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection loop error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun getDetections(results: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in 0 until minOf(300, results.size)) {
            val res = results[i]
            val score = res[4]
            if (score >= threshold) {
                val label = labels.getOrElse(res[5].toInt()) { "Unknown" }
                detections.add(Detection(label, RectF(res[0], res[1], res[2], res[3]), score))
            }
        }
        return detections.sortedByDescending { it.score }.take(maxResults)
    }

    fun close() {
        detectorScope.cancel()
        interpreter?.close()
        imageChannel.close()
    }

    companion object {
        const val MAX_RESULTS_DEFAULT = 5
        const val THRESHOLD_DEFAULT = 0.45F
        const val TAG = "FoodDetector"
        private val YAML_NAME_REGEX = Regex("^(\\d+):\\s*['\"]?(.*?)['\"]?$")
    }
}
