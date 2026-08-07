package com.fengshui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@Composable
fun HomeScreen(
    onStartScan: () -> Unit,
    onSetBirth: () -> Unit,
    onViewReport: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("风水堪舆 · MVP", style = MaterialTheme.typography.headlineMedium)
        Text(
            "AR 扫描房间 → 识别布局 → 命卦+北向 → 古籍规则分析",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        val birth = AppState.guaInfo
        Text(
            if (birth != null) "命卦：${birth.summary}" else "命卦：未设置（需生辰）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            if (AppState.hasNorth()) "北向：已校准" else "北向：未校准（扫描页校准）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
            Text("开始 AR 扫描")
        }
        OutlinedButton(
            onClick = onSetBirth,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
        ) { Text("设置生辰（命卦）") }
        OutlinedButton(
            onClick = onViewReport,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
        ) { Text("查看报告") }
    }
}

@Composable
fun HomeScreenPreview() {
    FengShuiAppTheme {
        HomeScreen(onStartScan = {}, onSetBirth = {}, onViewReport = {})
    }
}
