package com.fengshui.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
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

    /** 主入口：点云 → 房间足迹多边形。track = 扫描期相机轨迹 (ox, oz) 序列。 */
    /** 主入口：点云 → 房间足迹多边形。track = 扫描期相机轨迹 (ox,oz)；wallSegments = ARCore 竖墙段 [x1,z1,x2,z2]。 */
    fun buildPolygon(
        raw: List<FloatArray>,
        track: List<Pair<Float, Float>> = emptyList(),
        wallSegments: List<FloatArray> = emptyList()
    ): Polygon? {
        val c = clean(raw)
        if (c.size < 50) return null

        // 改进算法（主路径）：全点密度网格 + 形态学闭合 + 轨迹约束 + ARCore 竖墙证据(A) + 墙线拟合/正交化(D/B) + 规则化(C)。
        if (track.size >= 3) {
            try {
                val imp = improvedOutlineWall(c, track, wallSegments)
                if (imp != null) {
                    val a = polygonArea(imp)
                    if (a > 0.5f && isSimple(imp)) return Polygon(imp, a)
                }
            } catch (e: Exception) {
                android.util.Log.w("RoomPolygon", "改进算法异常，回退旧路径: ${e.message}")
            }
        }

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

        // 4) 形状自适应：近矩形用最小外接矩形（干净四边形）；
        //    有显著内凹（凹形面积 < 凸包 95%，如 L/U 型缺口）用凹多边形；
        //    否则回退凸包。
        val rect = fitMinAreaRect(keep2)
        val hull = convexHull(keep2)
        val rectArea = polygonArea(rect)
        val hullArea = polygonArea(hull)
        val concave = concaveFootprint(keep2)
        val ca = concave?.let { polygonArea(it) } ?: 0f
        val concaveValid = concave != null && ca >= 0.4f * hullArea && ca <= hullArea * 1.05f && isSimple(concave)
        val shape = when {
            hullArea <= 0f || rectArea <= 0f -> hull
            concaveValid && ca < 0.95f * hullArea -> concave
            hullArea / rectArea > 0.85f -> rect
            else -> hull
        }
        val area = polygonArea(shape)
        return Polygon(shape, area)
    }

    /** 点在多边形内（射线法，含边界）。 */
    private fun pointInPoly(p: FloatArray, poly: List<FloatArray>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i][0]; val zi = poly[i][1]
            val xj = poly[j][0]; val zj = poly[j][1]
            if ((zi > p[1]) != (zj > p[1]) &&
                p[0] < (xj - xi) * (p[1] - zi) / (zj - zi) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** 形态学闭合：先膨胀 1 格再腐蚀 1 格（补采样缝，不净扩边界）。一维 flat 网格（规避 2D 数组 JIT 越界）。 */
    private fun closeGrid(occ: BooleanArray, gh: Int, gw: Int): BooleanArray {
        val dil = BooleanArray(gh * gw)
        for (r in 0 until gh) for (c in 0 until gw) {
            val base = r * gw + c
            var hit = false
            for (dr in -1..1) {
                val rr = r + dr
                if (rr < 0 || rr >= gh) continue
                for (dc in -1..1) {
                    val cc = c + dc
                    if (cc < 0 || cc >= gw) continue
                    if (occ[rr * gw + cc]) { hit = true; break }
                }
                if (hit) break
            }
            dil[base] = hit
        }
        val ero = BooleanArray(gh * gw)
        for (r in 0 until gh) for (c in 0 until gw) {
            val base = r * gw + c
            if (!dil[base]) continue
            var all = true
            for (dr in -1..1) {
                val rr = r + dr
                if (rr < 0 || rr >= gh) { all = false; break }
                for (dc in -1..1) {
                    val cc = c + dc
                    if (cc < 0 || cc >= gw) { all = false; break }
                    if (!dil[rr * gw + cc]) { all = false; break }
                }
                if (!all) break
            }
            ero[base] = all
        }
        return ero
    }

    /** 网格占用 → 最大 4 连通分量 → Moore 外轮廓 → 世界坐标 → 去共线。一维 flat 网格。 */
    private fun traceBoundary(occ: BooleanArray, gh: Int, gw: Int, minX: Float, minZ: Float, cell: Float): List<FloatArray>? {
        val label = IntArray(gh * gw)
        var mainSize = 0
        var startCell: Pair<Int, Int>? = null
        for (r in 0 until gh) for (c in 0 until gw) {
            val base = r * gw + c
            if (!occ[base] || label[base] != 0) continue
            val q = ArrayDeque<Pair<Int, Int>>()
            label[base] = 1
            q.add(r to c)
            var size = 0
            var topmost: Pair<Int, Int>? = null
            while (q.isNotEmpty()) {
                val (rr, cc) = q.removeFirst()
                size++
                if (topmost == null || rr < topmost.first) topmost = rr to cc
                for ((dr, dc) in listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)) {
                    val nr = rr + dr; val nc = cc + dc
                    if (nr in 0 until gh && nc in 0 until gw && occ[nr * gw + nc] && label[nr * gw + nc] == 0) {
                        label[nr * gw + nc] = 1
                        q.add(nr to nc)
                    }
                }
            }
            if (size > mainSize) { mainSize = size; startCell = topmost }
        }
        if (mainSize < 4 || startCell == null) return null
        val (b0r, b0c) = startCell
        val boundary = mutableListOf(b0r to b0c)
        var br = b0r; var bc = b0c
        var prevR = b0r; var prevC = b0c - 1
        val dirs = listOf(
            0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1, 1 to 0, 1 to -1
        )
        var guard = 0
        while (true) {
            if (++guard > 1_000_000) return null
            val dirIdx = dirs.indexOf(prevR - br to prevC - bc)
            if (dirIdx < 0) return null
            var next: Pair<Int, Int>? = null
            for (k in 1..8) {
                val (dr, dc) = dirs[(dirIdx + k) and 7]
                val nr = br + dr; val nc = bc + dc
                if (nr in 0 until gh && nc in 0 until gw && occ[nr * gw + nc]) { next = nr to nc; break }
            }
            val (nr, nc) = next ?: return null
            prevR = br; prevC = bc
            br = nr; bc = nc
            if (br == b0r && bc == b0c) break
            boundary.add(br to bc)
        }
        if (boundary.size < 4) return null
        val raw = boundary.map { floatArrayOf(minX + (it.second + 0.5f) * cell, minZ + (it.first + 0.5f) * cell) }
        return simplifyCollinear(dedupeConsecutive(raw))
    }

    /**
     * 改进算法：全点(含墙点) XZ 密度网格 → 轨迹包络裁剪(上界, 控鬼影) → 形态学闭合 → 最大分量 → 外轮廓。
     * 下界：轮廓须含 ≥50% 轨迹，否则退化为轨迹包络外扩矩形。
     */
    private fun improvedOutline(points: List<FloatArray>, track: List<Pair<Float, Float>>): List<FloatArray>? {
        if (points.size < 20 || track.size < 3) return null
        val txMin = track.minOf { it.first }; val txMax = track.maxOf { it.first }
        val tzMin = track.minOf { it.second }; val tzMax = track.maxOf { it.second }
        val wallMax = 1.5f  // 墙距走迹上限（控鬼影/反射膨胀）
        val x0c = txMin - wallMax; val x1c = txMax + wallMax
        val z0c = tzMin - wallMax; val z1c = tzMax + wallMax
        val inEnv = points.filter { it[0] in x0c..x1c && it[1] in z0c..z1c }
        if (inEnv.size < 20) return null

        // 降采样：>100K 点抽稀，控网格规模
        val src = if (inEnv.size > 100_000) inEnv.filterIndexed { i, _ -> i % 2 == 0 } else inEnv
        if (src.size < 20) return null

        val cell = 0.2f
        val minX = src.minOf { it[0] }; val maxX = src.maxOf { it[0] }
        val minZ = src.minOf { it[1] }; val maxZ = src.maxOf { it[1] }
        val gw = ceil((maxX - minX) / cell).toInt() + 1
        val gh = ceil((maxZ - minZ) / cell).toInt() + 1
        if (gw > 512 || gh > 512 || gw < 2 || gh < 2) return null
        val occ = BooleanArray(gh * gw)
        for (p in src) {
            val c = ((p[0] - minX) / cell).toInt().coerceIn(0, gw - 1)
            val r = ((p[1] - minZ) / cell).toInt().coerceIn(0, gh - 1)
            occ[r * gw + c] = true
        }
        val poly = traceBoundary(closeGrid(occ, gh, gw), gh, gw, minX, minZ, cell) ?: return null

        // 下界：轮廓须包含 ≥50% 轨迹（用户走迹在墙内）
        val inside = track.count { pointInPoly(floatArrayOf(it.first, it.second), poly) }
        if (inside < maxOf(3, track.size / 2)) {
            return listOf(
                floatArrayOf(txMin - 0.4f, tzMin - 0.4f),
                floatArrayOf(txMax + 0.4f, tzMin - 0.4f),
                floatArrayOf(txMax + 0.4f, tzMax + 0.4f),
                floatArrayOf(txMin - 0.4f, tzMax + 0.4f)
            )
        }
        return poly
    }

    /** RANSAC 拟合的墙线（法向 + 偏移 + 内点数 + 方向角°）。 */
    private data class WallLine(val nx: Float, val nz: Float, val d: Float, val inliers: Int, val angleDeg: Float)

    /** D: 从墙证据（点云 XZ + ARCore 墙段端点）RANSAC 拟合多条主导墙线。 */
    private fun ransacWallLines(xz: List<FloatArray>, thr: Float = 0.15f, minInliers: Int = 20, maxLines: Int = 6): List<WallLine> {
        if (xz.size < 4) return emptyList()
        val pts = if (xz.size > 15_000) xz.filterIndexed { i, _ -> i % (xz.size / 15_000 + 1) == 0 } else xz
        if (pts.size < 4) return emptyList()
        val rng = java.util.Random(1)
        var remaining = pts.toMutableList()
        val lines = ArrayList<WallLine>()
        repeat(maxLines) {
            if (remaining.size < 4) return@repeat
            var best: WallLine? = null
            repeat(120) {
                val i = rng.nextInt(remaining.size)
                var j = rng.nextInt(remaining.size); while (j == i) j = rng.nextInt(remaining.size)
                val a = remaining[i]; val b = remaining[j]
                val dx = b[0] - a[0]; val dz = b[1] - a[1]
                val len = sqrt(dx * dx + dz * dz)
                if (len < 0.2f) return@repeat
                val nx = -dz / len; val nz = dx / len
                val d = -(nx * a[0] + nz * a[1])
                var cnt = 0
                for (p in remaining) if (abs(nx * p[0] + nz * p[1] + d) < thr) cnt++
                if (cnt >= minInliers && (best == null || cnt > best.inliers)) {
                    best = WallLine(nx, nz, d, cnt, Math.toDegrees(Math.atan2(dz.toDouble(), dx.toDouble())).toFloat())
                }
            }
            val bl = best ?: return@repeat
            // D 精修（借鉴 Deep3D-FloorPlan-Net）：内点最小二乘主成分方向 → 更准墙线
            val inl = remaining.filter { abs(bl.nx * it[0] + bl.nz * it[1] + bl.d) < thr }
            if (inl.size >= 2) {
                var cx = 0f; var cz = 0f
                for (p in inl) { cx += p[0]; cz += p[1] }
                cx /= inl.size; cz /= inl.size
                var sxx = 0f; var sxy = 0f; var syy = 0f
                for (p in inl) { val dx = p[0] - cx; val dz = p[1] - cz; sxx += dx * dx; sxy += dx * dz; syy += dz * dz }
                val ang2 = (0.5 * Math.atan2(2.0 * sxy.toDouble(), (sxx - syy).toDouble())).toFloat()
                val dirx = Math.cos(ang2.toDouble()).toFloat(); val dirz = Math.sin(ang2.toDouble()).toFloat()
                val n2x = -dirz; val n2z = dirx
                val d2 = -(n2x * cx + n2z * cz)
                lines.add(WallLine(n2x, n2z, d2, inl.size, Math.toDegrees(ang2.toDouble()).toFloat()))
            } else {
                lines.add(bl)
            }
            remaining = remaining.filter { abs(lines.last().nx * it[0] + lines.last().nz * it[1] + lines.last().d) >= thr }.toMutableList()
        }
        return lines
    }

    /** B: 主方向轴（墙线方向 mod 90，按内点加权，置信门控 ≥60%）。非正交返回 null。 */
    private fun dominantAxis(lines: List<WallLine>): Float? {
        if (lines.size < 2) return null
        val axes = lines.map { ((it.angleDeg % 180f) + 180f) % 180f % 90f }
        val totalInl = lines.sumOf { it.inliers }
        if (totalInl <= 0) return null
        var bestAxis = axes[0]; var bestScore = 0f
        for (cand in axes) {
            var score = 0f
            for (i in lines.indices) {
                var dd = Math.abs(axes[i] - cand)
                if (dd > 90f) dd = 180f - dd
                if (dd <= 8f) score += lines[i].inliers
            }
            if (score > bestScore) { bestScore = score; bestAxis = cand }
        }
        if (bestScore < 0.6f * totalInl) return null
        return Math.toRadians(bestAxis.toDouble()).toFloat()
    }

    /** B: 正交化——旋转到主轴系 → 近轴判定 → 顶点吸附 0.05 网格拉直 → 旋转回。非正交返回 null。 */
    private fun orthogonalizeOutline(poly: List<FloatArray>, axisRad: Float): List<FloatArray>? {
        val c = Math.cos(-axisRad.toDouble()); val s = Math.sin(-axisRad.toDouble())
        val c2 = Math.cos(axisRad.toDouble()); val s2 = Math.sin(axisRad.toDouble())
        fun rot(p: FloatArray) = floatArrayOf((p[0] * c - p[1] * s).toFloat(), (p[0] * s + p[1] * c).toFloat())
        fun rotBack(p: FloatArray) = floatArrayOf((p[0] * c2 - p[1] * s2).toFloat(), (p[0] * s2 + p[1] * c2).toFloat())
        val rp = poly.map { rot(it) }
        val n = rp.size
        var near = 0
        for (i in 0 until n) {
            val a = rp[i]; val b = rp[(i + 1) % n]
            val ang = Math.toDegrees(Math.atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble()))
            var aa = ang; while (aa > 90) aa -= 180; while (aa < -90) aa += 180
            if (abs(aa) < 8f || abs(abs(aa) - 90f) < 8f) near++
        }
        if (near < 0.7f * n) return null
        val grid = 0.05f
        val out = rp.map { floatArrayOf(Math.round(it[0] / grid) * grid, Math.round(it[1] / grid) * grid) }
        val dd = simplifyCollinear(dedupeConsecutive(out))
        return dd.map { rotBack(it) }
    }

    /** C: Douglas-Peucker 简化（去阶梯/毛刺，保留整体形状）。 */
    private fun simplifyDP(poly: List<FloatArray>, tol: Float): List<FloatArray> {
        if (poly.size <= 3) return poly
        var i0 = 0; var i1 = 0; var maxD = -1f
        for (i in poly.indices) for (j in i + 1 until poly.size) {
            val d = hypot(poly[i][0] - poly[j][0], poly[i][1] - poly[j][1])
            if (d > maxD) { maxD = d; i0 = i; i1 = j }
        }
        fun distToSeg(p: FloatArray, a: FloatArray, b: FloatArray): Float {
            val dx = b[0] - a[0]; val dz = b[1] - a[1]
            val len2 = dx * dx + dz * dz
            if (len2 < 1e-9f) return hypot(p[0] - a[0], p[1] - a[1])
            var t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / len2
            t = t.coerceIn(0f, 1f)
            return hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dz))
        }
        fun chain(from: Int, to: Int): List<FloatArray> {
            var maxDist = -1f; var idx = -1
            var i = (from + 1) % poly.size
            while (i != to) {
                val d = distToSeg(poly[i], poly[from], poly[to])
                if (d > maxDist) { maxDist = d; idx = i }
                i = (i + 1) % poly.size
            }
            if (maxDist > tol && idx >= 0) return chain(from, idx) + chain(idx, to).drop(1)
            return listOf(poly[from], poly[to])
        }
        val out = chain(i0, i1).dropLast(1) + chain(i1, i0)
        return simplifyCollinear(dedupeConsecutive(out))
    }

    /** C: 毛刺/短枝去除——相邻两边都过短的顶点删掉（借鉴 RRCE_2D 鲁棒轮廓的去噪思想）。 */
    private fun removeSpikes(poly: List<FloatArray>, minLen: Float): List<FloatArray> {
        val n = poly.size
        if (n < 4) return poly
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val a = poly[(i - 1 + n) % n]; val b = poly[i]; val c = poly[(i + 1) % n]
            val e1 = hypot(b[0] - a[0], b[1] - a[1])
            val e2 = hypot(c[0] - b[0], c[1] - b[1])
            if (e1 < minLen && e2 < minLen) continue
            out.add(b)
        }
        return if (out.size < 3) poly else simplifyCollinear(dedupeConsecutive(out))
    }

    /**
     * 改进算法（含 A/B/C/D）：全点密度网格 + ARCore 竖墙证据注入 + 形态学闭合 + 最大分量 + 外轮廓；
     * D 墙线 RANSAC → B 主方向正交化(置信门控) → C DP 简化；下界轨迹含入。
     */
    private fun improvedOutlineWall(
        points: List<FloatArray>,
        track: List<Pair<Float, Float>>,
        wallSegments: List<FloatArray>
    ): List<FloatArray>? {
        if (points.size < 20 || track.size < 3) return null
        val txMin = track.minOf { it.first }; val txMax = track.maxOf { it.first }
        val tzMin = track.minOf { it.second }; val tzMax = track.maxOf { it.second }
        val wallMax = 1.5f
        val x0c = txMin - wallMax; val x1c = txMax + wallMax
        val z0c = tzMin - wallMax; val z1c = tzMax + wallMax
        val inEnv = points.filter { it[0] in x0c..x1c && it[1] in z0c..z1c }
        if (inEnv.size < 20) return null
        val src = if (inEnv.size > 100_000) inEnv.filterIndexed { i, _ -> i % 2 == 0 } else inEnv
        if (src.size < 20) return null

        val cell = 0.2f
        val minX = src.minOf { it[0] }; val maxX = src.maxOf { it[0] }
        val minZ = src.minOf { it[1] }; val maxZ = src.maxOf { it[1] }
        val gw = ceil((maxX - minX) / cell).toInt() + 1
        val gh = ceil((maxZ - minZ) / cell).toInt() + 1
        if (gw > 512 || gh > 512 || gw < 2 || gh < 2) return null
        val occ = BooleanArray(gh * gw)
        fun setCell(x: Float, z: Float) {
            val cc = ((x - minX) / cell).toInt().coerceIn(0, gw - 1)
            val rr = ((z - minZ) / cell).toInt().coerceIn(0, gh - 1)
            occ[rr * gw + cc] = true
        }
        for (p in src) setCell(p[0], p[1])
        // A: 注入 ARCore 竖墙证据（墙段端点 = 精确墙线）
        val wallPts = ArrayList<FloatArray>()
        for (ws in wallSegments) {
            if (ws.size >= 4) {
                setCell(ws[0], ws[1]); setCell(ws[2], ws[3])
                wallPts.add(floatArrayOf(ws[0], ws[1])); wallPts.add(floatArrayOf(ws[2], ws[3]))
            }
        }
        val outline = traceBoundary(closeGrid(occ, gh, gw), gh, gw, minX, minZ, cell) ?: return null

        // D: 墙线 RANSAC（网格内点 + 墙段端点）
        val wallSrc = ArrayList<FloatArray>(src.size + wallPts.size)
        wallSrc.addAll(src)
        wallSrc.addAll(wallPts)
        val lines = ransacWallLines(wallSrc)
        val axis = if (lines.size >= 2) dominantAxis(lines) else null

        // B: 正交化（置信门控）+ C: DP 简化 + 毛刺去除
        var finalPoly = outline
        if (axis != null) {
            val ortho = orthogonalizeOutline(outline, axis)
            if (ortho != null) finalPoly = ortho
        }
        finalPoly = removeSpikes(simplifyDP(finalPoly, 0.25f), 0.3f)

        // 下界：轨迹含入
        val inside = track.count { pointInPoly(floatArrayOf(it.first, it.second), finalPoly) }
        if (inside < maxOf(3, track.size / 2)) {
            return listOf(
                floatArrayOf(txMin - 0.4f, tzMin - 0.4f),
                floatArrayOf(txMax + 0.4f, tzMin - 0.4f),
                floatArrayOf(txMax + 0.4f, tzMax + 0.4f),
                floatArrayOf(txMin - 0.4f, tzMax + 0.4f)
            )
        }
        return finalPoly
    }

    /**
     * 正交网格轮廓法：凹多边形重建（保留 L/U 型房间的内凹缺口，不再把缺口拉成斜线/矩形）。
     * 步骤：点云栅格化 → 膨胀 1 格补缝 → 最大连通域 → Moore 邻域边界追踪 → 去共线。
     * 输入 XZ 点 [x,z]。房间多为正交结构，天然保留直角内凹；斜墙房呈阶梯状边缘（可接受）。
     * 失败（网格过大/连通域过小/面积异常/自交）返回 null，由调用方回退凸包。
     */
    private fun concaveFootprint(points: List<FloatArray>, cell: Float = 0.2f): List<FloatArray>? {
        if (points.size < 3) return null
        val minX = points.minOf { it[0] }; val maxX = points.maxOf { it[0] }
        val minZ = points.minOf { it[1] }; val maxZ = points.maxOf { it[1] }
        val gw = ceil((maxX - minX) / cell).toInt() + 1
        val gh = ceil((maxZ - minZ) / cell).toInt() + 1
        if (gw > 1024 || gh > 1024 || gw < 2 || gh < 2) return null
        val occ = Array(gh) { BooleanArray(gw) }
        for (p in points) {
            val c = ((p[0] - minX) / cell).toInt().coerceIn(0, gw - 1)
            val r = ((p[1] - minZ) / cell).toInt().coerceIn(0, gh - 1)
            occ[r][c] = true
        }

        // 膨胀 1 格：补齐墙线稀疏采样产生的缝
        val dil = Array(gh) { BooleanArray(gw) }
        for (r in 0 until gh) for (c in 0 until gw) {
            var hit = false
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until gh && cc in 0 until gw && occ[rr][cc]) { hit = true; break }
            }
            dil[r][c] = hit
        }

        // 最大连通域（4 邻接），记录最上行最左格作为追踪起点
        val label = Array(gh) { IntArray(gw) }
        val q = ArrayDeque<Pair<Int, Int>>()
        var comp = 0
        var mainComp = 0
        var mainCells = 0
        var startCell: Pair<Int, Int>? = null
        for (r in 0 until gh) for (c in 0 until gw) {
            if (!dil[r][c] || label[r][c] != 0) continue
            comp++
            var size = 0
            var topmost: Pair<Int, Int>? = null
            label[r][c] = comp
            q.add(r to c)
            while (q.isNotEmpty()) {
                val (rr, cc) = q.removeFirst()
                size++
                if (topmost == null || rr < topmost.first) topmost = rr to cc
                for ((dr, dc) in listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)) {
                    val nr = rr + dr; val nc = cc + dc
                    if (nr in 0 until gh && nc in 0 until gw && dil[nr][nc] && label[nr][nc] == 0) {
                        label[nr][nc] = comp
                        q.add(nr to nc)
                    }
                }
            }
            if (size > mainCells) { mainCells = size; mainComp = comp; startCell = topmost }
        }
        if (mainCells < 4) return null
        val keep = Array(gh) { r -> BooleanArray(gw) { c -> label[r][c] == mainComp } }

        // Moore 邻域边界追踪（8 邻接），自最上行最左格沿外轮廓走一圈（含内凹缺口）
        val (b0r, b0c) = startCell ?: return null
        val boundary = mutableListOf(b0r to b0c)
        var br = b0r; var bc = b0c
        var prevR = b0r; var prevC = b0c - 1   // 起点西侧为背景
        val dirs = listOf(
            0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1, 1 to 0, 1 to -1   // 自西顺时针
        )
        var guard = 0
        while (true) {
            if (++guard > 1_000_000) return null
            val dirIdx = dirs.indexOf(prevR - br to prevC - bc)
            if (dirIdx < 0) return null
            var next: Pair<Int, Int>? = null
            for (k in 1..8) {
                val (dr, dc) = dirs[(dirIdx + k) and 7]
                val nr = br + dr; val nc = bc + dc
                if (nr in 0 until gh && nc in 0 until gw && keep[nr][nc]) { next = nr to nc; break }
            }
            val (nr, nc) = next ?: return null
            prevR = br; prevC = bc
            br = nr; bc = nc
            if (br == b0r && bc == b0c) break
            boundary.add(br to bc)
        }
        if (boundary.size < 4) return null

        // 格 → 世界坐标（格中心，较墙面内缩半格，保证物体在域内）
        val raw = boundary.map { floatArrayOf(minX + (it.second + 0.5f) * cell, minZ + (it.first + 0.5f) * cell) }
        return simplifyCollinear(dedupeConsecutive(raw))
    }

    /** 去连续重复点（首尾相同含其中）。 */
    private fun dedupeConsecutive(pts: List<FloatArray>): List<FloatArray> {
        if (pts.size < 2) return pts
        val out = ArrayList<FloatArray>(pts.size)
        for (p in pts) {
            val last = out.lastOrNull()
            if (last != null && last[0] == p[0] && last[1] == p[1]) continue
            out.add(p)
        }
        if (out.size >= 2) {
            val f = out.first(); val l = out.last()
            if (f[0] == l[0] && f[1] == l[1]) out.removeAt(out.size - 1)
        }
        return out
    }

    /** 去共线点（相邻三点叉积≈0 则删中间点，迭代至稳定）。 */
    private fun simplifyCollinear(pts: List<FloatArray>): List<FloatArray> {
        var cur = pts
        while (cur.size >= 3) {
            var changed = false
            val out = ArrayList<FloatArray>(cur.size)
            val n = cur.size
            for (i in 0 until n) {
                val a = cur[(i - 1 + n) % n]; val b = cur[i]; val c = cur[(i + 1) % n]
                val cross = (b[0]-a[0])*(c[1]-b[1]) - (b[1]-a[1])*(c[0]-b[0])
                if (Math.abs(cross) < 1e-6f) { changed = true; continue }
                out.add(b)
            }
            if (!changed || out.size < 3) break
            cur = out
        }
        return cur
    }

    /** 简单多边形判定（非相邻边不相交）。 */
    private fun isSimple(p: List<FloatArray>): Boolean {
        val n = p.size
        if (n < 3) return false
        for (i in 0 until n) {
            val a1 = p[i]; val a2 = p[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val b1 = p[j]; val b2 = p[(j + 1) % n]
                if (segmentsIntersect(a1, a2, b1, b2)) return false
            }
        }
        return true
    }

    /** 线段相交判定（含共享端点不计）。 */
    private fun segmentsIntersect(a1: FloatArray, a2: FloatArray, b1: FloatArray, b2: FloatArray): Boolean {
        fun orient(p: FloatArray, q: FloatArray, r: FloatArray): Float =
            (q[0]-p[0])*(r[1]-p[1]) - (q[1]-p[1])*(r[0]-p[0])
        fun onSeg(p: FloatArray, a: FloatArray, b: FloatArray): Boolean =
            p[0] in minOf(a[0], b[0])..maxOf(a[0], b[0]) && p[1] in minOf(a[1], b[1])..maxOf(a[1], b[1])
        val o1 = orient(a1, a2, b1); val o2 = orient(a1, a2, b2)
        val o3 = orient(b1, b2, a1); val o4 = orient(b1, b2, a2)
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) return true
        return (o1 == 0f && onSeg(b1, a1, a2)) || (o2 == 0f && onSeg(b2, a1, a2)) ||
               (o3 == 0f && onSeg(a1, b1, b2)) || (o4 == 0f && onSeg(a2, b1, b2))
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
