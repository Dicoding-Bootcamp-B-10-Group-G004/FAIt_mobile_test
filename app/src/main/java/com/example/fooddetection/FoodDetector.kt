package com.example.fooddetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import androidx.core.graphics.scale

data class DetectionResult(
    val boundingBox: RectF,
    val score: Float,
    val classIndex: Int
)

class FoodDetector(private val context: Context, private val modelPath: String) {
    private var interpreter: Interpreter? = null
    private var inputSize = 0

    // Model input details
    private var inputDataType: DataType = DataType.FLOAT32
    private var inputScale = 0f
    private var inputZeroPoint = 0

    // Model output details
    private var outputIndex = 0

    init {
        try {
            val model = loadModelFile()
            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)

            val inputTensor = interpreter!!.getInputTensor(0)
            val shape = inputTensor.shape() // [1, size, size, 3]
            inputSize = shape[1]
            inputDataType = inputTensor.dataType()

            // Check quantization
            if (inputDataType == DataType.INT8 || inputDataType == DataType.UINT8) {
                val quantization = inputTensor.quantizationParams()
                inputScale = quantization.scale
                inputZeroPoint = quantization.zeroPoint
            }

            val outputTensor = interpreter!!.getOutputTensor(0)
            outputIndex = 0 // Assuming single output

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) return emptyList()

        // Preprocess image
        val byteBuffer = convertBitmapToByteBuffer(bitmap)

        // Output buffer [1, 300, 6]
        // 300 detections, each has 6 values: [x1, y1, x2, y2, score, class]
        val output = Array(1) { Array(300) { FloatArray(6) } }

        interpreter!!.run(byteBuffer, output)

        val detections = mutableListOf<DetectionResult>()
        val outputArray = output[0]

        for (detection in outputArray) {
            // detection: [x1, y1, x2, y2, score, class]
            val score = detection[4]
            if (score > 0.25f) {
                val x1 = detection[0]
                val y1 = detection[1]
                val x2 = detection[2]
                val y2 = detection[3]
                val classIdx = detection[5]

                // x1, y1, x2, y2 are normalized 0..1 in Yolo usually, but user script says:
                // left = x1 * original_size[0] -> so yes, they are normalized.

                detections.add(
                    DetectionResult(
                        boundingBox = RectF(x1, y1, x2, y2),
                        score = score,
                        classIndex = classIdx.toInt()
                    )
                )
            }
        }

        return detections
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = bitmap.scale(inputSize, inputSize)

        val bufferSize = if (inputDataType == DataType.FLOAT32) {
            4 * inputSize * inputSize * 3
        } else {
            1 * inputSize * inputSize * 3
        }

        val byteBuffer = ByteBuffer.allocateDirect(bufferSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]
                val r = (value shr 16 and 0xFF)
                val g = (value shr 8 and 0xFF)
                val b = (value and 0xFF)

                if (inputDataType == DataType.FLOAT32) {
                    byteBuffer.putFloat(r / 255.0f)
                    byteBuffer.putFloat(g / 255.0f)
                    byteBuffer.putFloat(b / 255.0f)
                } else if (inputDataType == DataType.INT8) {
                    // (val / 255.0 / scale + zeroPoint)
                    // Note: r is 0..255.
                    val rNormalized = (r / 255.0f / inputScale + inputZeroPoint)
                    val gNormalized = (g / 255.0f / inputScale + inputZeroPoint)
                    val bNormalized = (b / 255.0f / inputScale + inputZeroPoint)

                    byteBuffer.put(rNormalized.toInt().toByte())
                    byteBuffer.put(gNormalized.toInt().toByte())
                    byteBuffer.put(bNormalized.toInt().toByte())
                } else if (inputDataType == DataType.UINT8) {
                    // Usually just pixel value if quantized to [0, 255]
                    // But strictly speaking: (val / scale + zeroPoint) is for dequantizing.
                    // Quantizing: value / scale + zeroPoint
                    // If input is UINT8, assume range 0..255.
                    byteBuffer.put((r).toByte())
                    byteBuffer.put((g).toByte())
                    byteBuffer.put((b).toByte())
                }
            }
        }
        return byteBuffer
    }
}