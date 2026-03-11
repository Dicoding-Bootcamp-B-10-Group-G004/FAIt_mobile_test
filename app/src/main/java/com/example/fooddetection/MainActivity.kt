package com.example.fooddetection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.fooddetection.ui.theme.FoodDetectionTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoodDetectionTheme {
                ObjectDetectionScreen()
            }
        }
    }
}

@Composable
fun ObjectDetectionScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var selectedModel by remember { mutableStateOf("yolo26n.tflite") }
    var detections by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var inferenceTime by remember { mutableLongStateOf(0L) }
    
    val foodDetector = remember(selectedModel) {
        FoodDetector(context, selectedModel)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            ControlPanel(
                selectedModel = selectedModel,
                onModelSelected = { selectedModel = it },
                inferenceTime = inferenceTime
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (hasCameraPermission) {
                CameraWithOverlay(
                    foodDetector = foodDetector,
                    onDetectionsUpdated = { res, time ->
                        detections = res
                        inferenceTime = time
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
fun CameraWithOverlay(
    foodDetector: FoodDetector,
    onDetectionsUpdated: (List<DetectionResult>, Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var currentDetections by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val startTime = System.currentTimeMillis()
                            val results = foodDetector.detect(bitmap)
                            val endTime = System.currentTimeMillis()

                            currentDetections = results
                            onDetectionsUpdated(results, endTime - startTime)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Detection error", e)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                        Log.e("MainActivity", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Overlay for bounding boxes
        DetectionOverlay(detections = currentDetections)
    }
}

@Composable
fun DetectionOverlay(detections: List<DetectionResult>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenWidth = size.width
        val screenHeight = size.height

        detections.forEach { detection ->
            // Assuming FoodDetector returns normalized coordinates (0..1)
            val left = detection.boundingBox.left * screenWidth
            val top = detection.boundingBox.top * screenHeight
            val right = detection.boundingBox.right * screenWidth
            val bottom = detection.boundingBox.bottom * screenHeight

            drawRect(
                color = Color.Red,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = 4f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                "Class ${detection.classIndex} (${String.format("%.2f", detection.score)})",
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

@Composable
fun ControlPanel(
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    inferenceTime: Long
) {
    var isExpanded by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (isExpanded) {
                InfoRow(label = "Inference Time", value = "${inferenceTime}ms")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ML Model",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        Row(
                            modifier = Modifier.clickable { menuExpanded = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedModel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("yolo26n_float32.tflite") },
                                onClick = {
                                    onModelSelected("yolo26n_float32.tflite")
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("yolo26n_int8.tflite") },
                                onClick = {
                                    onModelSelected("yolo26n_int8.tflite")
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
