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

    // ---- 五行（L2）：物体五行 vs 八卦方位五行 ----
    private val OBJECT_ELEMENT = mapOf(
        "stove" to "火", "toilet" to "水", "fridge" to "水", "plant" to "木",
        "sofa" to "土", "wardrobe" to "土", "bed" to "木", "dining" to "木",
        "desk" to "木", "study" to "木", "door" to "木", "window" to "木",
        "front_desk" to "金", "cashier" to "金", "finance_room" to "金", "pillar" to "金",
        "water" to "水", "shrine" to "火"
    )
    private val SECTOR_ELEMENT = mapOf(
        "北" to "水", "东北" to "土", "东" to "木", "东南" to "木",
        "南" to "火", "西南" to "土", "西" to "金", "西北" to "金"
    )
    private val ELEMENT_KE = mapOf("木" to "土", "土" to "水", "水" to "火", "火" to "金", "金" to "木") // A 克 B
    private val ELEMENT_SHENG = mapOf("木" to "火", "火" to "土", "土" to "金", "金" to "水", "水" to "木") // A 生 B

    /** 两行相克（双向任一）。 */
    private fun elementClash(e1: String, e2: String): Boolean =
        ELEMENT_KE[e1] == e2 || ELEMENT_KE[e2] == e1

    /** A 生 B（单向）。 */
    private fun elementSheng(e1: String, e2: String): Boolean = ELEMENT_SHENG[e1] == e2

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
            // 新增可近似距离事实（激活根因C规则：植物/神位/灶/门/主位等近距判定）
            "nearBedHead", "stoveInFront", "facingPillar", "facesPillar",
            "facesDoorPath", "visibleFromDoor", "besideDoor", "noDraftFromDoor" -> {
                val target = when (fact) {
                    "nearBedHead" -> "bed"
                    "stoveInFront" -> "stove"
                    "facingPillar", "facesPillar" -> "pillar"
                    else -> "door"
                }
                nearAny(o, facts, target)
            }
            // 厕忌吉方（作厕忌在吉方/来脉/将星等，近似吉方）
            "inBagua" -> goodSectors.contains(Geo.sector(o.pos, roomCenter(facts), facts.northAngle))
            // 主位背后空虚：靠墙方向正对门
            "backToDoor" -> backToDoor(o, facts)
            // 三门相对一线：≥3 扇门近似共线
            "threeDoorsInLine" -> threeDoorsInLine(facts)
            // 宅内门不宜多：检测门数 ≥4
            "tooManyDoors" -> facts.objects.count { it.type == "door" } >= 4
            // L2 五行相生（吉）：方位五行 生 物体五行（得生之方）
            "sectorGeneratesObject" -> {
                val objEl = OBJECT_ELEMENT[o.type] ?: return false
                val secEl = SECTOR_ELEMENT[Geo.sector(o.pos, roomCenter(facts), facts.northAngle)]
                secEl != null && elementSheng(secEl, objEl)
            }
            // L2 五行相生（吉）：邻近物体五行 生 本物五行
            "objectGeneratesObject" -> {
                val objEl = OBJECT_ELEMENT[o.type] ?: return false
                facts.objects.any {
                    it.type != o.type && Geo.dist(o.pos, it.pos) < NEAR_DIST &&
                        OBJECT_ELEMENT[it.type]?.let { el -> elementSheng(el, objEl) } == true
                }
            }
            // L2 五行相克（凶）：邻近物体五行 克 本物五行
            "objectClashesObject" -> {
                val objEl = OBJECT_ELEMENT[o.type] ?: return false
                facts.objects.any {
                    it.type != o.type && Geo.dist(o.pos, it.pos) < NEAR_DIST &&
                        OBJECT_ELEMENT[it.type]?.let { el -> elementClash(el, objEl) } == true
                }
            }
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
            // L2 五行：物体五行与所在卦位五行相克（水火/火金/金木/木土/土水）
            "elementClashWithSector" -> {
                val objEl = OBJECT_ELEMENT[o.type] ?: return false
                val sector = Geo.sector(o.pos, roomCenter(facts), facts.northAngle)
                val secEl = SECTOR_ELEMENT[sector]
                secEl != null && elementClash(objEl, secEl)
            }
            else -> false
        }
    }

    private fun roomCenter(f: RoomFacts): Pt =
        Pt(f.polygon.map { it.x }.average(), f.polygon.map { it.z }.average())

    /** 距某类型最近物体 < dist。 */
    private fun nearAny(o: Furniture, facts: RoomFacts, targetType: String, dist: Double = NEAR_DIST): Boolean =
        facts.objects.any { it.type == targetType && Geo.dist(o.pos, it.pos) < dist }

    /** 靠背方向：物体 → 最近墙垂足 的单位方向。 */
    private fun backingDir(o: Furniture, facts: RoomFacts): Pair<Double, Double>? {
        var bestD = Double.MAX_VALUE
        var foot: Pt? = null
        for (w in facts.walls) {
            val f = projectToSeg(o.pos, w)
            val d = Math.hypot(o.pos.x - f.x, o.pos.z - f.z)
            if (d < bestD) { bestD = d; foot = f }
        }
        val f = foot ?: return null
        val dx = f.x - o.pos.x; val dz = f.z - o.pos.z
        val len = Math.hypot(dx, dz)
        if (len < 1e-6) return null
        return (dx / len) to (dz / len)
    }

    private fun projectToSeg(p: Pt, s: Seg): Pt {
        val dx = s.b.x - s.a.x; val dz = s.b.z - s.a.z
        val len2 = dx * dx + dz * dz
        if (len2 < 1e-12) return s.a
        val t = ((p.x - s.a.x) * dx + (p.z - s.a.z) * dz) / len2
        val tt = t.coerceIn(0.0, 1.0)
        return Pt(s.a.x + tt * dx, s.a.z + tt * dz)
    }

    /** 背后空虚：本物背后（靠墙方向）有门正对。 */
    private fun backToDoor(o: Furniture, facts: RoomFacts): Boolean {
        val bd = backingDir(o, facts) ?: return false
        for (d in facts.objects.filter { it.type == "door" }) {
            val dx = d.pos.x - o.pos.x; val dz = d.pos.z - o.pos.z
            val dist = Math.hypot(dx, dz)
            if (dist < 1e-6 || dist > 3.0) continue
            if (dx * bd.first + dz * bd.second > 0.5 * dist) return true
        }
        return false
    }

    /** 三门一线：≥3 扇门近似共线（三角形面积 < 0.5㎡）。 */
    private fun threeDoorsInLine(facts: RoomFacts): Boolean {
        val doors = facts.objects.filter { it.type == "door" }.map { it.pos }
        if (doors.size < 3) return false
        for (i in doors.indices) for (j in i + 1 until doors.size) for (k in j + 1 until doors.size) {
            val a = doors[i]; val b = doors[j]; val c = doors[k]
            val area = Math.abs((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)) / 2.0
            if (area < 0.5) return true
        }
        return false
    }

    val defaultGood = setOf("北", "西北", "西", "西南", "东北")
    val defaultBad = setOf("南", "东南", "东")
}
