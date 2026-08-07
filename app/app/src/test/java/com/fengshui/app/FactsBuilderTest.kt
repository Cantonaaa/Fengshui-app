package com.fengshui.app

import com.fengshui.solver.Movability
import com.fengshui.solver.Placement
import com.fengshui.solver.Pt
import org.junit.Test

class FactsBuilderTest {

    @Test
    fun build_mapsTypesAndDims() {
        val objects = listOf(
            ScanObject("dining table", 1.0, 1.0),   // → dining
            ScanObject("refrigerator", 3.0, 2.0),   // → fridge
            ScanObject("stove", 2.0, 1.0),
            ScanObject("plant", 0.5, 0.5),
            ScanObject("pillar", 4.0, 3.0)
        )
        val facts = FactsBuilder.build(objects, listOf(Pt(0.0, 0.0), Pt(5.0, 0.0), Pt(5.0, 4.0), Pt(0.0, 4.0)), 0.0)
        val byType = facts.objects.associateBy { it.type }
        assert(byType.keys == setOf("dining", "fridge", "stove", "plant", "pillar"))
        // 尺寸映射
        assert(byType["dining"]!!.dimX == 1.5 && byType["dining"]!!.dimZ == 0.8)
        assert(byType["fridge"]!!.dimX == 0.7)
        // 属性
        assert(byType["plant"]!!.placement == Placement.FREESTANDING && byType["plant"]!!.movability == Movability.MOVABLE)
        assert(byType["stove"]!!.placement == Placement.WALL_NEEDING && byType["stove"]!!.movability == Movability.FIXED)
        assert(byType["pillar"]!!.placement == Placement.FREESTANDING && byType["pillar"]!!.movability == Movability.FIXED)
        // 墙段 = 多边形边
        assert(facts.walls.size == 4)
    }

    @Test
    fun build_usesMeasuredDims() {
        val objects = listOf(ScanObject("bed", 1.0, 1.0, dimX = 2.4, dimZ = 2.0))
        val facts = FactsBuilder.build(objects, listOf(Pt(0.0, 0.0), Pt(5.0, 0.0), Pt(5.0, 4.0), Pt(0.0, 4.0)), 0.0)
        assert(facts.objects[0].dimX == 2.4)
    }

    @Test
    fun boundingPolygon_rectAroundObjects() {
        val objects = listOf(ScanObject("bed", 2.0, 2.0), ScanObject("sofa", 3.0, 1.0))
        val poly = FactsBuilder.boundingPolygon(objects)
        assert(poly.size == 4)
        assert(GeoPointIn(poly, 2.5, 1.5))
    }

    private fun GeoPointIn(poly: List<Pt>, x: Double, z: Double): Boolean {
        val p = Pt(x, z)
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z + 1e-12) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

class SizeEstimatorTest {

    @Test
    fun estimate_basic() {
        val (w, h) = SizeEstimator.estimate(100f, 200f, 500f, 500f, 2.0)
        assert(kotlin.math.abs(w - 0.4) < 1e-6)
        assert(kotlin.math.abs(h - 0.8) < 1e-6)
    }

    @Test
    fun estimate_invalid() {
        assert(SizeEstimator.estimate(100f, 200f, 500f, 500f, 0.0) == (0.0 to 0.0))
        assert(SizeEstimator.estimate(100f, 200f, 0f, 500f, 2.0) == (0.0 to 0.0))
    }

    @Test
    fun footprintWidth_clamp() {
        assert(SizeEstimator.footprintWidth(10.0, 2.0) == 3.6)   // 钳到 1.8×
        assert(SizeEstimator.footprintWidth(0.1, 2.0) == 1.2)     // 钳到 0.6×
        assert(SizeEstimator.footprintWidth(2.0, 2.0) == 2.0)
        assert(SizeEstimator.footprintWidth(0.0, 2.0) == 2.0)     // 无效 → 默认
    }
}
