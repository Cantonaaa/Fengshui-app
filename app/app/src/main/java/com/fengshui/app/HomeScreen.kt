package com.fengshui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onStartScan: () -> Unit,
    onSetBirth: () -> Unit,
    onViewReport: () -> Unit
) {
    val context = LocalContext.current
    var scene by remember { mutableStateOf(AppState.scene) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text(
                "风水堪舆",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                "入宅勘察 · 命卦方位 · 古籍依凭",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // 生辰 / 命卦状态卡
            StatusCard {
                val birth = AppState.guaInfo
                Text("生辰与命卦", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (birth != null) {
                    Text(
                        birth.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    AppState.birthSummary()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "未定（需生辰以起命卦）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 北向状态卡
            StatusCard {
                Text("罗盘", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (AppState.hasNorth()) "已校准" else "未正（入宅页定盘）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (AppState.hasNorth()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // 场景选择
            StatusCard {
                Text("居所类别", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val scenes = listOf(
                        "bedroom" to "卧室", "living" to "客厅", "kitchen" to "厨房",
                        "study" to "书房", "office" to "办公室"
                    )
                    scenes.forEach { (key, name) ->
                        val selected = key == scene
                        val btnMod = Modifier.height(36.dp)
                        if (selected) {
                            Button(
                                onClick = {
                                    scene = key; AppState.scene = key; AppState.save(context)
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                                modifier = btnMod
                            ) { Text(name, style = MaterialTheme.typography.bodySmall) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    scene = key; AppState.scene = key; AppState.save(context)
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                                modifier = btnMod
                            ) { Text(name, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // 主 CTA
            Button(
                onClick = onStartScan,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("入宅勘察", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = onSetBirth,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("定生辰 · 起命卦") }

            OutlinedButton(
                onClick = onViewReport,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("观阅批语") }

            Text(
                "生辰与本机诸事，谨藏于内，绝不上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}
