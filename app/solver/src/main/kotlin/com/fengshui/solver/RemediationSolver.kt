package com.fengshui.solver

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
 * 整改求解器：
 * 双层目标（硬约束优先）：
 *   硬：目标凶消除 + 受影响规则无新凶（局部重评估）
 *   软：满足更多吉规则（"消凶后构造吉"）
 * L1 只移动目标物体；其他家具=固定障碍。
 */
class RemediationSolver(
    private val rules: List<Rule>,
    private val context: Map<String, String> = emptyMap()
) {
    private val index = RuleIndex(rules)
    private val goodSeverity = setOf("大吉", "吉")

    fun solve(facts: RoomFacts, violatedIds: List<String>): RemediationPlan {
        val actions = mutableListOf<RemediationAction>()
        val remaining = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val tradeoffs = mutableListOf<String>()

        val violatedRules = rules.filter { it.id in violatedIds }
        for (v in violatedRules) {
            val subjects = index.subjectCandidates(v, facts)
            if (subjects.isEmpty()) {
                remaining.add(v.id)
                tradeoffs.add("规则 ${v.id}：无可移动目标物体（需结构改造 L2/L3）")
                continue
            }
            var fixed = false
            for (s in subjects) {
                val result = tryFix(facts, v, s)
                when (result) {
                    is FixResult.Fixed -> {
                        actions.add(RemediationAction(s.id, s.pos, result.description, result.satisfied))
                        fixed = true
                        break
                    }
                    is FixResult.Blocked -> blocked.add("物体 ${s.id}(${s.type}) 移动方案被其他家具占用，需人工移开")
                    is FixResult.NoSolution -> tradeoffs.add("物体 ${s.id}(${s.type}) 无合适空位，建议遮挡/改造")
                }
            }
            if (!fixed) remaining.add(v.id)
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
        data class Fixed(val description: String, val satisfied: String) : FixResult()
        object Blocked : FixResult()
        object NoSolution : FixResult()
    }

    private fun tryFix(facts: RoomFacts, target: Rule, s: Furniture): FixResult {
        // 移动前受影响规则的状态（用于回归判断）
        val affected = index.rulesFor(listOf(s.type))
        val beforeViolated = affected.filter { ConditionEvaluator.violated(it.condition, facts, context) }.map { it.id }.toSet()

        val candidates = candidatePositions(facts, s)
        var best: Pt? = null
        var bestScore = Int.MIN_VALUE
        var bestDesc = ""
        var sawBlocked = false

        for (p in candidates) {
            if (!isValidPosition(facts, s, p)) {
                sawBlocked = true
                continue
            }
            val oldPos = s.pos
            s.pos = p
            val afterViolated = affected.filter { ConditionEvaluator.violated(it.condition, facts, context) }.map { it.id }.toSet()
            val targetFixed = target.id !in afterViolated
            val noNew = (afterViolated - beforeViolated).none { it != target.id } && afterViolated.all { it == target.id || it in beforeViolated }
            val soft = if (targetFixed && noNew) {
                // 软目标：满足更多吉规则（少违规）
                affected.filter { it.severity in goodSeverity }.count { ConditionEvaluator.violated(it.condition, facts, context) }.let { -it }
            } else Int.MIN_VALUE
            s.pos = oldPos

            if (soft > bestScore) {
                bestScore = soft
                best = p
                bestDesc = describe(s, p, facts)
            }
        }

        if (best == null) {
            return if (sawBlocked) FixResult.Blocked else FixResult.NoSolution
        }
        s.pos = best
        return FixResult.Fixed(bestDesc, "满足：${target.id} 已消除，无新凶")
    }

    /** 候选位生成：贴墙类=沿墙条带；自由类=网格采样。 */
    private fun candidatePositions(facts: RoomFacts, s: Furniture): List<Pt> {
        val pts = mutableListOf<Pt>()
        if (s.placement == Placement.WALL_NEEDING) {
            for (wall in facts.walls) {
                val dx = wall.b.x - wall.a.x; val dz = wall.b.z - wall.a.z
                val len = Math.hypot(dx, dz)
                if (len < 1e-6) continue
                val ux = dx / len; val uz = dz / len
                // 法向（指向房内：取法向使偏移点在多边形内）
                val nx = -uz; val nz = ux
                val offset = s.dimZ / 2 + 0.1
                for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
                    val wx = wall.a.x + ux * len * t
                    val wz = wall.a.z + uz * len * t
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
            val step = 1.0
            var x = xmin + 0.5
            while (x < xmax) {
                var z = zmin + 0.5
                while (z < zmax) {
                    val p = Pt(x, z)
                    if (Geo.pointInPolygon(p, facts.polygon)) pts.add(p)
                    z += step
                }
                x += step
            }
        }
        return pts
    }

    /** 位置约束：在多边形内 + 不与其他物体重叠(含净空) + 贴墙类需贴墙。 */
    private fun isValidPosition(facts: RoomFacts, s: Furniture, p: Pt): Boolean {
        if (!Geo.pointInPolygon(p, facts.polygon)) return false
        // 与其他物体不重叠（净空 = clearance）
        val sRect = Pair(
            Pt(p.x - s.dimX / 2 - facts.clearance, p.z - s.dimZ / 2 - facts.clearance),
            Pt(p.x + s.dimX / 2 + facts.clearance, p.z + s.dimZ / 2 + facts.clearance)
        )
        for (other in facts.objects) {
            if (other.id == s.id) continue
            val (a, b) = other.rect()
            if (Geo.rectsOverlap(sRect, Pair(a, b))) return false
        }
        // 贴墙类：中心离墙 ≈ dimZ/2（靠背贴墙）
        if (s.placement == Placement.WALL_NEEDING) {
            val d = Geo.distToWall(p, facts.walls)
            if (d > s.dimZ / 2 + 0.35 || d < s.dimZ / 2 - 0.35) return false
        }
        return true
    }

    private fun describe(s: Furniture, p: Pt, facts: RoomFacts): String {
        return if (s.placement == Placement.WALL_NEEDING)
            "${s.type}移到（${"%.2f".format(p.x)}, ${"%.2f".format(p.z)}），靠背/床头贴墙"
        else
            "${s.type}移到（${"%.2f".format(p.x)}, ${"%.2f".format(p.z)}）空位"
    }
}
