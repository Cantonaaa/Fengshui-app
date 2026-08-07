package com.fengshui.solver

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** 2D 点（房间平面 XZ）。 */
data class Pt(val x: Double, val z: Double)

/** 线段。 */
data class Seg(val a: Pt, val b: Pt)

enum class Placement { WALL_NEEDING, FREESTANDING }
enum class Movability { MOVABLE, FIXED }

/** 家具/物体。pos 为地面中心点；dimX/dimZ 为占地尺寸。 */
data class Furniture(
    val id: String,
    val type: String,
    var pos: Pt,
    val dimX: Double,
    val dimZ: Double,
    val placement: Placement,
    val movability: Movability,
    val zhName: String = ""
) {
    /** 轴对齐占地矩形（用于占用图/碰撞）。 */
    fun rect(): Pair<Pt, Pt> = Pair(
        Pt(pos.x - dimX / 2, pos.z - dimZ / 2),
        Pt(pos.x + dimX / 2, pos.z + dimZ / 2)
    )
}

/** 房间事实（纯 Kotlin，无 Android 依赖）。 */
class RoomFacts(
    val polygon: List<Pt>,
    val walls: List<Seg>,
    val floorHeight: Double,
    val objects: MutableList<Furniture>,
    val clearance: Double = 0.4,
    val northAngle: Double = 0.0
) {
    /** 家具占用矩形集合（含净空膨胀）。 */
    fun occupiedRects(padding: Double = 0.0): List<Pair<Pt, Pt>> =
        objects.map { f ->
            val (a, b) = f.rect()
            Pair(Pt(a.x - padding, a.z - padding), Pt(b.x + padding, b.z + padding))
        }

    fun objectOf(id: String): Furniture? = objects.firstOrNull { it.id == id }
}

/** 几何工具。 */
object Geo {

    /** 射线法判断点在多边形内。 */
    fun pointInPolygon(p: Pt, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.z > p.z) != (b.z > p.z) &&
                p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z + 1e-12) + a.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** 点到线段最近距离。 */
    fun distPointSegment(p: Pt, s: Seg): Double {
        val dx = s.b.x - s.a.x; val dz = s.b.z - s.a.z
        val len2 = dx * dx + dz * dz
        val t = if (len2 < 1e-12) 0.0 else
            ((p.x - s.a.x) * dx + (p.z - s.a.z) * dz) / len2
        val tC = t.coerceIn(0.0, 1.0)
        val cx = s.a.x + tC * dx; val cz = s.a.z + tC * dz
        return Math.hypot(p.x - cx, p.z - cz)
    }

    /** 点到最近墙的距离。 */
    fun distToWall(p: Pt, walls: List<Seg>): Double =
        walls.minOfOrNull { distPointSegment(p, it) } ?: Double.MAX_VALUE

    /** 物体是否贴墙（到墙距离 < 阈值）。 */
    fun isNearWall(p: Pt, walls: List<Seg>, thr: Double): Boolean =
        distToWall(p, walls) < thr

    /** 两中心距离。 */
    fun dist(a: Pt, b: Pt): Double = Math.hypot(a.x - b.x, a.z - b.z)

    /** 两矩形是否相交（含 padding）。 */
    fun rectsOverlap(r1: Pair<Pt, Pt>, r2: Pair<Pt, Pt>): Boolean {
        return !(r1.second.x < r2.first.x || r1.first.x > r2.second.x ||
                r1.second.z < r2.first.z || r1.first.z > r2.second.z)
    }

    /** 鞋带面积。 */
    fun polygonArea(poly: List<Pt>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            s += a.x * b.z - b.x * a.z
        }
        return abs(s) / 2.0
    }

    /** 以 northAngle(弧度) 为北的 8 宫（卦位）：返回方位名。 */
    fun sector(p: Pt, center: Pt, northAngle: Double): String {
        val dx = p.x - center.x; val dz = p.z - center.z
        val ang = atan2(dx, dz) - northAngle  // 0=北, 顺时针
        val deg = Math.toDegrees(ang).let { (it % 360 + 360) % 360 }
        return when {
            deg < 22.5 || deg >= 337.5 -> "北"
            deg < 67.5 -> "东北"
            deg < 112.5 -> "东"
            deg < 157.5 -> "东南"
            deg < 202.5 -> "南"
            deg < 247.5 -> "西南"
            deg < 292.5 -> "西"
            else -> "西北"
        }
    }
}
