package com.jeeva.adshield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jeeva.adshield.ui.home.HomeScreen
import com.jeeva.adshield.ui.theme.AdShieldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdShieldTheme {
                HomeScreen()
            }
        }
    }
}
