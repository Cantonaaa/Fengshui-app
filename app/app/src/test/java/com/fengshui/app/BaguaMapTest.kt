package com.fengshui.app

import com.fengshui.solver.Pt
import org.junit.Test

class BaguaMapTest {

    private val center = Pt(2.0, 2.0)

    @Test
    fun project_northPointsUp() {
        // (2,3.5) 在中心正北(+z)，northAngle=0 → 显示坐标 y 为负(向上)
        val (x, y) = BaguaMap.project(Pt(2.0, 3.5), center, 0.0)
        assert(kotlin.math.abs(x) < 1e-6)
        assert(y < 0)   // 北 → 顶部
        assert(kotlin.math.abs(y + 1.5) < 1e-6)
    }

    @Test
    fun project_eastPointsRight() {
        // (3.5,2) 在中心正东(+x) → 显示坐标 x 为正(向右)
        val (x, y) = BaguaMap.project(Pt(3.5, 2.0), center, 0.0)
        assert(x > 0)
        assert(kotlin.math.abs(y) < 1e-6)
        assert(kotlin.math.abs(x - 1.5) < 1e-6)
    }

    @Test
    fun project_northAngleRotates() {
        // northAngle=π/2：北(0°)顺时针转 -90°(在显示角为 270°=西/左)
        val (x, y) = BaguaMap.project(Pt(2.0, 3.5), center, kotlin.math.PI / 2)
        assert(kotlin.math.abs(x + 1.5) < 1e-6)   // 移到左
        assert(kotlin.math.abs(y) < 1e-6)
    }

    @Test
    fun fitScale_returnsPositive() {
        val pts = listOf(Pair(0.0, 0.0), Pair(3.0, 2.0), Pair(-1.0, -1.0))
        val s = BaguaMap.fitScale(pts, 300f, 300f, 20f)
        assert(s > 0f && s < 300f)
    }
}
