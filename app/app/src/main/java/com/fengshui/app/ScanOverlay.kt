package com.fengshui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** AR 覆盖层半透明信息芯片。 */
@Composable
fun OverlayChip(text: String, accent: Color = Color.White) {
    Text(
        text,
        color = accent,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** 罗盘：固定北标记 + 红色航向针（azimuthText 为 "0".."359" 度数字串）。 */
@Composable
fun CompassView(azimuthText: String, northSet: Boolean, modifier: Modifier = Modifier) {
    val azimuthDeg = azimuthText.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    Canvas(modifier.size(78.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f - 2f
        // 底盘
        drawCircle(Color.White.copy(alpha = 0.88f), radius = r, center = Offset(cx, cy))
        drawCircle(Color(0x9A, 0x9A, 0x9A), radius = r, center = Offset(cx, cy), style = Stroke(2f))
        // 北(上)标记：红=未校准，绿=已校准
        val northColor = if (northSet) Color(0x2E, 0x7D, 0x32) else Color(0xD0, 0x30, 0x20)
        drawCircle(northColor, radius = 4f, center = Offset(cx, cy - r + 8f))
        // 航向针（device 朝向）
        val a = Math.toRadians(azimuthDeg.toDouble())
        val tx = cx + (r - 10f) * sin(a).toFloat()
        val ty = cy - (r - 10f) * cos(a).toFloat()
        drawLine(
            Color(0xC6, 0x28, 0x28),
            Offset(cx, cy), Offset(tx, ty),
            strokeWidth = 4f, cap = StrokeCap.Round
        )
        drawCircle(Color(0xC6, 0x28, 0x28), radius = 4f, center = Offset(cx, cy))
    }
}
