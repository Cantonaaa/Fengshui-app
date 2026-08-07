package com.fengshui.app

import org.junit.Test

class LunarUtilTest {

    @Test
    fun solarToLunar_yearBoundary() {
        // 1990 春节 = 1月27日：1月25日仍在农历 1989 年
        assert(LunarUtil.lunarYear(false, 1990, 1, 25) == 1989)
        assert(LunarUtil.lunarYear(false, 1990, 2, 15) == 1990)
    }

    @Test
    fun lunarInput_directYear() {
        assert(LunarUtil.lunarYear(true, 1989, 12, 25) == 1989)
        assert(LunarUtil.lunarYear(true, 1990, 1, 1) == 1990)
    }

    @Test
    fun lunarToSolar_springFestival() {
        val s = LunarUtil.lunarToSolar(1990, 1, 1)
        assert(s != null && s.first == 1990 && s.second == 1 && s.third == 27)
    }

    @Test
    fun solarToLunar_name() {
        val l = LunarUtil.solarToLunar(1990, 1, 27)
        assert(l != null && l.first == 1990 && l.second == 1 && l.third == 1)
        assert(LunarUtil.lunarMonthName(1) == "正月")
        assert(LunarUtil.lunarDayName(1) == "初一")
    }

    @Test
    fun invalidInputs_null() {
        assert(LunarUtil.lunarYear(true, 1990, 13, 1) == null)   // 无13月
        assert(LunarUtil.lunarYear(true, 1990, 2, 31) == null)   // 无31日
        assert(LunarUtil.lunarYear(false, 1990, 2, 30) == null)  // 公历2月无30日
    }

    @Test
    fun mingGua_usesLunarYear() {
        // 男：公历 1990-01-25（农历1989）→ 坤；1990-02-15（农历1990）→ 坎
        val g1 = MingGua.compute(LunarUtil.lunarYear(false, 1990, 1, 25)!!, "男", TestData.BAGUA_JSON)
        val g2 = MingGua.compute(LunarUtil.lunarYear(false, 1990, 2, 15)!!, "男", TestData.BAGUA_JSON)
        assert(g1.trigram == "坤") { "1990-01-25(农历1989)男应为坤，实际 ${g1.trigram}" }
        assert(g2.trigram == "坎") { "1990-02-15(农历1990)男应为坎，实际 ${g2.trigram}" }
    }
}
