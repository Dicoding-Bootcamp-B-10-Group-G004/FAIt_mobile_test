package com.example.fooddetection.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.fooddetection.data.Detection
import com.example.fooddetection.ml.FoodDetector
import com.example.fooddetection.ui.components.ControlPanel
import com.example.fooddetection.ui.components.DetectionOverlay
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.Executors

@Composable
fun ObjectDetectionScreen() {
    val context = LocalContext.current
    
    val availableModels = remember {
        context.assets.list("")?.filter { it.endsWith(".tflite") } ?: emptyList()
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var selectedModelPath by remember { 
        val defaultModel = "best_float32.tflite"
        val initialModel = if (availableModels.contains(defaultModel)) defaultModel else availableModels.firstOrNull() ?: defaultModel
        mutableStateOf(initialModel)
    }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var inferenceTime by remember { mutableLongStateOf(0L) }
    
    val foodDetector = remember(selectedModelPath) {
        FoodDetector(context, selectedModelPath)
    }

    DisposableEffect(foodDetector) {
        onDispose { foodDetector.close() }
    }

    LaunchedEffect(foodDetector) {
        foodDetector.setup()
        foodDetector.detectionResult.collectLatest { result ->
            detections = result.detections
            inferenceTime = result.inferenceTime
        }
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
                selectedModelPath = selectedModelPath,
                availableModels = availableModels,
                onModelSelected = { selectedModelPath = it },
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
                    detections = detections
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
    detections: List<Detection>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
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
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        foodDetector.detect(imageProxy)
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
                        Log.e("ObjectDetectionScreen", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        DetectionOverlay(detections = detections)
    }
}
