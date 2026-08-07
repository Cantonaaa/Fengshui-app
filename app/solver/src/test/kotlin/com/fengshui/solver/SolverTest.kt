package com.fengshui.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试用房间/规则夹具。 */
object Fixtures {
    fun rectRoom(w: Double = 4.0, d: Double = 3.0): RoomFacts {
        val poly = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, d), Pt(0.0, d))
        val walls = listOf(
            Seg(Pt(0.0, 0.0), Pt(w, 0.0)),
            Seg(Pt(w, 0.0), Pt(w, d)),
            Seg(Pt(w, d), Pt(0.0, d)),
            Seg(Pt(0.0, d), Pt(0.0, 0.0))
        )
        return RoomFacts(polygon = poly, walls = walls, floorHeight = 0.0, objects = mutableListOf())
    }

    fun rule(id: String, sev: String, require: String, spatial: String): Rule {
        val cond = org.json.JSONObject()
            .put("require", org.json.JSONObject().put(require, 1))
            .put("spatial", org.json.JSONObject().put(require, org.json.JSONArray().put(spatial)))
        return Rule(id, sev, RuleCondition.fromJson(cond))
    }
}

class GeometryTest {
    @Test fun pointInPolygon() {
        val room = Fixtures.rectRoom()
        assertTrue(Geo.pointInPolygon(Pt(2.0, 1.5), room.polygon))
        assertTrue(!Geo.pointInPolygon(Pt(5.0, 1.5), room.polygon))
        assertTrue(!Geo.pointInPolygon(Pt(-1.0, 1.5), room.polygon))
    }

    @Test fun distToWall() {
        val room = Fixtures.rectRoom()
        assertEquals(1.5, Geo.distToWall(Pt(2.0, 1.5), room.walls), 1e-6)
        assertEquals(0.3, Geo.distToWall(Pt(2.0, 0.3), room.walls), 1e-6)
    }

    @Test fun rectsOverlap() {
        val a = Pair(Pt(0.0, 0.0), Pt(2.0, 1.0))
        val b = Pair(Pt(1.5, 0.5), Pt(3.0, 2.0))
        assertTrue(Geo.rectsOverlap(a, b))
        assertTrue(!Geo.rectsOverlap(a, Pair(Pt(3.0, 0.0), Pt(4.0, 1.0))))
    }

    @Test fun sector() {
        val c = Pt(2.0, 1.5)
        assertEquals("北", Geo.sector(Pt(2.0, 3.0), c, 0.0))
        assertEquals("南", Geo.sector(Pt(2.0, 0.0), c, 0.0))
        assertEquals("东", Geo.sector(Pt(4.0, 1.5), c, 0.0))
        assertEquals("西", Geo.sector(Pt(0.0, 1.5), c, 0.0))
    }
}

class ConditionEvaluatorTest {
    private fun facts(bedAt: Pt): RoomFacts {
        val f = Fixtures.rectRoom()
        f.objects.add(Furniture("bed1", "bed", bedAt, 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        return f
    }

    @Test fun noBackingWhenInMiddle() {
        val f = facts(Pt(2.0, 1.5))  // 房间中央 → 无靠
        val r = Fixtures.rule("r1", "凶", "bed", "noBacking")
        assertTrue(ConditionEvaluator.violated(r.condition, f))
    }

    @Test fun hasBackingWhenAgainstWall() {
        val f = facts(Pt(2.0, 0.5))  // 贴墙 → 有靠
        val r = Fixtures.rule("r1", "凶", "bed", "noBacking")
        assertTrue(!ConditionEvaluator.violated(r.condition, f))
    }

    @Test fun nearFacts() {
        val f = Fixtures.rectRoom()
        f.objects.add(Furniture("bed1", "bed", Pt(2.0, 1.5), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        f.objects.add(Furniture("stove1", "stove", Pt(2.5, 1.2), 0.8, 0.6, Placement.FREESTANDING, Movability.MOVABLE))
        val r = Fixtures.rule("r2", "凶", "stove", "nearBedroom")
        assertTrue(ConditionEvaluator.violated(r.condition, f))  // 相距<2.5
    }
}

class RemediationSolverTest {

    @Test fun wallNeedingBedMovesToWall_noRegression() {
        // 床在房间中央（无靠），沙发靠墙。求解器应把床移到墙边且不与沙发冲突。
        val f = Fixtures.rectRoom()
        f.objects.add(Furniture("bed1", "bed", Pt(2.0, 1.5), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        f.objects.add(Furniture("sofa1", "sofa", Pt(3.5, 0.5), 1.8, 0.9, Placement.WALL_NEEDING, Movability.MOVABLE))

        val bedRule = Fixtures.rule("bed_backing", "凶", "bed", "noBacking")
        val sofaRule = Fixtures.rule("sofa_backing", "凶", "sofa", "noBacking")
        val solver = RemediationSolver(listOf(bedRule, sofaRule))

        val plan = solver.solve(f, listOf("bed_backing"))

        assertEquals(1, plan.actions.size)
        assertEquals("bed1", plan.actions[0].objectId)
        // 移后床应贴墙（有靠）
        assertTrue(Geo.distToWall(plan.actions[0].toPosition, f.walls) < 1.0)
        // 无回归：沙发规则未被破坏
        assertTrue(!ConditionEvaluator.violated(sofaRule.condition, f))
        // 完整
        assertTrue(plan.remainingViolations.isEmpty())
    }

    @Test fun blockedByOtherFurniture() {
        // 房间被家具占满，床无处可移 → 被占提示
        val f = Fixtures.rectRoom(3.0, 2.0)
        f.objects.add(Furniture("bed1", "bed", Pt(1.5, 1.0), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        // 占满两侧墙
        f.objects.add(Furniture("sofa1", "sofa", Pt(0.5, 0.4), 1.8, 0.8, Placement.WALL_NEEDING, Movability.MOVABLE))
        f.objects.add(Furniture("ward1", "wardrobe", Pt(2.5, 0.4), 0.6, 1.8, Placement.WALL_NEEDING, Movability.MOVABLE))
        f.objects.add(Furniture("cab1", "cabinet", Pt(0.5, 1.6), 1.2, 0.5, Placement.FREESTANDING, Movability.MOVABLE))

        val bedRule = Fixtures.rule("bed_backing", "凶", "bed", "noBacking")
        val solver = RemediationSolver(listOf(bedRule))
        val plan = solver.solve(f, listOf("bed_backing"))

        // 可能无解或提示被占
        assertTrue(plan.actions.isEmpty() || plan.blockedNotes.isNotEmpty() || plan.tradeoffNotes.isNotEmpty())
    }

    @Test fun jsonParseRealRule() {
        // 读取真实规则 JSON（solver 测试目录相对项目根）
        val paths = listOf("../rules/draft/yszs_batch1.json", "../../rules/draft/yszs_batch1.json", "rules/draft/yszs_batch1.json")
        val f = java.io.File(paths.firstOrNull { java.io.File(it).exists() } ?: paths[1])
        if (!f.exists()) return  // 路径不存在则跳过（本地开发环境应存在）
        val arr = org.json.JSONArray(f.readText())
        val r = Rule.fromJson(arr.getJSONObject(0))
        assertTrue(r.id.isNotBlank())
        assertTrue(r.condition.require.isNotEmpty() || r.condition.spatial.isNotEmpty())
    }

    // ===== 边缘情况（B5 优化） =====

    @Test fun jointSolve_multipleViolationsOneMove() {
        // 同一物体（床）多个违规：无靠 + 近灶 → 一次移动同时消除
        val f = Fixtures.rectRoom(6.0, 4.0)
        f.objects.add(Furniture("bed1", "bed", Pt(3.0, 2.0), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        f.objects.add(Furniture("stove1", "stove", Pt(5.5, 2.0), 0.8, 0.6, Placement.FREESTANDING, Movability.FIXED))

        val bedBacking = Fixtures.rule("bed_backing", "凶", "bed", "noBacking")
        val bedNearStove = Fixtures.rule("bed_nearstove", "凶", "bed", "nearStove")
        val solver = RemediationSolver(listOf(bedBacking, bedNearStove))

        val plan = solver.solve(f, listOf("bed_backing", "bed_nearstove"))

        // 一次移动解决两个违规
        assertEquals(1, plan.actions.size)
        assertEquals("bed1", plan.actions[0].objectId)
        assertTrue(plan.remainingViolations.isEmpty())
        assertTrue(!ConditionEvaluator.violated(bedBacking.condition, f))
        assertTrue(!ConditionEvaluator.violated(bedNearStove.condition, f))
    }

    @Test fun shortWall_noSolution() {
        // 所有墙段都太短（1.5m）放不下床长边（2m）→ 无解
        val f = Fixtures.rectRoom(1.5, 1.5)
        f.objects.add(Furniture("bed1", "bed", Pt(0.75, 0.75), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        val bedBacking = Fixtures.rule("bed_backing", "凶", "bed", "noBacking")
        val solver = RemediationSolver(listOf(bedBacking))
        val plan = solver.solve(f, listOf("bed_backing"))
        // 墙长 1.5 < 床长 2 → 无候选 → 无解/权衡
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.tradeoffNotes.isNotEmpty() || plan.blockedNotes.isNotEmpty())
    }

    @Test fun suboptimalWithTradeoff() {
        // 无完美解：唯一可移墙位（整面左墙）都在"西"（引入平级 inWest 违规）→ 次优+权衡
        val f = Fixtures.rectRoom(6.0, 4.0)
        f.objects.add(Furniture("bed1", "bed", Pt(3.0, 2.0), 2.0, 1.0, Placement.WALL_NEEDING, Movability.MOVABLE))
        // 右/底/顶墙堵住；底/顶柜不延伸到左墙（避免净空误伤）
        f.objects.add(Furniture("r1", "wardrobe", Pt(5.6, 2.0), 0.4, 3.0, Placement.WALL_NEEDING, Movability.FIXED))
        f.objects.add(Furniture("b1", "wardrobe", Pt(4.0, 0.4), 3.8, 0.4, Placement.WALL_NEEDING, Movability.FIXED))
        f.objects.add(Furniture("t1", "wardrobe", Pt(4.0, 3.6), 3.8, 0.4, Placement.WALL_NEEDING, Movability.FIXED))

        val bedBacking = Fixtures.rule("bed_backing", "凶", "bed", "noBacking")
        val bedInWest = Fixtures.rule("bed_inWest", "平", "bed", "inWest")
        val solver = RemediationSolver(listOf(bedBacking, bedInWest))

        val plan = solver.solve(f, listOf("bed_backing"))

        // 目标消除（床有靠了），唯一可移墙位在"西"→ 引入平级违规 → 次优+权衡
        assertTrue(plan.actions.isNotEmpty())
        assertTrue(!ConditionEvaluator.violated(bedBacking.condition, f))
        assertTrue(ConditionEvaluator.violated(bedInWest.condition, f))
        assertTrue(plan.tradeoffNotes.isNotEmpty())
    }
}

/** 命卦感知：吉凶方位由外部传入，而非硬编码。 */
class MingGuaAwareTest {
    @Test fun inGoodSector_usesCustomSectors() {
        val f = Fixtures.rectRoom(4.0, 4.0)   // 中心(2,2)，northAngle=0
        val sofa = Furniture("s1", "sofa", Pt(2.0, 0.5), 2.0, 0.9, Placement.WALL_NEEDING, Movability.MOVABLE)  // 南
        // 默认吉方不含"南"；自定义吉方含"南"
        assertTrue(!ConditionEvaluator.evalFact("inGoodSector", sofa, f))
        assertTrue(ConditionEvaluator.evalFact("inGoodSector", sofa, f, goodSectors = setOf("南")))
        // 自定义凶方含"南" → inKillSector 命中
        assertTrue(ConditionEvaluator.evalFact("inKillSector", sofa, f, badSectors = setOf("南")))
    }

    @Test fun violated_honorsCustomBadSectors() {
        val f = Fixtures.rectRoom(4.0, 4.0)
        val sofa = Furniture("s1", "sofa", Pt(2.0, 0.5), 2.0, 0.9, Placement.WALL_NEEDING, Movability.MOVABLE)  // 南
        f.objects.add(sofa)
        val r = Fixtures.rule("rS", "凶", "sofa", "inKillSector")
        // 默认凶方含"南" → 违规；自定义凶方不含"南" → 不违规（命卦感知）
        assertTrue(ConditionEvaluator.violated(r.condition, f))
        assertTrue(!ConditionEvaluator.violated(r.condition, f, goodSectors = setOf("南"), badSectors = setOf("西", "西北", "东北")))
    }
}
