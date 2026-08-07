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
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fengshui.app.ui.theme.FengShuiAppTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏：状态栏（时间/电量）与手势条全部隐藏，应用置于最上层；下滑/上滑可临时唤出
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // 加载生辰/北向 + 八卦数据
        AppState.load(this)
        AppState.baguaJson = assets.open("bagua_data.json").bufferedReader().use { it.readText() }
        AppState.refreshGua()
        setContent {
            FengShuiAppTheme {
                var screen by remember { mutableStateOf("home") }
                var historyView by remember { mutableStateOf<HistoryEntry?>(null) }
                val context = LocalContext.current
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "scan" -> ScanScreen(
                            onBack = { screen = "home" },
                            onAnalyze = { screen = "report" }
                        )
                        "birth" -> BirthSetupScreen(onDone = { screen = "home" })
                        "report" -> {
                            val entry = historyView
                            if (entry != null) {
                                ReportScreen(
                                    result = entry.result,
                                    northAngle = entry.northAngle,
                                    sceneKey = entry.scene,
                                    onBack = { historyView = null; screen = "history" },
                                    onSaveHistory = null
                                )
                            } else {
                                ReportScreen(
                                    result = AppState.analysisResult,
                                    northAngle = AppState.northAngle ?: 0.0,
                                    sceneKey = AppState.scene,
                                    onBack = { screen = "home" },
                                    onSaveHistory = {
                                        val r = AppState.analysisResult
                                        if (r != null) {
                                            HistoryStore.save(
                                                context,
                                                HistoryEntry(
                                                    id = UUID.randomUUID().toString(),
                                                    name = AppState.sceneName(),
                                                    createdAt = System.currentTimeMillis(),
                                                    scene = AppState.scene,
                                                    northAngle = AppState.northAngle ?: 0.0,
                                                    result = r
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        "history" -> HistoryScreen(
                            onBack = { screen = "home" },
                            onView = { entry -> historyView = entry; screen = "report" }
                        )
                        else -> HomeScreen(
                            onStartScan = { screen = "scan" },
                            onSetBirth = { screen = "birth" },
                            onViewReport = { screen = "report" },
                            onViewHistory = { screen = "history" }
                        )
                    }
                }
            }
        }
    }

    /** 切回前台时重新隐藏系统栏（沉浸式标准模式）。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
