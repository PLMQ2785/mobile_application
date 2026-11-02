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

        // 1. 세 감지기 객체 생성 (팀 아키텍처 명세)
        val fall = FallDetector(applicationContext)
        val vital = VitalDetector()
        val beacon = BeaconDetector()

        // 2. 공통 관리자 (SafetyManager)
        val safetyManager = SafetyManager(
            detectors = listOf(fall, vital, beacon),
            alertSender = HttpAlertSender("http://10.0.2.2:8080/alert")
        )

        // 3. 화면 띄우기
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
