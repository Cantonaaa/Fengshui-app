package com.fengshui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 轮播基础理论：仅原文 + 小字出处。 */
object TheoryProvider {
    data class Quote(val text: String, val source: String)

    val QUOTES = listOf(
        Quote("葬者乘生氣也", "《葬书·内篇》"),
        Quote("氣乘風則散，界水則止", "《葬书·内篇》"),
        Quote("明堂惜水如惜血，堂裡避風如避賊", "《葬书·内篇》"),
        Quote("宅有五虛，令人貧耗；五實，令人富貴", "《宅经》"),
        Quote("刑禍之方縮復縮，猶恐災殃枉相逐", "《宅经》"),
        Quote("火淹即是廚房止宜在宅凶方不宜在吉方", "《阳宅十书·论厨灶》"),
        Quote("安床，宜擇宅之吉方吉間", "《宅法举隅·卧房》"),
        Quote("倉庫屬土，宜在土方，不宜在木方", "《宅法举隅·仓库》"),
        Quote("灶位與床位，忌相反，為衝關，宜相生為吉", "《宅法举隅·灶》"),
        Quote("財集倉庫，子孫忠孝，天神祐助", "《宅经》")
    )
}

/** 起批过程中轮播基础理论（原文 + 出处）。 */
@Composable
fun TheoryCarousel(modifier: Modifier = Modifier) {
    val items = TheoryProvider.QUOTES
    var idx by remember { mutableIntStateOf(0) }

    LaunchedEffect(items.size) {
        while (true) {
            delay(5000)
            idx = (idx + 1) % items.size
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val q = items[idx]
            Text(
                q.text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                q.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.indices.forEach { i ->
                    val color = if (i == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    BoxDot(color)
                }
            }
        }
    }
}

@Composable
private fun BoxDot(color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(8.dp).background(color, CircleShape)
    )
}
