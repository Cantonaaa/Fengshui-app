package com.fengshui.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/** 生辰输入页：公历生年 + 性别 → 命卦。仅存本地。 */
@Composable
fun BirthSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var year by remember { mutableStateOf(AppState.birthYear?.toString() ?: "1990") }
    var gender by remember { mutableStateOf(AppState.gender ?: "男") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("生辰设置", style = MaterialTheme.typography.headlineMedium)
            Text(
                "用于计算命卦（东四/西四命）与四吉凶方。仅存于本机。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("出生年份（公历）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { gender = "男" }) { Text("男") }
                OutlinedButton(onClick = { gender = "女" }) { Text("女") }
            }
            val guaPreview = runCatching {
                val y = year.toIntOrNull()
                if (y != null) MingGua.compute(y, gender, AppState.baguaJson) else null
            }.getOrNull()
            if (guaPreview != null && guaPreview.trigram != "?") {
                Text(
                    "命卦：${guaPreview.summary}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    val y = year.toIntOrNull()
                    if (y == null || y < 1900 || y > 2100) {
                        error = "请输入有效年份（1900-2100）"
                    } else {
                        AppState.birthYear = y
                        AppState.gender = gender
                        AppState.refreshGua()
                        AppState.save(context)
                        onDone()
                    }
                },
                modifier = Modifier.padding(top = 24.dp)
            ) { Text("保存") }
        }
    }
}
