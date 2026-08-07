package com.fengshui.solver

import org.json.JSONObject

/** 一条规则（ID + 严重度 + 中文标题 + 条件）。 */
data class Rule(
    val id: String,
    val severity: String,
    val title: String,
    val condition: RuleCondition
) {
    companion object {
        fun fromJson(o: JSONObject): Rule = Rule(
            id = o.getString("ruleId"),
            severity = o.optString("severity", "平"),
            title = o.optString("title", ""),
            condition = RuleCondition.fromJson(o.optJSONObject("condition") ?: JSONObject())
        )
    }
}

/**
 * 规则索引：物体类型 → 相关规则。
 * 支撑求解器的"局部重评估"——移动物体后只评估涉及该物体的规则。
 */
class RuleIndex(private val rules: List<Rule>) {
    private val byObject: Map<String, List<Rule>> = run {
        val m = HashMap<String, MutableList<Rule>>()
        for (r in rules) {
            val types = r.condition.require + r.condition.spatial.keys
            for (t in types) {
                if (t == "birth" || t == "direction" || t == "room") continue
                m.getOrPut(t) { mutableListOf() }.add(r)
            }
        }
        m
    }

    /** 与给定物体类型相关的规则。 */
    fun rulesFor(types: Collection<String>): List<Rule> =
        types.flatMap { byObject[it] ?: emptyList() }.distinctBy { it.id }

    /** 命中某条规则的物体类型集合（用于确定"subject"）。 */
    fun subjectCandidates(rule: Rule, facts: RoomFacts): List<Furniture> =
        rule.condition.require.mapNotNull { t ->
            facts.objects.firstOrNull { it.type == t && it.movability == Movability.MOVABLE }
        }
}
