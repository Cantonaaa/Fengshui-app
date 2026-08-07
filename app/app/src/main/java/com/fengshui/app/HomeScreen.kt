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
import androidx.compose.foundation.layout.safeContentPadding
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onStartScan: () -> Unit,
    onSetBirth: () -> Unit,
    onViewReport: () -> Unit,
    onViewHistory: () -> Unit
) {
    val context = LocalContext.current
    var scene by remember { mutableStateOf(AppState.scene) }
    val scope = rememberCoroutineScope()
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }

    fun checkUpdate() {
        updateMsg = "检查中…"
        scope.launch(Dispatchers.IO) {
            val info = UpdateChecker.checkLatest()
            val current = BuildConfig.VERSION_NAME
            withContext(Dispatchers.Main) {
                updateMsg = when {
                    info == null -> "检查失败（网络或服务不可达）"
                    UpdateChecker.isNewer(info.version, current) -> { updateInfo = info; null }
                    else -> "当前已是最新版本"
                }
            }
        }
    }

    fun startDownload(info: UpdateChecker.UpdateInfo) {
        downloading = true
        updateMsg = "下载中…"
        scope.launch(Dispatchers.IO) {
            val id = UpdateChecker.download(context, info.apkUrl, info.version)
            while (true) {
                kotlinx.coroutines.delay(1000)
                val p = UpdateChecker.downloadProgress(context, id)
                withContext(Dispatchers.Main) {
                    when {
                        p >= 100 -> {
                            downloading = false
                            val f = UpdateChecker.downloadedFile(context, info.version)
                            if (f != null) {
                                if (UpdateChecker.install(context, f)) updateMsg = "更新包已下载，请按系统提示安装"
                                else { updateMsg = "需开启「安装未知应用」权限"; UpdateChecker.openInstallPermissionSettings(context) }
                            }
                        }
                        p < 0 -> { downloading = false; updateMsg = "下载失败" }
                        else -> updateMsg = "下载中… $p%"
                    }
                }
                if (p >= 100 || p < 0) break
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
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

            // 罗盘状态卡（北向为入宅会话内数据，首页恒显示未正）
            StatusCard {
                Text("罗盘", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "未正（入宅后定盘）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
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

            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("历史记录") }

            Text(
                "生辰与本机诸事，谨藏于内，绝不上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
                textAlign = TextAlign.Center
            )

            // 检查更新（可选在线）
            TextButton(onClick = { checkUpdate() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("检查更新")
            }
            updateMsg?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            updateInfo?.let { info ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { updateInfo = null },
                    title = { Text("发现新版本 v${info.version}") },
                    text = { Text(info.notes.take(500), style = MaterialTheme.typography.bodySmall) },
                    confirmButton = {
                        TextButton(onClick = {
                            updateInfo = null
                            startDownload(info)
                        }) { Text("下载安装") }
                    },
                    dismissButton = {
                        TextButton(onClick = { updateInfo = null }) { Text("取消") }
                    }
                )
            }
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
