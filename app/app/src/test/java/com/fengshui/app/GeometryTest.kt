package com.fengshui.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Test

class ObjectLocalizerTest {

    private fun snap(ox: Float, oy: Float, oz: Float, qx: Float, qy: Float, qz: Float, qw: Float) =
        ObjectLocalizer.CameraSnapshot(500f, 500f, 320f, 240f, ox, oy, oz, qx, qy, qz, qw)

    @Test
    fun cameraForwardHeading_identity_and_yaw() {
        // 单位四元数：forward = (0,0,-1) → heading = atan2(0,-1) = π
        val id = ObjectLocalizer.cameraForwardHeading(snap(0f, 0f, 0f, 0f, 0f, 0f, 1f))
        assert(abs(id - PI) < 1e-4)
        // +90° yaw（绕 Y）：forward = (-1,0,0) → heading = atan2(-1,0) = -π/2
        val s = 0.70710678f
        val yaw = ObjectLocalizer.cameraForwardHeading(snap(0f, 0f, 0f, 0f, s, 0f, s))
        assert(abs(yaw - (-PI / 2)) < 1e-4)
    }

    @Test
    fun projectToFloor_rayDown_hitsFloor() {
        // 相机原点 (0,1.5,0)，物体中心在图像中心偏下 → 射线朝下 → 命中 y=0 地板
        val cam = snap(0f, 1.5f, 0f, 0f, 0f, 0f, 1f)
        val pos = ObjectLocalizer.projectToFloor(cam, 320f, 340f, 0f)
        assert(pos != null)
        assert(abs(pos!![1] - 0f) < 1e-3)
        assert(abs(pos[0]) < 1e-4)   // 射线无横向分量
        assert(pos[2] < 0f)          // 前向为 -z，命中点在前方
    }

    @Test
    fun projectToFloor_invalidFloorHeight_null() {
        val cam = snap(0f, 1.5f, 0f, 0f, 0f, 0f, 1f)
        assert(ObjectLocalizer.projectToFloor(cam, 320f, 340f, Float.MAX_VALUE) == null)
    }
}

class RoomPolygonTest {

    /** 生成合成点云：房间地板(密) + 墙(竖面) + 窗外幻影簇(稀) + 散点。 */
    private fun makeScene(): List<FloatArray> {
        val pts = mutableListOf<FloatArray>()
        // 地板：x∈[0,4], z∈[0,3]，间距 0.15，y≈0
        var x = 0f
        while (x <= 4f) {
            var z = 0f
            while (z <= 3f) {
                pts.add(floatArrayOf(x, (Math.random() * 0.03).toFloat(), z))
                z += 0.15f
            }
            x += 0.15f
        }
        // 墙：四边竖面，y∈[0.1,2.5]
        var y = 0.1f
        while (y <= 2.5f) {
            var xx = 0f
            while (xx <= 4f) { pts.add(floatArrayOf(xx, y, 0f)); pts.add(floatArrayOf(xx, y, 3f)); xx += 0.25f }
            var zz = 0f
            while (zz <= 3f) { pts.add(floatArrayOf(0f, y, zz)); pts.add(floatArrayOf(4f, y, zz)); zz += 0.25f }
            y += 0.25f
        }
        // 窗外幻影簇：z∈[3.5,5] 稀疏（间距 0.6），y≈0 —— 模拟玻璃透射鬼影
        var px = 1f
        while (px <= 3f) {
            var pz = 3.5f
            while (pz <= 5f) {
                pts.add(floatArrayOf(px, 0f, pz))
                pz += 0.6f
            }
            px += 0.6f
        }
        // 散点离群（远处）
        repeat(15) { pts.add(floatArrayOf((Math.random() * 30).toFloat() - 15, 0f, (Math.random() * 30).toFloat() - 15)) }
        return pts
    }

    @Test
    fun buildPolygon_excludesGhostCluster() {
        val poly = RoomPolygon.buildPolygon(makeScene())
        assert(poly != null) { "应能生成户型" }
        val area = poly!!.area
        // 房间 4×3=12；若幻影簇(至 z=5)与散点被纳入，面积会明显大于 15
        assert(area < 15f) { "幻影未被剔除，面积 $area" }
        // 所有顶点 z 不超出房间（幻影在 z>3.5）
        for (v in poly.vertices) {
            assert(v[1] <= 3.2f) { "凸包含幻影顶点: ${v[0]},${v[1]}" }
            assert(v[0] >= -0.2f && v[0] <= 4.2f)
        }
    }

    /** 生成 L 型房间点云：上横臂 [0,4]×[2,4] + 左竖臂 [0,2]×[0,4]，缺口 [2,4]×[0,2] 无点。 */
    private fun makeLScene(): List<FloatArray> {
        val pts = mutableListOf<FloatArray>()
        fun inL(x: Float, z: Float): Boolean = (z >= 2f) || (x <= 2f)
        // 地板：臂内密铺
        var x = 0f
        while (x <= 4f) {
            var z = 0f
            while (z <= 4f) {
                if (inL(x, z)) pts.add(floatArrayOf(x, (Math.random() * 0.03).toFloat(), z))
                z += 0.12f
            }
            x += 0.12f
        }
        // 墙竖面（含缺口内角两壁），y∈[0.1,2]
        var y = 0.1f
        while (y <= 2f) {
            var xx = 0f
            while (xx <= 4f) {
                pts.add(floatArrayOf(xx, y, 0f)); pts.add(floatArrayOf(xx, y, 4f))
                pts.add(floatArrayOf(2f, y, xx)); pts.add(floatArrayOf(4f, y, xx))  // 内角垂直壁 x=2
                xx += 0.3f
            }
            pts.add(floatArrayOf(0f, y, 0f))
            y += 0.3f
        }
        return pts
    }

    @Test
    fun buildPolygon_lShapeKeepsNotch() {
        val poly = RoomPolygon.buildPolygon(makeLScene())
        assert(poly != null) { "应能生成 L 型户型" }
        val area = poly!!.area
        // L 真面积 12；外接矩形 16。若缺口被拉平（矩形/凸包），面积将接近 16
        assert(area < 14.5f) { "缺口被填平，面积 $area（>14.5）" }
        assert(area > 10f) { "面积异常偏小 $area" }
        // 必须存在内凹角顶点（缺口角 ≈ (2,2)），否则说明缺口被拉成斜线/矩形
        val hasNotch = poly.vertices.any { v ->
            abs(v[0] - 2.3f) < 0.7f && abs(v[1] - 2.3f) < 0.7f
        }
        assert(hasNotch) { "无内凹角顶点，顶点=${poly.vertices.map { "(${it[0]},${it[1]})" }}" }
    }
}
