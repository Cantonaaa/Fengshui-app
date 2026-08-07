package com.fengshui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fengshui.app.ui.theme.FengShuiAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 加载生辰/北向 + 八卦数据
        AppState.load(this)
        AppState.baguaJson = assets.open("bagua_data.json").bufferedReader().use { it.readText() }
        AppState.refreshGua()
        setContent {
            FengShuiAppTheme {
                var screen by remember { mutableStateOf("home") }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "scan" -> ScanScreen(
                            onBack = { screen = "home" },
                            onAnalyze = { screen = "report" }
                        )
                        "birth" -> BirthSetupScreen(onDone = { screen = "home" })
                        "report" -> ReportScreen(onBack = { screen = "home" })
                        else -> HomeScreen(
                            onStartScan = { screen = "scan" },
                            onSetBirth = { screen = "birth" },
                            onViewReport = { screen = "report" }
                        )
                    }
                }
            }
        }
    }
}
