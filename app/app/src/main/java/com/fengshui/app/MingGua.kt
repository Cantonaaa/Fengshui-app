package com.fengshui.app

import org.json.JSONObject

/**
 * 命卦计算：生辰 → 三元命卦 → 东四/西四命 → 四吉方/四凶方（八宅大游年）。
 * 数据源：assets/bagua_data.json（youNianOrder + xing）。
 *
 * 算法：
 *  1. 三元命卦：男 (100 - 生年后两位) mod 9，女 (生年后两位 - 4) mod 9（余0按9）
 *  2. 大游年：本卦起伏位，自本卦方位顺时针起，8 个方位依次取游年序索引 [7,0,2,1,3,4,6,5] 对应之星
 *  3. 生气/延年/天乙/伏位 = 吉方，绝命/五鬼/六煞/祸害 = 凶方
 * 注：按公历年近似（立春边界未细分，MVP 用）。
 */
object MingGua {

    data class GuaInfo(
        val trigram: String,     // 坎/坤/震/巽/乾/兑/艮/离
        val group: String,       // 东四命 / 西四命
        val goodSectors: List<String>,  // 四吉方
        val badSectors: List<String>    // 四凶方
    ) {
        val summary: String
            get() = "卦主${trigram}命（$group）· 吉方：${goodSectors.joinToString("/")} · 凶方：${badSectors.joinToString("/")}"
    }

    private val TRIGRAM_DIR = mapOf(
        "坎" to "北", "艮" to "东北", "震" to "东", "巽" to "东南",
        "离" to "南", "坤" to "西南", "兑" to "西", "乾" to "西北"
    )
    private val COMPASS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    private val INDEX_ORDER = intArrayOf(7, 0, 2, 1, 3, 4, 6, 5)   // 本卦顺时针 8 方位对应的游年序索引
    private val GOOD_STARS = setOf("生气", "延年", "天乙", "伏位")

    /** 三元命卦。birthYear 为公历年；gender ∈ {男, 女}。 */
    fun trigram(birthYear: Int, gender: String): String {
        val y = ((birthYear % 100) + 100) % 100
        val r = if (gender == "男") {
            val v = (100 - y) % 9; if (v == 0) 9 else v
        } else {
            val v = ((y - 4) % 9 + 9) % 9; if (v == 0) 9 else v
        }
        return when (r) {
            1 -> "坎"; 2 -> "坤"; 3 -> "震"; 4 -> "巽"
            5 -> if (gender == "男") "坤" else "艮"
            6 -> "乾"; 7 -> "兑"; 8 -> "艮"; 9 -> "离"
            else -> "?"
        }
    }

    fun group(trigram: String): String =
        if (trigram in setOf("震", "巽", "坎", "离")) "东四命" else "西四命"

    /** 由生年/性别 + bagua_data.json 原文计算四吉方/四凶方。 */
    fun compute(birthYear: Int, gender: String, baguaJson: String): GuaInfo {
        val t = trigram(birthYear, gender)
        val g = group(t)
        if (t == "?") return GuaInfo(t, g, emptyList(), emptyList())

        val root = JSONObject(baguaJson)
        val orderStr = root.getJSONObject("youNianOrder").getString(t)  // 8 星，每星2字
        val stars = orderStr.chunked(2)
        if (stars.size != 8) return GuaInfo(t, g, emptyList(), emptyList())

        val base = TRIGRAM_DIR[t] ?: return GuaInfo(t, g, emptyList(), emptyList())
        val startIdx = COMPASS.indexOf(base)
        val dirs = (0 until 8).map { COMPASS[(startIdx + it) % 8] }

        val good = mutableListOf<String>()
        val bad = mutableListOf<String>()
        for (i in 0 until 8) {
            val star = stars.getOrNull(INDEX_ORDER[i]) ?: continue
            if (star in GOOD_STARS) good.add(dirs[i]) else bad.add(dirs[i])
        }
        return GuaInfo(t, g, good, bad)
    }
}
