package com.fengshui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fengshui.app.ui.theme.FengShuiAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FengShuiAppTheme {
                var screen by remember { mutableStateOf("home") }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "scan" -> ScanScreen(onBack = { screen = "home" })
                        else -> HomeScreen(onStartScan = { screen = "scan" })
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(onStartScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("风水堪舆 · MVP", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A1.1: ARCore 平面检测验证",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )
        Button(onClick = onStartScan) {
            Text("开始 AR 扫描")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    FengShuiAppTheme {
        HomeScreen(onStartScan = {})
    }
}
