package com.fengshui.app

import android.content.Context
import com.fengshui.solver.ConditionEvaluator
import com.fengshui.solver.Furniture
import com.fengshui.solver.Geo
import com.fengshui.solver.Movability
import com.fengshui.solver.Placement
import com.fengshui.solver.Pt
import com.fengshui.solver.RemediationPlan
import com.fengshui.solver.RemediationSolver
import com.fengshui.solver.RoomFacts
import com.fengshui.solver.Rule
import com.fengshui.solver.RuleCondition
import com.fengshui.solver.Seg
import org.json.JSONArray
import org.json.JSONObject

/** 原文依据。 */
data class Evidence(val book: String, val chapter: String, val original: String, val modern: String)

/** 一条规则卡（含原文与整改建议）。 */
data class RuleCard(
    val id: String,
    val title: String,
    val severity: String,
    val condition: RuleCondition,
    val summary: String,
    val remedy: List<String>,
    val evidence: List<Evidence>
)

/** 检测物体及所在卦位。 */
data class ObjInfo(val type: String, val x: Double, val z: Double, val sector: String)

/** 分析结果。 */
data class AnalysisResult(
    val gua: MingGua.GuaInfo?,
    val northSet: Boolean,
    val objectCount: Int,
    val hits: List<RuleCard>,
    val objects: List<ObjInfo>,
    val plan: RemediationPlan? = null
) {
    val goodHits: List<RuleCard> get() = hits.filter { it.severity == "吉" }
    val badHits: List<RuleCard> get() = hits.filter { it.severity != "吉" }
}

/** 物体 → 卦位（配合扇形显示用）。 */
fun sectorInfo(objects: List<ScanObject>, polygon: List<Pt>, northAngle: Double): List<ObjInfo> {
    if (polygon.isEmpty()) return objects.map { ObjInfo(TYPE_MAP[it.type] ?: it.type, it.x, it.z, "?") }
    val center = Pt(polygon.map { it.x }.average(), polygon.map { it.z }.average())
    return objects.map {
        ObjInfo(TYPE_MAP[it.type] ?: it.type, it.x, it.z, Geo.sector(Pt(it.x, it.z), center, northAngle))
    }
}

/** 检测类型 → 规则卡物体类型。 */
private val TYPE_MAP = mapOf(
    "dining table" to "dining",
    "refrigerator" to "fridge",
    "potted plant" to "plant",
    "front desk" to "front_desk"
)

/** 物体类型 → 默认占地尺寸 [dimX, dimZ]（米）。 */
private val DIMS = mapOf(
    "bed" to (1.8 to 2.0), "sofa" to (2.0 to 0.9), "dining" to (1.5 to 0.8),
    "fridge" to (0.7 to 0.7), "plant" to (0.4 to 0.4), "toilet" to (0.4 to 0.6),
    "wardrobe" to (1.6 to 0.6), "door" to (0.9 to 0.06), "window" to (1.2 to 0.06),
    "stove" to (0.7 to 0.6), "pillar" to (0.3 to 0.3), "desk" to (1.2 to 0.6),
    "front_desk" to (1.5 to 0.6)
)

/** 固定/贴墙属性（灶固定，结构件固定；门窗贴墙）。 */
private fun isFixed(t: String) = t in setOf("stove", "toilet", "fridge", "door", "window", "pillar", "wardrobe")
private fun isWallNeeding(t: String) = t !in setOf("plant", "dining", "pillar")

object FactsBuilder {

    /** 由扫描物体 + 户型多边形（或包围盒回退）+ 北向 构建 RoomFacts。 */
    fun build(objects: List<ScanObject>, polygon: List<Pt>, northAngle: Double): RoomFacts {
        val furn = objects.mapIndexed { i, o ->
            val type = TYPE_MAP[o.type] ?: o.type
            val (ddx, ddz) = DIMS[type] ?: (0.6 to 0.6)
            val dx = o.dimX ?: ddx
            val dz = o.dimZ ?: ddz
            Furniture(
                id = "obj$i",
                type = type,
                pos = Pt(o.x, o.z),
                dimX = dx, dimZ = dz,
                placement = if (isWallNeeding(type)) Placement.WALL_NEEDING else Placement.FREESTANDING,
                movability = if (isFixed(type)) Movability.FIXED else Movability.MOVABLE
            )
        }.toMutableList()

        val walls: List<Seg> = if (polygon.size >= 3) {
            (0 until polygon.size).map { i -> Seg(polygon[i], polygon[(i + 1) % polygon.size]) }
        } else emptyList()

        return RoomFacts(
            polygon = polygon,
            walls = walls,
            floorHeight = 0.0,
            objects = furn,
            clearance = 0.4,
            northAngle = northAngle
        )
    }

    /** 户型缺失时：由物体包围盒生成近似矩形。 */
    fun boundingPolygon(objects: List<ScanObject>): List<Pt> {
        if (objects.isEmpty()) return emptyList()
        val xs = objects.map { it.x }; val zs = objects.map { it.z }
        val minX = xs.min() - 1.0; val maxX = xs.max() + 1.0
        val minZ = zs.min() - 1.0; val maxZ = zs.max() + 1.0
        return listOf(Pt(minX, minZ), Pt(maxX, minZ), Pt(maxX, maxZ), Pt(minX, maxZ))
    }

    /** 物体类型（检测名或规则类型）默认占地尺寸 [dimX, dimZ]。 */
    fun defaultDims(detectionType: String): Pair<Double, Double> =
        DIMS[TYPE_MAP[detectionType] ?: detectionType] ?: (0.6 to 0.6)
}

/** RuleCard → solver.Rule。 */
fun toSolverRule(c: RuleCard): Rule = Rule(c.id, c.severity, c.condition)

/**
 * 整改求解：对命中的凶/平规则生成整改方案（L1 移动）。
 * 需房间多边形 ≥3 顶点；返回 null 表示无可整改（无多边形或无凶命中）。
 */
fun solveRemediation(
    facts: RoomFacts,
    rules: List<RuleCard>,
    badHits: List<RuleCard>,
    gua: MingGua.GuaInfo
): RemediationPlan? {
    if (badHits.isEmpty() || facts.polygon.size < 3) return null
    val solverRules = rules.map { toSolverRule(it) }
    val ctx = mapOf("mingua" to gua.group, "houseTrigram" to gua.trigram)
    val solver = RemediationSolver(solverRules, ctx, gua.goodSectors.toSet(), gua.badSectors.toSet())
    return solver.solve(facts, badHits.map { it.id })
}

object RuleEngine {

    private val RULE_FILES = listOf(
        "yszs_batch1.json", "zfj_batch2.json", "zj_batch2b.json",
        "zss_fwl_batch3.json", "furniture_batch4.json", "office_batch5.json"
    )

    /** 纯函数：解析一份规则卡 JSON 文本 → 规则卡列表（含原文/整改）。 */
    fun parseRuleCards(json: String): List<RuleCard> {
        val cards = mutableListOf<RuleCard>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("status", "active") != "active") continue
            val cond = RuleCondition.fromJson(o.optJSONObject("condition") ?: JSONObject())
            val finding = o.optJSONObject("finding") ?: JSONObject()
            val remedy = mutableListOf<String>()
            finding.optJSONArray("remedy")?.let { ra ->
                for (j in 0 until ra.length()) remedy.add(ra.getString(j))
            }
            val ev = mutableListOf<Evidence>()
            o.optJSONArray("evidence")?.let { ea ->
                for (j in 0 until ea.length()) {
                    val e = ea.getJSONObject(j)
                    ev.add(Evidence(
                        e.optString("book"), e.optString("chapter"),
                        e.optString("original"), e.optString("modern")
                    ))
                }
            }
            cards.add(RuleCard(
                id = o.optString("ruleId"),
                title = o.optString("title"),
                severity = o.optString("severity", "平"),
                condition = cond,
                summary = finding.optString("summary"),
                remedy = remedy,
                evidence = ev
            ))
        }
        return cards
    }

    fun loadRules(context: Context): List<RuleCard> {
        val cards = mutableListOf<RuleCard>()
        for (file in RULE_FILES) {
            val json = context.assets.open(file).bufferedReader().use { it.readText() }
            cards.addAll(parseRuleCards(json))
        }
        return cards
    }

    /** 无条件规则：无 spatial 且 require 仅含房间/生辰/朝向级 → 每次必然触发，不进命中。 */
    fun isUnconditional(c: RuleCondition): Boolean {
        if (c.spatial.isNotEmpty()) return false
        return c.require.all { it in setOf("room", "birth", "direction") }
    }

    /** 求值：返回命中的规则卡（过滤无条件通则）。 */
    fun analyze(facts: RoomFacts, rules: List<RuleCard>, gua: MingGua.GuaInfo?): List<RuleCard> {
        if (gua == null) return emptyList()
        val context = mapOf(
            "mingua" to gua.group,
            "houseTrigram" to gua.trigram
        )
        val good = gua.goodSectors.toSet()
        val bad = gua.badSectors.toSet()
        return rules.filter { !isUnconditional(it.condition) && ConditionEvaluator.violated(it.condition, facts, context, good, bad) }
    }
}
