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
}
