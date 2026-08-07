package com.fengshui.app

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar

/**
 * 公历/农历生辰换算。
 * 命卦（三元九宫）按农历生年计算：公历输入先转农历；农历输入直接用。
 * 年界：农历年以春节为界（立春细分暂不启用）。
 */
object LunarUtil {

    /** 输入生辰（公历或农历）→ 农历年（用于命卦）。无效输入返回 null。 */
    fun lunarYear(isLunar: Boolean, year: Int, month: Int, day: Int): Int? {
        return try {
            if (isLunar) {
                if (month !in 1..12 || day !in 1..30) return null
                Lunar.fromYmd(year, month, day).year
            } else {
                if (month !in 1..12 || day !in 1..31) return null
                Solar.fromYmd(year, month, day).lunar.year
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 显示用：公历 → 农历年月日。无效返回 null。 */
    fun solarToLunar(year: Int, month: Int, day: Int): Triple<Int, Int, Int>? {
        return try {
            val l = Solar.fromYmd(year, month, day).lunar
            Triple(l.year, l.month, l.day)
        } catch (e: Exception) {
            null
        }
    }

    /** 显示用：农历 → 公历年月日。无效返回 null。 */
    fun lunarToSolar(year: Int, month: Int, day: Int): Triple<Int, Int, Int>? {
        return try {
            val s = Lunar.fromYmd(year, month, day).solar
            Triple(s.year, s.month, s.day)
        } catch (e: Exception) {
            null
        }
    }

    /** 农历月名（正/二/.../冬/腊）。 */
    fun lunarMonthName(month: Int): String = when (month) {
        1 -> "正月"; 2 -> "二月"; 3 -> "三月"; 4 -> "四月"; 5 -> "五月"; 6 -> "六月"
        7 -> "七月"; 8 -> "八月"; 9 -> "九月"; 10 -> "十月"; 11 -> "冬月"; 12 -> "腊月"
        else -> "${month}月"
    }

    fun lunarDayName(day: Int): String = when (day) {
        1 -> "初一"; 2 -> "初二"; 3 -> "初三"; 4 -> "初四"; 5 -> "初五"; 6 -> "初六"
        7 -> "初七"; 8 -> "初八"; 9 -> "初九"; 10 -> "初十"; 11 -> "十一"; 12 -> "十二"
        13 -> "十三"; 14 -> "十四"; 15 -> "十五"; 16 -> "十六"; 17 -> "十七"; 18 -> "十八"
        19 -> "十九"; 20 -> "二十"; 21 -> "廿一"; 22 -> "廿二"; 23 -> "廿三"; 24 -> "廿四"
        25 -> "廿五"; 26 -> "廿六"; 27 -> "廿七"; 28 -> "廿八"; 29 -> "廿九"; 30 -> "三十"
        else -> "${day}日"
    }
}
