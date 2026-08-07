package com.fengshui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 历史堪舆记录页：按测试时间从新到旧，支持命名/重命名/删除/查看。 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onView: (HistoryEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<HistoryEntry?>(null) }
    var renaming by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val list = HistoryStore.list(context)
            withContext(Dispatchers.Main) { entries = list }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = HistoryStore.list(context)
            withContext(Dispatchers.Main) { entries = list }
        }
        loaded = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp)) {
            Text("历史记录", style = MaterialTheme.typography.headlineMedium)
            Text(
                "本地谨藏，按测试时间从新至旧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            if (loaded && entries.isEmpty()) {
                Text(
                    "尚无历史记录。堪舆成批后，可在批语页点「存入历史」。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries, key = { it.id }) { e ->
                        Card(
                            onClick = { onView(e) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        e.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "测试时间：${HistoryStore.formatTime(e.createdAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Text(
                                        "${AppState.sceneName(e.scene)} · 器物 ${e.result.objects.size} 件 · 吉凶 ${e.result.hits.size} 则",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box {
                                    TextButton(onClick = { menuFor = e }) {
                                        Text("⋯", style = MaterialTheme.typography.titleLarge)
                                    }
                                    DropdownMenu(
                                        expanded = menuFor?.id == e.id,
                                        onDismissRequest = { menuFor = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            onClick = { menuFor = null; renaming = e }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuFor = null; deleting = e }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("← 返回首页")
            }
        }
    }

    renaming?.let { e ->
        var text by remember(e) { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) { HistoryStore.rename(context, e.id, t) }
                        reload()
                    }
                    renaming = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除记录") },
            text = { Text("确定删除「${e.name}」？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { HistoryStore.delete(context, e.id) }
                    deleting = null
                    reload()
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}
