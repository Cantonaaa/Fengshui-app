package com.fengshui.solver

import org.json.JSONObject
import java.lang.Math.abs

/**
 * 规则条件模型 + 求值器。
 * 规则条件：require(需存在的物体) + spatial(空间事实=违规点) + match(命卦/宅卦匹配)。
 * 违规判定：require 全部存在 且 (任一 spatial 事实成立 或 无 spatial) 且 match 匹配。
 */
data class RuleCondition(
    val require: List<String>,
    val spatial: Map<String, List<String>>,
    val match: Map<String, String>
) {
    companion object {
        fun fromJson(c: JSONObject): RuleCondition {
            val req = c.optJSONObject("require")?.keys()?.asSequence()?.toList() ?: emptyList()
            val sp = LinkedHashMap<String, List<String>>()
            c.optJSONObject("spatial")?.let { o ->
                o.keys().forEach { k ->
                    val v = o.opt(k)
                    when (v) {
                        is Boolean -> if (v) sp[k] = listOf("TRUE")
                        is org.json.JSONArray -> sp[k] = (0 until v.length()).map { v.getString(it) }
                    }
                }
            }
            val match = LinkedHashMap<String, String>()
            c.optJSONObject("match")?.let { o ->
                o.keys().forEach { k -> match[k] = o.getString(k) }
            }
            return RuleCondition(req, sp, match)
        }
    }
}

object ConditionEvaluator {

    private const val NEAR_DIST = 2.5
    private const val FACES_DIST = 2.5
    private const val WALL_BACKING_TOL = 0.25  // 有靠判定容差（相对物体进深）

    /** 评估一条规则条件是否违规。 */
    fun violated(
        cond: RuleCondition,
        facts: RoomFacts,
        context: Map<String, String> = emptyMap(),
        goodSectors: Set<String> = defaultGood,
        badSectors: Set<String> = defaultBad
    ): Boolean {
        // require：全部存在
        for (t in cond.require) {
            if (t == "birth" || t == "direction" || t == "room") continue // 用户/房间级，视为满足
            if (facts.objects.none { it.type == t }) return false
        }
        // match
        for ((k, v) in cond.match) {
            if (context[k] != null && context[k] != v) return false
        }
        // spatial：任一事实成立 → 违规
        if (cond.spatial.isEmpty()) return true
        for ((objType, facts_) in cond.spatial) {
            for (o in facts.objects.filter { it.type == objType }) {
                if (facts_.any { evalFact(it, o, facts, goodSectors, badSectors) }) return true
            }
        }
        return false
    }

    /** 计算单个空间事实（违规方向成立即返回 true）。 */
    fun evalFact(
        fact: String,
        o: Furniture,
        facts: RoomFacts,
        goodSectors: Set<String> = defaultGood,
        badSectors: Set<String> = defaultBad
    ): Boolean {
        return when (fact) {
            // 距离类：near / faces（MVP 位置近似，不依赖朝向）
            "nearStove", "nearToilet", "nearBedroom", "nearStudy",
            "facesStove", "facesDoor", "facesObjectDirectly", "facesAnotherDoor",
            "facesDesk", "facesMainOffice", "facesGoodSector" -> {
                val target = when (fact) {
                    "facesStove", "nearStove" -> "stove"
                    "facesDoor", "facesAnotherDoor" -> "door"
                    "nearToilet" -> "toilet"
                    "nearBedroom" -> "bed"
                    "nearStudy" -> "study"
                    "facesDesk" -> "desk"
                    "facesMainOffice" -> "office_area"
                    else -> null
                }
                if (target == null) false
                else facts.objects.filter { it.type == target }.any { Geo.dist(o.pos, it.pos) < (if (fact.startsWith("near")) NEAR_DIST else FACES_DIST) }
            }
            "oppositeBed" -> facts.objects.any { it.type == "bed" && Geo.dist(o.pos, it.pos) < NEAR_DIST }
            // 墙邻/位置类
            "noBacking" -> Geo.distToWall(o.pos, facts.walls) > o.dimZ / 2 + WALL_BACKING_TOL
            "backToWall" -> Geo.distToWall(o.pos, facts.walls) < o.dimZ / 2 + WALL_BACKING_TOL
            "frontOpen" -> Geo.distToWall(o.pos, facts.walls) > o.dimZ / 2 + WALL_BACKING_TOL
            "onRightSideOfRoom" -> o.pos.z > roomCenter(facts).z
            "onLeftSideOfRoom" -> o.pos.z < roomCenter(facts).z
            "underBeam", "stairAbove", "aboveDoorHeightHigh", "hasSkylight" -> false // 暂不可测
            "inWenChang" -> Geo.sector(o.pos, roomCenter(facts), facts.northAngle) == "东南"
            "inKillSector" -> badSectors.contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "inGoodSector" -> goodSectors.contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "notInKillSector" -> !badSectors.contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "inWoodSector" -> setOf("东", "东南").contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "notInEastSE" -> !setOf("东", "东南").contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "inEast" -> Geo.sector(o.pos, roomCenter(facts), facts.northAngle) == "东"
            "inWest" -> Geo.sector(o.pos, roomCenter(facts), facts.northAngle) == "西"
            "inWestNWNE" -> setOf("西", "西北", "东北").contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            "notInWest", "notInWoodSector" -> setOf("西", "西北", "西南").contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            else -> false
        }
    }

    private fun roomCenter(f: RoomFacts): Pt =
        Pt(f.polygon.map { it.x }.average(), f.polygon.map { it.z }.average())

    val defaultGood = setOf("北", "西北", "西", "西南", "东北")
    val defaultBad = setOf("南", "东南", "东")
}
