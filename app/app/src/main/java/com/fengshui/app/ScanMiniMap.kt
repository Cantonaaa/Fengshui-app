package com.fengshui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fengshui.solver.Geo
import com.fengshui.solver.Pt
import kotlin.math.hypot

/** 覆盖度计算（纯函数，后台线程调用）。 */
object ScanCoverage {

    /**
     * 房间多边形网格化，格点距任一关键帧相机位置 ≤ r 视为已覆盖。
     * @return 覆盖百分比 0..100
     */
    fun coveragePct(keyframes: List<Pair<Double, Double>>, polygon: List<Pt>, r: Double): Int {
        if (polygon.size < 3 || keyframes.isEmpty()) return 0
        val minX = polygon.minOf { it.x }; val maxX = polygon.maxOf { it.x }
        val minZ = polygon.minOf { it.z }; val maxZ = polygon.maxOf { it.z }
        val cell = 0.5
        var total = 0
        var covered = 0
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                if (Geo.pointInPolygon(Pt(x, z), polygon)) {
                    total++
                    if (keyframes.any { hypot(it.first - x, it.second - z) <= r }) covered++
                }
                z += cell
            }
            x += cell
        }
        return if (total == 0) 0 else covered * 100 / total
    }
}

/**
 * 扫描小地图（右下角）：相机朝向上。
 * 亮区 = 关键帧覆盖圆并集（已覆盖），线 = 房间墙线（多边形），箭头 = 当前位置与朝向。
 */
@Composable
fun MiniMapView(
    keyframes: List<Pair<Double, Double>>,
    polygon: List<Pt>?,
    cameraPos: Pair<Double, Double>,
    headingRad: Double,
    coveragePct: Int,
    coverageRadius: Double,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(120.dp)) {
                val all = keyframes + (polygon?.map { it.x to it.z } ?: emptyList()) + listOf(cameraPos)
                if (all.isEmpty()) return@Canvas
                val cx = all.map { it.first }.average()
                val cz = all.map { it.second }.average()
                val center = Pt(cx, cz)
                val projected = all.map { BaguaMap.project(Pt(it.first, it.second), center, headingRad) }
                val maxD = (projected.map { hypot(it.first, it.second) }.maxOrNull() ?: 1.0)
                    .coerceAtLeast(coverageRadius)
                val scale = (size.minDimension / 2f - 8f) / maxD
                fun toSc(p: Pair<Double, Double>): Offset = Offset(
                    size.width / 2f + (p.first * scale).toFloat(),
                    size.height / 2f - (p.second * scale).toFloat()
                )

                // 覆盖亮圈（关键帧并集）
                for (kf in keyframes) {
                    val c = toSc(BaguaMap.project(Pt(kf.first, kf.second), center, headingRad))
                    drawCircle(Color(0xFF7FB8E8).copy(alpha = 0.40f), radius = (coverageRadius * scale).toFloat(), center = c)
                }
                // 墙线
                polygon?.takeIf { it.size >= 3 }?.let { poly ->
                    val path = Path()
                    val p0 = toSc(BaguaMap.project(poly[0], center, headingRad))
                    path.moveTo(p0.x, p0.y)
                    for (i in 1 until poly.size) {
                        val p = toSc(BaguaMap.project(poly[i], center, headingRad))
                        path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, Color.White, style = Stroke(width = 2f))
                }
                // 当前位置（向上箭头 = 相机朝向；地图相机朝上）
                val cam = toSc(BaguaMap.project(Pt(cameraPos.first, cameraPos.second), center, headingRad))
                val arrow = Path().apply {
                    moveTo(cam.x, cam.y - 10f)
                    lineTo(cam.x - 5.5f, cam.y + 6f)
                    lineTo(cam.x + 5.5f, cam.y + 6f)
                    close()
                }
                drawPath(arrow, Color(0xFF40C4FF))
                drawCircle(Color.White, radius = 2.5f, center = cam)
            }
            Text(
                "覆盖 $coveragePct%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
