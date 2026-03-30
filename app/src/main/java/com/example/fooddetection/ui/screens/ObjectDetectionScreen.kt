package com.example.fooddetection.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.fooddetection.data.Detection
import com.example.fooddetection.data.SnappedResult
import com.example.fooddetection.ml.FoodDetector
import com.example.fooddetection.ui.components.ControlPanel
import com.example.fooddetection.ui.components.DetectionOverlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class ScreenState {
    Camera, Searching, Result
}

@Composable
fun ObjectDetectionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // GPU selection state
    var useGpu by remember { mutableStateOf(false) }
    val isGpuSupported = remember { FoodDetector.isGpuSupported(context) }
    val isModelQuantized = remember(selectedModelPath) {
        selectedModelPath.contains("int8", ignoreCase = true)
    }

    var screenState by remember { mutableStateOf(ScreenState.Camera) }
    var liveDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var liveInferenceTime by remember { mutableLongStateOf(0L) }
    var snappedResult by remember { mutableStateOf<SnappedResult?>(null) }

    val foodDetector = remember(selectedModelPath) {
        FoodDetector(context, selectedModelPath)
    }

    DisposableEffect(foodDetector) {
        onDispose { foodDetector.close() }
    }

    LaunchedEffect(foodDetector, useGpu) {
        foodDetector.setup(useGpu = useGpu)

        // Observe real-time results
        launch {
            foodDetector.detectionResult.collectLatest { result ->
                if (screenState != ScreenState.Result) {
                    liveDetections = result.detections
                    liveInferenceTime = result.inferenceTime
                }
            }
        }

        // Observe automatic snap results
        launch {
            foodDetector.snappedResult.collectLatest { result ->
                snappedResult = result
                screenState = ScreenState.Result
            }
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
            if (screenState != ScreenState.Result) {
                ControlPanel(
                    selectedModelPath = selectedModelPath,
                    availableModels = availableModels,
                    onModelSelected = { selectedModelPath = it },
                    inferenceTime = liveInferenceTime,
                    useGpu = useGpu,
                    onUseGpuChanged = { useGpu = it },
                    isGpuSupported = isGpuSupported,
                    isModelQuantized = isModelQuantized
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (screenState) {
                ScreenState.Camera, ScreenState.Searching -> {
                    if (hasCameraPermission) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreview(foodDetector = foodDetector)

                            // Real-time overlay
                            DetectionOverlay(detections = liveDetections)

                            // Snap Button - Triggers high-speed background search
                            FloatingActionButton(
                                onClick = {
                                    if (liveDetections.isNotEmpty()) {
                                        screenState = ScreenState.Searching
                                        foodDetector.isSnapping = true
                                    } else {
                                        Toast.makeText(context, "No detections found to snap", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 32.dp),
                                containerColor = if (screenState == ScreenState.Searching)
                                    MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            ) {
                                if (screenState == ScreenState.Searching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Camera, contentDescription = "Search & Snap")
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "Camera Permission Required")
                        }
                    }
                }
                ScreenState.Result -> {
                    snappedResult?.let { result ->
                        DetectionResultScreen(
                            snappedResult = result,
                            onBack = {
                                screenState = ScreenState.Camera
                                snappedResult = null
                                liveDetections = emptyList()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(foodDetector: FoodDetector) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

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
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    foodDetector.detect(imageProxy)
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}
