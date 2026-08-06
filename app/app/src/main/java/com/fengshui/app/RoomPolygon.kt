package com.fengshui.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A1.3: 户型多边形生成（Kotlin 端上版）。
 * 输入：点云 (x, y=高度, z)；输出：房间足迹多边形（地板凸包）。
 * 算法已验证于 scripts/room_polygon.py（真机点云 49.8m²）。
 */
object RoomPolygon {

    data class Plane(val normal: FloatArray, val d: Float, val inliers: Int)

    data class Polygon(val vertices: List<FloatArray>, val area: Float)

    /** 清洗：去 NaN/Inf、高度 Y∈[-0.6,3.5]、xz∈[-15,15]。 */
    private fun clean(points: List<FloatArray>): List<FloatArray> =
        points.filter { p ->
            p[0].isFinite() && p[1].isFinite() && p[2].isFinite() &&
                p[1] > -0.6f && p[1] < 3.5f && abs(p[0]) < 15f && abs(p[2]) < 15f
        }

    /** RANSAC 平面拟合。 */
    private fun ransacPlane(points: List<FloatArray>, thr: Float, minInliers: Int): Plane? {
        if (points.size < 3) return null
        val rng = java.util.Random(0)
        var best: Plane? = null
        repeat(120) {
            val i = rng.nextInt(points.size)
            var j = rng.nextInt(points.size); while (j == i) j = rng.nextInt(points.size)
            var k = rng.nextInt(points.size); while (k == i || k == j) k = rng.nextInt(points.size)
            val a = points[i]; val b = points[j]; val c = points[k]
            val v1 = floatArrayOf(b[0]-a[0], b[1]-a[1], b[2]-a[2])
            val v2 = floatArrayOf(c[0]-a[0], c[1]-a[1], c[2]-a[2])
            val n = floatArrayOf(
                v1[1]*v2[2]-v1[2]*v2[1],
                v1[2]*v2[0]-v1[0]*v2[2],
                v1[0]*v2[1]-v1[1]*v2[0]
            )
            val len = sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2])
            if (len < 1e-8f) return@repeat
            n[0]/=len; n[1]/=len; n[2]/=len
            val d = -(n[0]*a[0]+n[1]*a[1]+n[2]*a[2])
            var cnt = 0
            for (p in points) {
                if (abs(n[0]*p[0]+n[1]*p[1]+n[2]*p[2]+d) < thr) cnt++
            }
            if (cnt > minInliers && (best == null || cnt > best!!.inliers)) {
                best = Plane(n, d, cnt)
            }
        }
        return best
    }

    private fun dist(p: FloatArray, plane: Plane): Float =
        abs(plane.normal[0]*p[0]+plane.normal[1]*p[1]+plane.normal[2]*p[2]+plane.d)

    /** 主入口：点云 → 房间足迹多边形。 */
    fun buildPolygon(raw: List<FloatArray>): Polygon? {
        val c = clean(raw)
        if (c.size < 50) return null

        // 1) 地板平面：水平且最低
        var floor: Plane? = null
        var working = c.toMutableList()
        repeat(4) {
            val pl = ransacPlane(working, 0.04f, c.size / 30)
                ?: return@repeat
            if (abs(pl.normal[1]) > 0.7f) {
                val height = -pl.d / pl.normal[1]
                if (floor == null || height < (-floor!!.d / floor!!.normal[1])) floor = pl
            }
            working = working.filter { dist(it, pl) >= 0.04f }.toMutableList()
        }
        val f = floor ?: return null

        // 2) 地板带内点（距地板 < 0.15m）
        val floorPts = c.filter { dist(it, f) < 0.15f }
        if (floorPts.size < 5) return null

        // 3) 鲁棒离群剔除：质心 + 中位距离 + 2.5*MAD
        val xz = floorPts.map { floatArrayOf(it[0], it[2]) }
        val cenX = xz.map { it[0] }.sorted()[xz.size / 2]
        val cenZ = xz.map { it[1] }.sorted()[xz.size / 2]
        val dists = xz.map { sqrt((it[0]-cenX)*(it[0]-cenX)+(it[1]-cenZ)*(it[1]-cenZ)) }.sorted()
        val med = dists[dists.size / 2]
        val mads = dists.map { abs(it - med) }.sorted()
        val mad = mads[mads.size / 2]
        val keep = xz.filter { sqrt((it[0]-cenX)*(it[0]-cenX)+(it[1]-cenZ)*(it[1]-cenZ)) < med + 2.5f * mad }
        if (keep.size < 3) return null

        // 4) 凸包（Andrew 单调链）
        val hull = convexHull(keep)
        val area = polygonArea(hull)
        return Polygon(hull, area)
    }

    /** Andrew 单调链凸包，返回 (x,z) 顶点序列。 */
    private fun convexHull(points: List<FloatArray>): List<FloatArray> {
        val sorted = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray): Float =
            (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0])
        val lower = ArrayList<FloatArray>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size-2], lower[lower.size-1], p) <= 0) lower.removeAt(lower.size-1)
            lower.add(p)
        }
        val upper = ArrayList<FloatArray>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size-2], upper[upper.size-1], p) <= 0) upper.removeAt(upper.size-1)
            upper.add(p)
        }
        lower.removeAt(lower.size-1)
        upper.removeAt(upper.size-1)
        return lower + upper
    }

    /** 鞋带公式面积。 */
    private fun polygonArea(hull: List<FloatArray>): Float {
        var s = 0f
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i+1) % hull.size]
            s += a[0]*b[1] - b[0]*a[1]
        }
        return abs(s) / 2f
    }
}
