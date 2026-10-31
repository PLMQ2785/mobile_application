package com.example.safetymonitor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safetymonitor.ui.MainScreen
import com.example.safetymonitor.ui.WorkSafeTheme
import com.example.safetymonitor.ui.WorkSafeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: WorkSafeViewModel = viewModel()
            val darkTheme = model.darkTheme
            SideEffect {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            WorkSafeTheme(darkTheme) {
                MainScreen(model)
            }
        }
    }
}
