package com.fengshui.solver

import kotlin.math.max

/** 单条整改动作。 */
data class RemediationAction(
    val objectId: String,
    val toPosition: Pt,
    val description: String,
    val satisfied: String
)

/** 整改方案输出。 */
data class RemediationPlan(
    val actions: List<RemediationAction>,
    val beforeViolations: List<String>,
    val remainingViolations: List<String>,
    val blockedNotes: List<String>,
    val tradeoffNotes: List<String>
) {
    val isComplete: Boolean get() = remainingViolations.isEmpty()
}

/**
 * 整改求解器（双层目标：硬=消凶+无回归，软=构造吉）。
 *
 * 优化（边缘情况处理）：
 *  1. 联合求解：同一物体的多个违规一次移动同时消除
 *  2. 墙段长度校验：候选位须在墙段内放下物体长边
 *  3. 次优权衡：无完美解时接受"只引入低severity凶"候选并标注
 *  4. 吉方墙偏好：贴墙类靠墙侧落吉方位时软目标加分
 */
class RemediationSolver(
    private val rules: List<Rule>,
    private val context: Map<String, String> = emptyMap()
) {
    private val index = RuleIndex(rules)
    private val goodSeverity = setOf("大吉", "吉")
    private val sevWeight = mapOf("大凶" to 5, "凶" to 3, "平" to 1, "吉" to -1, "大吉" to -2)
    private val goodBackingSectors = setOf("北", "西北", "西", "西南", "东北")

    fun solve(facts: RoomFacts, violatedIds: List<String>): RemediationPlan {
        val actions = mutableListOf<RemediationAction>()
        val remaining = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val tradeoffs = mutableListOf<String>()

        val violatedRules = rules.filter { it.id in violatedIds }

        // 优化1：按 subject 物体分组（联合求解）
        val bySubject = LinkedHashMap<String, MutableList<Rule>>()
        for (v in violatedRules) {
            val subjects = index.subjectCandidates(v, facts)
            if (subjects.isEmpty()) {
                remaining.add(v.id)
                tradeoffs.add("规则 ${v.id}：无移动目标（涉及固定结构，需遮挡/改造 L2/L3）")
                continue
            }
            val sid = subjects[0].id
            bySubject.getOrPut(sid) { mutableListOf() }.add(v)
        }

        for ((sid, group) in bySubject) {
            val s = facts.objectOf(sid) ?: continue
            val result = tryFixGroup(facts, group, s)
            when (result) {
                is FixResult.Fixed -> {
                    actions.add(RemediationAction(s.id, s.pos, result.description, result.satisfied))
                    if (result.tradeoff != null) tradeoffs.add(result.tradeoff)
                }
                is FixResult.Blocked -> {
                    blocked.add("物体 ${s.id}(${s.type}) 移动方案被其他家具占用，需人工移开")
                    remaining.addAll(group.map { it.id })
                }
                is FixResult.NoSolution -> {
                    tradeoffs.add("物体 ${s.id}(${s.type}) 无合适空位，建议遮挡/改造")
                    remaining.addAll(group.map { it.id })
                }
            }
        }

        return RemediationPlan(
            actions = actions,
            beforeViolations = violatedIds,
            remainingViolations = remaining,
            blockedNotes = blocked,
            tradeoffNotes = tradeoffs
        )
    }

    private sealed class FixResult {
        data class Fixed(val description: String, val satisfied: String, val tradeoff: String? = null) : FixResult()
        object Blocked : FixResult()
        object NoSolution : FixResult()
    }

    /** 对同一 subject 的规则组联合求解。 */
    private fun tryFixGroup(facts: RoomFacts, targets: List<Rule>, s: Furniture): FixResult {
        val affected = index.rulesFor(listOf(s.type))
        val beforeViolated = affected.filter { ConditionEvaluator.violated(it.condition, facts, context) }.map { it.id }.toSet()

        // 候选位（含墙段长度校验）
        val candidates = candidatePositions(facts, s)
        var best: Pt? = null
        var bestScore = Int.MIN_VALUE
        var bestDesc = ""
        var bestTradeoff: String? = null
        var sawBlocked = false

        for (p in candidates) {
            if (!isValidPosition(facts, s, p)) { sawBlocked = true; continue }
            val old = s.pos
            s.pos = p
            val after = affected.filter { ConditionEvaluator.violated(it.condition, facts, context) }.map { it.id }.toSet()
            s.pos = old

            val targetsFixed = targets.all { it.id !in after }
            if (!targetsFixed) continue

            // 回归：新增的凶（非目标）
            val newViolations = after - beforeViolated - targets.map { it.id }.toSet()
            val newWeight = newViolations.sumOf { sevWeight[affected.firstOrNull { r -> r.id == it }?.severity] ?: 3 }

            // 优化3：两档接受
            val perfect = newViolations.isEmpty()
            // 软目标（构造吉 + 吉方墙偏好）
            val soft = if (perfect || newWeight <= 1) {
                val fewerGoodViolated = -affected.filter { it.severity in goodSeverity }
                    .count { ConditionEvaluator.violated(it.condition, facts, context) }
                val backingBonus = if (s.placement == Placement.WALL_NEEDING && backingSectorGood(facts, p, s)) 1 else 0
                val noNewPenalty = if (perfect) 0 else -newWeight * 2
                fewerGoodViolated + backingBonus + noNewPenalty
            } else Int.MIN_VALUE

            if (soft > bestScore) {
                bestScore = soft
                best = p
                bestDesc = describe(s, p, facts)
                bestTradeoff = if (perfect) null else "次优方案：消除目标但引入 ${newViolations.joinToString(",")}（severity 较低），可接受或进一步处理"
            }
        }

        if (best == null) return if (sawBlocked) FixResult.Blocked else FixResult.NoSolution
        s.pos = best
        return FixResult.Fixed(bestDesc, "满足：${targets.map { it.id }.joinToString(",")} 已消除，无新凶", bestTradeoff)
    }

    /** 优化4：贴墙类的靠墙侧是否落吉方位。 */
    private fun backingSectorGood(facts: RoomFacts, p: Pt, s: Furniture): Boolean {
        // 靠墙方向 ≈ 从物体中心指向最近墙的法向（反方向为靠背朝向）
        var nearest: Seg? = null
        var bestD = Double.MAX_VALUE
        for (w in facts.walls) {
            val d = Geo.distPointSegment(p, w)
            if (d < bestD) { bestD = d; nearest = w }
        }
        val w = nearest ?: return false
        // 靠背侧方向 = 物体指向墙的方向（单位法向）
        val dx = p.x - w.a.x; val dz = p.z - w.a.z
        val len = Math.hypot(dx, dz)
        if (len < 1e-6) return false
        val backPt = Pt(p.x - dx / len * 0.01, p.z - dz / len * 0.01)
        val center = Pt(facts.polygon.map { it.x }.average(), facts.polygon.map { it.z }.average())
        return Geo.sector(backPt, center, facts.northAngle) in goodBackingSectors
    }

    /** 候选位生成（优化2：墙段长度校验）。 */
    private fun candidatePositions(facts: RoomFacts, s: Furniture): List<Pt> {
        val pts = mutableListOf<Pt>()
        if (s.placement == Placement.WALL_NEEDING) {
            for (wall in facts.walls) {
                val dx = wall.b.x - wall.a.x; val dz = wall.b.z - wall.a.z
                val len = Math.hypot(dx, dz)
                if (len < 1e-6) continue
                if (len < s.dimX) continue   // 优化2：墙段太短放不下物体长边
                val ux = dx / len; val uz = dz / len
                val nx = -uz; val nz = ux
                val offset = s.dimZ / 2 + 0.1
                // 沿墙按物体长边步进采样，确保投影在墙段内
                val steps = maxOf(3, (len / s.dimX).toInt() + 1)
                for (i in 1 until steps) {
                    val t = i.toDouble() / steps
                    val along = t * len
                    if (along < s.dimX / 2 || along > len - s.dimX / 2) continue // 长边不出墙段
                    val wx = wall.a.x + ux * along
                    val wz = wall.a.z + uz * along
                    for (sgn in doubleArrayOf(1.0, -1.0)) {
                        val px = wx + nx * offset * sgn
                        val pz = wz + nz * offset * sgn
                        if (Geo.pointInPolygon(Pt(px, pz), facts.polygon)) pts.add(Pt(px, pz))
                    }
                }
            }
        } else {
            val xmin = facts.polygon.map { it.x }.min(); val xmax = facts.polygon.map { it.x }.max()
            val zmin = facts.polygon.map { it.z }.min(); val zmax = facts.polygon.map { it.z }.max()
            val step = max(s.dimX, 1.0)
            var x = xmin + s.dimX / 2
            while (x < xmax - s.dimX / 2) {
                var z = zmin + s.dimZ / 2
                while (z < zmax - s.dimZ / 2) {
                    val p = Pt(x, z)
                    if (Geo.pointInPolygon(p, facts.polygon)) pts.add(p)
                    z += step
                }
                x += step
            }
        }
        return pts
    }

    private fun isValidPosition(facts: RoomFacts, s: Furniture, p: Pt): Boolean {
        if (!Geo.pointInPolygon(p, facts.polygon)) return false
        val sRect = Pair(
            Pt(p.x - s.dimX / 2 - facts.clearance, p.z - s.dimZ / 2 - facts.clearance),
            Pt(p.x + s.dimX / 2 + facts.clearance, p.z + s.dimZ / 2 + facts.clearance)
        )
        for (other in facts.objects) {
            if (other.id == s.id) continue
            val (a, b) = other.rect()
            if (Geo.rectsOverlap(sRect, Pair(a, b))) return false
        }
        if (s.placement == Placement.WALL_NEEDING) {
            val d = Geo.distToWall(p, facts.walls)
            if (d > s.dimZ / 2 + 0.35 || d < s.dimZ / 2 - 0.35) return false
        }
        return true
    }

    private fun describe(s: Furniture, p: Pt, facts: RoomFacts): String =
        if (s.placement == Placement.WALL_NEEDING)
            "${s.type}移到（${"%.2f".format(p.x)}, ${"%.2f".format(p.z)}），靠背/床头贴墙"
        else
            "${s.type}移到（${"%.2f".format(p.x)}, ${"%.2f".format(p.z)}）空位"
}
