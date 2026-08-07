package com.fengshui.app

import kotlin.math.abs
import kotlin.math.floor
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

        // 3b) 玻璃透反射鬼影裁剪：密度滤波 + 最大连通分量
        val keep2 = cullGhost(keep)
        if (keep2.size < 3) return null

        // 4) 形状自适应：近矩形用最小外接矩形（干净四边形），异形用凸包（避免矩形过拟合凹角）
        val rect = fitMinAreaRect(keep2)
        val hull = convexHull(keep2)
        val rectArea = polygonArea(rect)
        val hullArea = polygonArea(hull)
        val shape = if (hullArea > 0f && hullArea / rectArea > 0.85f) rect else hull
        val area = polygonArea(shape)
        return Polygon(shape, area)
    }

    /**
     * 最小面积外接矩形（沿主导方向旋转搜索）。输入/输出均为 XZ 点 [x,z]。
     * 适合大多数近矩形房间，避免凸包对散点产生的凹凸噪声。
     */
    private fun fitMinAreaRect(points: List<FloatArray>): List<FloatArray> {
        var bestArea = Float.MAX_VALUE
        var bestCorners: List<FloatArray> = points
        for (deg in 0 until 90) {
            val a = Math.toRadians(deg.toDouble())
            val c = Math.cos(a); val s = Math.sin(a)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in points) {
                val xr = (p[0] * c + p[1] * s).toFloat()
                val zr = (-p[0] * s + p[1] * c).toFloat()
                if (xr < minX) minX = xr; if (xr > maxX) maxX = xr
                if (zr < minZ) minZ = zr; if (zr > maxZ) maxZ = zr
            }
            val area = (maxX - minX) * (maxZ - minZ)
            if (area < bestArea) {
                bestArea = area
                bestCorners = listOf(
                    floatArrayOf(minX, minZ), floatArrayOf(maxX, minZ),
                    floatArrayOf(maxX, maxZ), floatArrayOf(minX, maxZ)
                ).map { q ->
                    floatArrayOf(
                        (q[0] * c - q[1] * s).toFloat(),
                        (q[0] * s + q[1] * c).toFloat()
                    )
                }
            }
        }
        return bestCorners
    }

    /**
     * 剔除玻璃/镜面透反射鬼影点（墙外幻影簇 + 稀疏杂点）。
     * 输入/输出均为 XZ 点（[x, z]）。两步：
     *  1) 密度滤波：网格邻居数 ≥ minNeighbors 才保留（鬼影点更稀疏）
     *  2) 最大连通分量：仅保留最大簇（窗外幻影与室内隔墙，通常不连通）
     */
    private fun cullGhost(points: List<FloatArray>, r: Float = 0.3f, minNeighbors: Int = 3): List<FloatArray> {
        if (points.size < 10) return points
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        fun cellOf(x: Float, z: Float): Pair<Int, Int> =
            Pair(floor(x / r).toInt(), floor(z / r).toInt())
        for ((i, p) in points.withIndex()) {
            val (cx, cz) = cellOf(p[0], p[1])
            grid.getOrPut(key(cx, cz)) { mutableListOf() }.add(i)
        }

        // 1) 密度滤波：邻居（r 内）计数
        val keep = BooleanArray(points.size)
        for ((i, p) in points.withIndex()) {
            val (cx, cz) = cellOf(p[0], p[1])
            var cnt = 0
            for (dx in -1..1) for (dz in -1..1) {
                val bucket = grid[key(cx + dx, cz + dz)] ?: continue
                for (j in bucket) {
                    if (j == i) continue
                    val q = points[j]
                    if (sqrt((p[0]-q[0])*(p[0]-q[0]) + (p[1]-q[1])*(p[1]-q[1])) <= r) cnt++
                }
            }
            keep[i] = cnt >= minNeighbors
        }

        // 2) 最大连通分量（并查集）
        val parent = IntArray(points.size) { it }
        fun find(x: Int): Int { var r0 = x; while (parent[r0] != r0) r0 = parent[r0]; return r0 }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for ((i, p) in points.withIndex()) {
            if (!keep[i]) continue
            val (cx, cz) = cellOf(p[0], p[1])
            for (dx in -1..1) for (dz in -1..1) {
                val bucket = grid[key(cx + dx, cz + dz)] ?: continue
                for (j in bucket) {
                    if (j == i || !keep[j]) continue
                    val q = points[j]
                    if (sqrt((p[0]-q[0])*(p[0]-q[0]) + (p[1]-q[1])*(p[1]-q[1])) <= r) union(i, j)
                }
            }
        }
        val compSize = HashMap<Int, Int>()
        for (i in points.indices) if (keep[i]) {
            val root = find(i); compSize[root] = (compSize[root] ?: 0) + 1
        }
        val maxRoot = compSize.maxByOrNull { it.value }?.key ?: return points
        val result = points.indices.filter { keep[it] && find(it) == maxRoot }.map { points[it] }
        return if (result.size >= 3) result else points
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
