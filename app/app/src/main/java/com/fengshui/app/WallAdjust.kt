package com.fengshui.app

import com.fengshui.solver.Geo
import com.fengshui.solver.Pt
import com.fengshui.solver.Seg

/**
 * 贴墙类物体的墙边校正（嵌入式/贴墙柜定位与进深优化，多边形确定后执行）：
 *  - C1 墙吸附：嵌入式物体检测框底未达地面时，投影落点偏前 → 吸附到最近墙
 *    （沿法向向内 dimZ/2 + 余量）。
 *  - A1 进深测量：贴墙物体进深 ≈ 2 ×（中心到墙距），用投影几何给出真实进深，
 *    替代类型默认值（钳制在默认值的 [0.5, 2.5] 倍）。
 * 门窗等贴地齐平类（默认进深 < 0.3）不调整，保持原位。
 */
object WallAdjust {

    /** 视作"贴墙"的最近墙距上限（相对默认进深）。 */
    private const val SNAP_EXTRA = 0.6

    fun apply(objects: List<ScanObject>, polygon: List<Pt>): List<ScanObject> {
        if (polygon.size < 3) return objects
        val walls = (0 until polygon.size).map { i -> Seg(polygon[i], polygon[(i + 1) % polygon.size]) }
        return objects.map { o ->
            val type = canonicalType(o.type)
            val (_, ddz) = FactsBuilder.defaultDims(type)
            if (!isWallNeeding(type) || ddz < 0.3) o else adjust(o, type, ddz, walls)
        }
    }

    private fun adjust(o: ScanObject, type: String, ddz: Double, walls: List<Seg>): ScanObject {
        val p = Pt(o.x, o.z)
        val (wall, d) = nearestWall(p, walls) ?: return o
        if (d > ddz / 2 + SNAP_EXTRA) return o   // 不在墙边：不吸附、不测深

        val foot = projectToSegment(p, wall)
        val (nx, nz) = inwardNormal(foot, walls) ?: return o
        val targetDist = ddz / 2 + 0.15
        val newX = foot.x + nx * targetDist
        val newZ = foot.z + nz * targetDist
        if (!Geo.pointInPolygon(Pt(newX, newZ), polygonOf(walls))) return o

        // A1：进深 = 2 ×（吸附后到墙距），钳制
        val newDist = Geo.distPointSegment(Pt(newX, newZ), wall)
        val newDz = (2 * newDist).coerceIn(ddz * 0.5, ddz * 2.5)
        return o.copy(x = newX, z = newZ, dimZ = newDz)
    }

    /** 最近墙段 + 距离（p 到线段）。 */
    private fun nearestWall(p: Pt, walls: List<Seg>): Pair<Seg, Double>? {
        var best: Seg? = null
        var bestD = Double.MAX_VALUE
        for (w in walls) {
            val d = Geo.distPointSegment(p, w)
            if (d < bestD) { bestD = d; best = w }
        }
        return best?.let { it to bestD }
    }

    /** p 到线段 a-b 的垂足（超界钳到端点）。 */
    private fun projectToSegment(p: Pt, s: Seg): Pt {
        val dx = s.b.x - s.a.x; val dz = s.b.z - s.a.z
        val len2 = dx * dx + dz * dz
        if (len2 < 1e-12) return s.a
        val t = ((p.x - s.a.x) * dx + (p.z - s.a.z) * dz) / len2
        val tt = t.coerceIn(0.0, 1.0)
        return Pt(s.a.x + tt * dx, s.a.z + tt * dz)
    }

    /** 指向房间内部的单位法向。 */
    private fun inwardNormal(foot: Pt, walls: List<Seg>): Pair<Double, Double>? {
        val (wall, _) = nearestWall(foot, walls) ?: return null
        val dx = wall.b.x - wall.a.x; val dz = wall.b.z - wall.a.z
        val len = Math.hypot(dx, dz)
        if (len < 1e-9) return null
        val ux = dx / len; val uz = dz / len
        val cand = listOf(-uz to ux, uz to -ux)
        val poly = polygonOf(walls)
        return cand.firstOrNull { (nx, nz) ->
            Geo.pointInPolygon(Pt(foot.x + nx * 0.1, foot.z + nz * 0.1), poly)
        }
    }

    private fun polygonOf(walls: List<Seg>): List<Pt> =
        walls.map { it.a }
}
