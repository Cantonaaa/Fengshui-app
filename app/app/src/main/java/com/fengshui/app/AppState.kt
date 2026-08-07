package com.fengshui.app

import android.content.Context
import kotlin.jvm.Synchronized

/** 扫描中检测到的一个物体（世界坐标，地面投影 + 估算占地尺寸）。 */
data class ScanObject(
    val type: String,
    val x: Double,
    val z: Double,
    val dimX: Double? = null,
    val dimZ: Double? = null
)

/**
 * 全局应用状态：生辰/命卦、北向（AR世界帧→磁北偏移弧度）、扫描物体清单。
 * 生辰与北向持久化于私有 SharedPreferences（本地仅存）。
 */
object AppState {

    var birthYear: Int? = null
    var birthMonth: Int? = null
    var birthDay: Int? = null
    var birthIsLunar: Boolean = false      // true=农历输入, false=公历输入
    var gender: String? = null          // 男 / 女
    var guaInfo: MingGua.GuaInfo? = null
    var northAngle: Double? = null      // sector() 用：世界帧方位 → 磁北 偏移（弧度）
    var baguaJson: String = "{}"        // 缓存 assets/bagua_data.json

    val scanObjects = mutableListOf<ScanObject>()
    var analysisResult: AnalysisResult? = null

    /** 用于命卦的农历年（公历输入先转农历）。 */
    fun effectiveLunarYear(): Int? =
        if (birthYear != null && birthMonth != null && birthDay != null)
            LunarUtil.lunarYear(birthIsLunar, birthYear!!, birthMonth!!, birthDay!!)
        else null

    // 原始检测缓冲（跨线程追加），分析时聚类成真实物体
    private val detBuffer = mutableListOf<ScanObject>()
    var unknownCount = 0   // 分类置信度不足（不在类别内）的检测数

    /** 新扫描会话：清空上一轮检测与未识别计数。 */
    @Synchronized
    fun resetScan() {
        detBuffer.clear()
        unknownCount = 0
        analysisResult = null
    }

    @Synchronized
    fun recordUnknown() { unknownCount++ }

    /**
     * 缓冲一条检测（不立即合并）。跨线程安全。
     * 移动扫描中同一物体投影位置漂移大，按"分析时聚类"更稳。
     */
    @Synchronized
    fun recordObject(type: String, x: Double, z: Double, dimX: Double? = null, dimZ: Double? = null) {
        detBuffer.add(ScanObject(type, x, z, dimX, dimZ))
        if (detBuffer.size > 6000) detBuffer.removeAt(0)   // 防无限增长
    }

    /** 聚类：同类型 2.5m 内合并取质心；≥3 次检测才计为物体（剔除孤立噪声簇）。 */
    @Synchronized
    fun snapshotObjects(): List<ScanObject> {
        val result = mutableListOf<ScanObject>()
        for (type in detBuffer.map { it.type }.distinct()) {
            val pts = detBuffer.filter { it.type == type }
            for (cl in greedyCluster(pts, 2.5)) {
                if (cl.size < 3) continue   // 孤立/稀疏检测不计
                val cx = cl.map { it.x }.average()
                val cz = cl.map { it.z }.average()
                val dx = cl.mapNotNull { it.dimX }.takeIf { it.isNotEmpty() }?.average()
                val dz = cl.mapNotNull { it.dimZ }.takeIf { it.isNotEmpty() }?.average()
                result.add(ScanObject(type, cx, cz, dx, dz))
            }
        }
        return result
    }

    /** 贪心聚类：点与某簇质心距离 ≤ radius 则并入，否则新建簇。 */
    private fun greedyCluster(pts: List<ScanObject>, radius: Double): List<List<ScanObject>> {
        val clusters = mutableListOf<MutableList<ScanObject>>()
        for (p in pts) {
            val c = clusters.firstOrNull { cl ->
                val cx = cl.map { it.x }.average()
                val cz = cl.map { it.z }.average()
                Math.hypot(p.x - cx, p.z - cz) <= radius
            }
            if (c != null) c.add(p) else clusters.add(mutableListOf(p))
        }
        return clusters
    }

    fun refreshGua() {
        val ly = effectiveLunarYear()
        guaInfo = if (ly != null && gender != null && baguaJson.isNotEmpty() && baguaJson != "{}")
            MingGua.compute(ly, gender!!, baguaJson)
        else null
    }

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        birthYear = if (sp.contains("birthYear")) sp.getInt("birthYear", 0) else null
        birthMonth = if (sp.contains("birthMonth")) sp.getInt("birthMonth", 0) else null
        birthDay = if (sp.contains("birthDay")) sp.getInt("birthDay", 0) else null
        birthIsLunar = sp.getBoolean("birthIsLunar", false)
        gender = sp.getString("gender", null)
        northAngle = if (sp.contains("northAngle")) sp.getFloat("northAngle", 0f).toDouble() else null
        refreshGua()
    }

    fun save(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            if (birthYear != null) putInt("birthYear", birthYear!!) else remove("birthYear")
            if (birthMonth != null) putInt("birthMonth", birthMonth!!) else remove("birthMonth")
            if (birthDay != null) putInt("birthDay", birthDay!!) else remove("birthDay")
            putBoolean("birthIsLunar", birthIsLunar)
            if (gender != null) putString("gender", gender) else remove("gender")
            if (northAngle != null) putFloat("northAngle", northAngle!!.toFloat()) else remove("northAngle")
            apply()
        }
    }

    fun hasBirth(): Boolean =
        birthYear != null && birthMonth != null && birthDay != null && gender != null && guaInfo != null
    fun hasNorth(): Boolean = northAngle != null

    /** 生辰显示文本（含历法）。 */
    fun birthSummary(): String? {
        if (!hasBirth()) return null
        val y = birthYear!!; val m = birthMonth!!; val d = birthDay!!
        return if (birthIsLunar)
            "农历${y}年${LunarUtil.lunarMonthName(m)}${LunarUtil.lunarDayName(d)}"
        else
            "${y}年${m}月${d}日（公历）"
    }

    private const val PREFS = "fengshui_prefs"
}
