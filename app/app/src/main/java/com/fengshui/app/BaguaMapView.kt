package com.fengshui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.fengshui.solver.Pt
import com.fengshui.solver.RemediationPlan
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

/**
 * 宫位图：世界坐标 (x,z) → 画布（北向上）的纯变换 + Compose Canvas 渲染。
 * 画房间多边形、八卦 8 宫扇形（吉方绿/凶方红）、物体点位。
 */
object BaguaMap {

    /** 八卦方位顺时针序（自北起）。 */
    val SECTORS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

    /**
     * 世界点 → 以房间中心为原点的显示坐标（北指向上，顺时针）。
     * 角度 = atan2(dx,dz) - northAngle（0=北）；显示 (x=右, y=上)。
     */
    fun project(p: Pt, center: Pt, northAngle: Double): Pair<Double, Double> {
        val dx = p.x - center.x
        val dz = p.z - center.z
        val r = hypot(dx, dz)
        val ang = Math.atan2(dx, dz) - northAngle
        return Pair(r * Math.sin(ang), -r * Math.cos(ang))
    }

    /** 投影点包围盒 → 适配画布的比例（带 padding）。 */
    fun fitScale(pts: List<Pair<Double, Double>>, canvasW: Float, canvasH: Float, padding: Float): Float {
        if (pts.isEmpty()) return 1f
        val xs = pts.map { it.first }
        val ys = pts.map { it.second }
        val w = (xs.max() - xs.min()).toFloat()
        val h = (ys.max() - ys.min()).toFloat()
        val s = min((canvasW - 2 * padding) / max(w, 1f), (canvasH - 2 * padding) / max(h, 1f))
        return max(s, 1f)
    }
}

/** 宫位图渲染。objects 带卦位；gua 提供吉凶方；plan 提供整改移动目标。 */
@Composable
fun BaguaMapView(
    polygon: List<Pt>,
    objects: List<ObjInfo>,
    northAngle: Double,
    gua: MingGua.GuaInfo?,
    plan: RemediationPlan? = null,
    modifier: Modifier = Modifier
) {
    val canvasH = 260.dp
    val good = gua?.goodSectors?.toSet() ?: emptySet()
    val bad = gua?.badSectors?.toSet() ?: emptySet()

    Canvas(modifier = modifier.fillMaxWidth().height(canvasH)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = if (polygon.isNotEmpty())
            Pt(polygon.map { it.x }.average(), polygon.map { it.z }.average())
        else Pt(0.0, 0.0)

        val projPoly = polygon.map { BaguaMap.project(it, center, northAngle) }
        val maxR = (projPoly.map { hypot(it.first, it.second) }.maxOrNull() ?: 1.0).toFloat()
        val scale = if (maxR > 0) ((min(size.width, size.height) / 2f - 20f) / maxR) else 1f
        fun toScreen(p: Pair<Double, Double>): Offset =
            Offset(cx + (p.first * scale).toFloat(), cy - (p.second * scale).toFloat())

        // 房间多边形路径（先构建，供裁剪与描边）
        val polyPath = Path()
        if (projPoly.size >= 3) {
            polyPath.moveTo(toScreen(projPoly[0]).x, toScreen(projPoly[0]).y)
            for (i in 1 until projPoly.size) polyPath.lineTo(toScreen(projPoly[i]).x, toScreen(projPoly[i]).y)
            polyPath.close()
        }

        // 房间内的内容整体裁剪进多边形（八卦扇区/中心/物体/整改箭头不越墙）
        clipPath(polyPath) {
            // 1) 八卦宫位扇形
            for (i in BaguaMap.SECTORS.indices) {
                val sector = BaguaMap.SECTORS[i]
                val color = when {
                    sector in good -> Color(0x40, 0xCC, 0x60, 0x45)
                    sector in bad -> Color(0xE0, 0x50, 0x40, 0x40)
                    else -> Color(0xCC, 0xCC, 0xCC, 0x25)
                }
                val start = 270f + i * 45f - 22.5f   // 北在顶部，顺时针
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = 45f,
                    useCenter = true,
                    topLeft = Offset(cx - maxR * scale, cy - maxR * scale),
                    size = Size(maxR * scale * 2f, maxR * scale * 2f)
                )
            }

            // 3) 中心
            drawCircle(Color(0x88, 0x88, 0x88), radius = 3f, center = Offset(cx, cy))

            // 4) 物体点位（放大 + 中文标签）
            for (o in objects) {
                val p = BaguaMap.project(Pt(o.x, o.z), center, northAngle)
                val pos = toScreen(p)
                val c = when {
                    o.sector in bad -> Color(0xD0, 0x30, 0x20)
                    o.sector in good -> Color(0x20, 0x90, 0x40)
                    else -> Color(0x88, 0x88, 0x88)
                }
                drawCircle(c, radius = 10f, center = pos)
                drawCircle(Color.White, radius = 5f, center = pos)
                // 中文标签（原生 Canvas，粗体+阴影提升可读性）
                drawContext.canvas.nativeCanvas.drawText(
                    typeNameZH(o.type), pos.x + 14f, pos.y + 4f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 14f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                )
            }

            // 5) 整改目标（虚线圈 + 箭头 当前→目标）
            plan?.actions?.forEach { act ->
                val obj = objects.firstOrNull { it.id == act.objectId } ?: return@forEach
                val from = toScreen(BaguaMap.project(Pt(obj.x, obj.z), center, northAngle))
                val to = toScreen(BaguaMap.project(act.toPosition, center, northAngle))
                val c = Color(0x10, 0x60, 0xC0)
                drawCircle(
                    color = c, radius = 9f, center = to,
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                )
                drawLine(c, from, to, strokeWidth = 2f)
                val ang = Math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
                val ah = 9f
                val dx = Math.cos(ang).toFloat(); val dy = Math.sin(ang).toFloat()
                drawLine(c, to, Offset(to.x - dx * ah + dy * ah * 0.5f, to.y - dy * ah - dx * ah * 0.5f), strokeWidth = 2f)
                drawLine(c, to, Offset(to.x - dx * ah - dy * ah * 0.5f, to.y - dy * ah + dx * ah * 0.5f), strokeWidth = 2f)
            }
        }

        // 2) 房间轮廓：可见深棕描边 + 微填充
        if (projPoly.size >= 3) {
            drawPath(polyPath, Color(0xFF5A4632), style = Stroke(width = 3f))
            drawPath(polyPath, Color(0xFF5A4632).copy(alpha = 0.07f))
        }
    }
}
