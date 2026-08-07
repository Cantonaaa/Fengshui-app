package com.fengshui.app

import com.fengshui.solver.Pt
import org.junit.Test

class ScanCoverageTest {

    private val room = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0))

    @Test
    fun emptyKeyframes_zeroCoverage() {
        assert(ScanCoverage.coveragePct(emptyList(), room, 3.0) == 0)
    }

    @Test
    fun centerPosition_coversMost() {
        // 4x4 房，相机在中心（R=3）→ 几乎全覆
        val kf = listOf(2.0 to 2.0)
        val pct = ScanCoverage.coveragePct(kf, room, 3.0)
        assert(pct >= 85) { "中心+3m 应覆盖 ≥85%，实际 $pct" }
    }

    @Test
    fun cornerPosition_underCovers() {
        // 6x6 房，只在角落（R=3）→ 覆盖明显不足（远角 >7m 未及）
        val room6 = listOf(Pt(0.0, 0.0), Pt(6.0, 0.0), Pt(6.0, 6.0), Pt(0.0, 6.0))
        val kf = listOf(0.5 to 0.5)
        val pct = ScanCoverage.coveragePct(kf, room6, 3.0)
        assert(pct < 75) { "单角落覆盖应 <75%，实际 $pct" }
    }

    @Test
    fun fullPerimeter_coversAll() {
        val kf = listOf(
            0.5 to 0.5, 2.0 to 0.5, 3.5 to 0.5, 3.5 to 2.0, 3.5 to 3.5,
            2.0 to 3.5, 0.5 to 3.5, 0.5 to 2.0, 2.0 to 2.0
        )
        val pct = ScanCoverage.coveragePct(kf, room, 3.0)
        assert(pct >= 90) { "四周+中心覆盖应 ≥90%，实际 $pct" }
    }
}
