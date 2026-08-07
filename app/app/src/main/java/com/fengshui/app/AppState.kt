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
    var gender: String? = null          // 男 / 女
    var guaInfo: MingGua.GuaInfo? = null
    var northAngle: Double? = null      // sector() 用：世界帧方位 → 磁北 偏移（弧度）
    var baguaJson: String = "{}"        // 缓存 assets/bagua_data.json

    val scanObjects = mutableListOf<ScanObject>()
    var analysisResult: AnalysisResult? = null

    /** 合并记录物体：同类型 0.8m 内并入均值，否则新增。跨线程安全。 */
    @Synchronized
    fun recordObject(type: String, x: Double, z: Double, dimX: Double? = null, dimZ: Double? = null) {
        val near = scanObjects.firstOrNull {
            it.type == type && Math.hypot(it.x - x, it.z - z) < 0.8
        }
        if (near != null) {
            val idx = scanObjects.indexOf(near)
            scanObjects[idx] = ScanObject(
                type,
                (near.x + x) / 2,
                (near.z + z) / 2,
                avg(near.dimX, dimX),
                avg(near.dimZ, dimZ)
            )
        } else {
            scanObjects.add(ScanObject(type, x, z, dimX, dimZ))
        }
    }

    private fun avg(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> (a + b) / 2
        a != null -> a
        b != null -> b
        else -> null
    }

    @Synchronized
    fun snapshotObjects(): List<ScanObject> = scanObjects.toList()

    fun refreshGua() {
        guaInfo = if (birthYear != null && gender != null && baguaJson.isNotEmpty() && baguaJson != "{}")
            MingGua.compute(birthYear!!, gender!!, baguaJson)
        else null
    }

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        birthYear = if (sp.contains("birthYear")) sp.getInt("birthYear", 0) else null
        gender = sp.getString("gender", null)
        northAngle = if (sp.contains("northAngle")) sp.getFloat("northAngle", 0f).toDouble() else null
        refreshGua()
    }

    fun save(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            if (birthYear != null) putInt("birthYear", birthYear!!) else remove("birthYear")
            if (gender != null) putString("gender", gender) else remove("gender")
            if (northAngle != null) putFloat("northAngle", northAngle!!.toFloat()) else remove("northAngle")
            apply()
        }
    }

    fun hasBirth(): Boolean = birthYear != null && gender != null && guaInfo != null
    fun hasNorth(): Boolean = northAngle != null

    private const val PREFS = "fengshui_prefs"
}
