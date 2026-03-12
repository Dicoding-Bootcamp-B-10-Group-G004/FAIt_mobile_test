package com.example.fooddetection.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.example.fooddetection.data.Detection

@Composable
fun DetectionOverlay(detections: List<Detection>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenWidth = size.width
        val screenHeight = size.height

        detections.forEach { detection ->
            val left = detection.boundingBox.left * screenWidth
            val top = detection.boundingBox.top * screenHeight
            val right = detection.boundingBox.right * screenWidth
            val bottom = detection.boundingBox.bottom * screenHeight

            drawRect(
                color = Color.Red,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                "${detection.label} (${String.format("%.2f", detection.score)})",
                left,
                top - 10f,
                Paint().apply {
                    color = android.graphics.Color.RED
                    textSize = 40f
                    isFakeBoldText = true
                }
            )
        }
    }
}
