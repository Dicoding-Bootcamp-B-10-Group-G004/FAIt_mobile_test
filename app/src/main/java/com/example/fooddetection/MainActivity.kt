package com.example.fooddetection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.fooddetection.ui.screens.ObjectDetectionScreen
import com.example.fooddetection.ui.theme.FoodDetectionTheme

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
