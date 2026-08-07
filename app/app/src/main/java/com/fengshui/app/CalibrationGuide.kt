package com.fengshui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 校北引导（雅言）：大罗盘 + 大示数 + 距北彩色指示。
 * 未校准时在扫描页顶部横幅显示；对准后点「定向正盘」。
 */
@Composable
fun CalibrationGuide(
    azimuthText: String,
    onCalibrate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val azimuth = azimuthText.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    // 距北 = 与 0° 的最小夹角
    val offset = ((azimuth + 180) % 360) - 180
    val off = abs(offset)
    val levelColor = when {
        off <= 5 -> Color(0xFF2E7D32)
        off <= 15 -> Color(0xFFB8860B)
        else -> Color(0xFFC0392B)
    }
    val aligned = off <= 5

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEEFBF8F2)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "定 向 正 盘",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大罗盘
                CompassDial(azimuth, Modifier.size(150.dp))
                // 示数 + 距北
                Column(Modifier.padding(start = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "示数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$azimuth°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = levelColor
                    )
                    Text(
                        if (aligned) "已对准北向" else "距北 $off°",
                        style = MaterialTheme.typography.titleSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                if (aligned) "罗盘已正，可定盘矣" else "徐转罗盘，使示数归于零度（正北）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Button(
                onClick = onCalibrate,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (aligned) "定 向 正 盘" else "此向为北 · 定盘")
            }
        }
    }
}

/** 罗盘表盘：刻度 + 方位字 + 红色航向针。 */
@Composable
private fun CompassDial(azimuth: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        // 底盘
        drawCircle(Color(0xFFFFFFFF), radius = r, center = Offset(cx, cy))
        drawCircle(Color(0xFFB9A88A), radius = r, center = Offset(cx, cy), style = Stroke(2.5f))
        // 刻度
        for (i in 0 until 360 step 15) {
            val a = Math.toRadians(i.toDouble())
            val inner = if (i % 45 == 0) r - 16f else r - 10f
            val x1 = cx + inner * sin(a).toFloat()
            val y1 = cy - inner * cos(a).toFloat()
            val x2 = cx + (r - 3f) * sin(a).toFloat()
            val y2 = cy - (r - 3f) * cos(a).toFloat()
            drawLine(Color(0xFF8A7A5C), Offset(x1, y1), Offset(x2, y2), strokeWidth = if (i % 45 == 0) 2.5f else 1.5f)
        }
        // 方位字
        val dirs = listOf("北" to 0, "东" to 90, "南" to 180, "西" to 270)
        dirs.forEach { (name, deg) ->
            val a = Math.toRadians(deg.toDouble())
            val x = cx + (r - 26f) * sin(a).toFloat()
            val y = cy - (r - 26f) * cos(a).toFloat()
            drawContext.canvas.nativeCanvas.drawText(
                name, x, y + 6f,
                android.graphics.Paint().apply {
                    textSize = 18f
                    textAlign = android.graphics.Paint.Align.CENTER
                    color = 0xFF6A5C40.toInt()
                }
            )
        }
        // 航向针
        val a = Math.toRadians(azimuth.toDouble())
        val tx = cx + (r - 30f) * sin(a).toFloat()
        val ty = cy - (r - 30f) * cos(a).toFloat()
        drawLine(Color(0xFFC62828), Offset(cx, cy), Offset(tx, ty), strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(Color(0xFFC62828), radius = 5f, center = Offset(cx, cy))
        // 北标记
        drawCircle(Color(0xFF2E7D32), radius = 4f, center = Offset(cx, cy - r + 12f))
    }
}
