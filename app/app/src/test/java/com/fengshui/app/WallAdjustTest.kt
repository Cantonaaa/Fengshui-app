package com.fengshui.app

import com.fengshui.solver.Pt
import kotlin.math.abs
import org.junit.Test

class WallAdjustTest {

    private val room = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 3.0), Pt(0.0, 3.0))

    private fun obj(type: String, x: Double, z: Double) =
        ScanObject(type, x, z, dimX = null, dimZ = null)

    @Test
    fun embeddedWardrobe_snapsToWall_andMeasuresDepth() {
        // 嵌入式衣柜：检测框底未达地面 → 投影偏前 (z≈0.05)；应吸附到墙边 z≈0.45，进深≈0.9
        val out = WallAdjust.apply(listOf(obj("wardrobe", 1.0, 0.05)), room)
        assert(out.size == 1)
        assert(abs(out[0].z - 0.45) < 0.05) { "应吸附到墙边，实际 z=${out[0].z}" }
        assert(abs(out[0].x - 1.0) < 1e-6)
        assert(abs((out[0].dimZ ?: 0.0) - 0.9) < 0.1) { "进深应≈0.9，实际 ${out[0].dimZ}" }
    }

    @Test
    fun freestandingPlant_untouched() {
        val p = obj("plant", 2.0, 1.5)
        val out = WallAdjust.apply(listOf(p), room)
        assert(out[0].x == p.x && out[0].z == p.z)
        assert(out[0].dimZ == null)
    }

    @Test
    fun flushDoor_untouched() {
        val d = obj("door", 2.0, 0.05)
        val out = WallAdjust.apply(listOf(d), room)
        assert(out[0].z == 0.05) { "齐平类(门)不应调整，实际 ${out[0].z}" }
    }

    @Test
    fun notNearWall_untouched() {
        // 衣柜 snapThresh = 0.6/2+0.6 = 0.9；z=1.8 距最近墙 1.2 > 0.9 → 不调整
        val w = obj("wardrobe", 2.0, 1.8)
        val out = WallAdjust.apply(listOf(w), room)
        assert(out[0].x == 2.0 && out[0].z == 1.8)
    }

    @Test
    fun noPolygon_untouched() {
        val w = obj("wardrobe", 1.0, 0.05)
        val out = WallAdjust.apply(listOf(w), emptyList())
        assert(out[0].z == 0.05)
    }

    @Test
    fun heightConsistent_filtersPartialAndAbsurd() {
        // 衣柜典型高 2.1，下界 0.3×2.1=0.63，上界 1.8×2.1=3.78
        assert(!heightConsistent(0.5, "wardrobe"))  // 只框到局部 → 过滤
        assert(heightConsistent(1.2, "wardrobe"))   // 合理
        assert(heightConsistent(3.5, "wardrobe"))   // 略超真值但容忍
        // 床典型高 0.55，下界 0.45×0.55=0.2475，上界 0.99
        assert(!heightConsistent(0.15, "bed"))
        assert(heightConsistent(0.6, "bed"))
        assert(!heightConsistent(2.0, "bed"))       // 异常高 → 过滤
        // 无效高度不拦截
        assert(heightConsistent(0.0, "bed"))
        assert(heightConsistent(Double.NaN, "bed"))
    }
}

class CanonicalizeTest {
    private fun obj(type: String, x: Double, z: Double) = ScanObject(type, x, z)

    @Test fun bookshelfWithBookNearby_becomesStudy() {
        val out = PostScanProcessor.canonicalizeScan(listOf(
            obj("bookshelf", 1.0, 1.0), obj("book", 1.2, 1.1)
        ))
        assert(out.size == 1) { "书应作验证器丢弃，bookshelf→study" }
        assert(out[0].type == "study")
    }

    @Test fun bookshelfAlone_dropped() {
        val out = PostScanProcessor.canonicalizeScan(listOf(obj("bookshelf", 1.0, 1.0)))
        assert(out.isEmpty()) { "无书则丢弃（防衣柜误判）" }
    }

    @Test fun bookshelfFarFromBook_dropped() {
        val out = PostScanProcessor.canonicalizeScan(listOf(
            obj("bookshelf", 1.0, 1.0), obj("book", 5.0, 5.0)
        ))
        assert(out.isEmpty()) { "书距>2m 不应确认" }
    }

    @Test fun safe_becomesFinanceRoom() {
        val out = PostScanProcessor.canonicalizeScan(listOf(obj("safe", 2.0, 2.0)))
        assert(out.size == 1 && out[0].type == "finance_room")
    }

    @Test fun waterShrine_unchanged() {
        val out = PostScanProcessor.canonicalizeScan(listOf(obj("water", 1.0, 1.0), obj("shrine", 3.0, 3.0)))
        assert(out.map { it.type } == listOf("water", "shrine"))
    }

    @Test fun baseObjects_unchanged() {
        val out = PostScanProcessor.canonicalizeScan(listOf(obj("bed", 1.0, 1.0), obj("wardrobe", 3.0, 3.0)))
        assert(out.map { it.type } == listOf("bed", "wardrobe"))
    }
}
