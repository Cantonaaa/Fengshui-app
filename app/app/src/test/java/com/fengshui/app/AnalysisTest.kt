package com.fengshui.app

import com.fengshui.solver.Pt
import org.junit.Test

class AnalysisTest {

    private val guaKan = MingGua.compute(1990, "男", TestData.BAGUA_JSON)   // 坎命

    private val rulesJson = """
    [
      {
        "ruleId": "t_bed_kill", "title": "床在凶方", "severity": "凶", "status": "active",
        "condition": { "require": { "bed": 1 }, "spatial": { "bed": ["inKillSector"] } },
        "finding": { "summary": "床应避开凶方", "remedy": ["移床"] },
        "evidence": [ { "book": "宅经", "chapter": "一", "quoteKey": "x", "original": "凶方勿居", "modern": "凶方不宜居", "reliability": "推演引申" } ]
      },
      {
        "ruleId": "t_uncond", "title": "乘生气通则", "severity": "吉", "status": "active",
        "condition": { "require": { "room": 1 } },
        "finding": { "summary": "通则", "remedy": [] },
        "evidence": []
      }
    ]
    """.trimIndent()

    private fun factsWith(bed: Pt): com.fengshui.solver.RoomFacts =
        FactsBuilder.build(
            listOf(ScanObject("bed", bed.x, bed.z)),
            listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0)),
            northAngle = 0.0
        )

    @Test
    fun parseRuleCards_readsFindingAndEvidence() {
        val cards = RuleEngine.parseRuleCards(rulesJson)
        assert(cards.size == 2)
        val bed = cards.first { it.id == "t_bed_kill" }
        assert(bed.severity == "凶")
        assert(bed.summary == "床应避开凶方")
        assert(bed.remedy == listOf("移床"))
        assert(bed.evidence.size == 1 && bed.evidence[0].original == "凶方勿居")
        assert(bed.evidence[0].reliability == "推演引申")
    }

    @Test
    fun isUnconditional() {
        val cards = RuleEngine.parseRuleCards(rulesJson)
        assert(RuleEngine.isUnconditional(cards.first { it.id == "t_uncond" }.condition))
        assert(!RuleEngine.isUnconditional(cards.first { it.id == "t_bed_kill" }.condition))
    }

    @Test
    fun analyze_firesKillRule_byGuaAndFiltersUnconditional() {
        val cards = RuleEngine.parseRuleCards(rulesJson)
        // 坎命凶方含西南；床放 (0.5,0.5)=西南 → 命中 inKillSector
        val facts = factsWith(Pt(0.5, 0.5))
        val hits = RuleEngine.analyze(facts, cards, guaKan)
        val ids = hits.map { it.id }
        assert(ids.contains("t_bed_kill")) { "凶方规则应命中: $ids" }
        assert(!ids.contains("t_uncond")) { "无条件通则应被过滤: $ids" }
    }

    @Test
    fun analyze_notFired_inGoodSector() {
        val cards = RuleEngine.parseRuleCards(rulesJson)
        // (2,2)=中心，sector=北? dx=0,dz=0 → atan2(0,0)=0 → 北（吉方）→ 不命中 inKillSector
        val facts = factsWith(Pt(2.0, 2.0))
        val hits = RuleEngine.analyze(facts, cards, guaKan)
        assert(hits.none { it.id == "t_bed_kill" })
    }

    @Test
    fun solveRemediation_movesBedToWallGoodSector() {
        val cards = RuleEngine.parseRuleCards(rulesJson)
        // 床在房间中央 → noBacking？规则是 inKillSector；床在 (0.5,0.5)=西南凶方 → 命中
        // 且床远离墙（noBacking 需额外规则，这里仅 inKillSector）
        val facts = factsWith(Pt(0.5, 0.5))
        val hits = RuleEngine.analyze(facts, cards, guaKan)
        val badHits = hits.filter { it.severity != "吉" }
        assert(badHits.isNotEmpty())

        val plan = solveRemediation(facts, cards, badHits, guaKan)
        assert(plan != null) { "应能生成整改方案" }
        assert(plan!!.actions.isNotEmpty()) { "应至少一个移动动作" }
    }

    @Test
    fun sectorInfo_idsAlignWithFactsBuilder() {
        val objects = listOf(
            ScanObject("bed", 0.5, 0.5),
            ScanObject("stove", 2.5, 2.5)
        )
        val poly = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(0.0, 4.0))
        val facts = FactsBuilder.build(objects, poly, 0.0)
        val infos = sectorInfo(objects, poly, 0.0)
        assert(infos.size == 2)
        for (i in infos.indices) {
            assert(infos[i].id == facts.objects[i].id) { "ObjInfo.id 应对齐 Furniture.id" }
        }
        assert(infos[0].sector == "西南")   // (0.5,0.5) 相对中心(2,2)
    }

    @Test
    fun sceneTypes_preferredPerScene() {
        assert(AppState.sceneTypes().isNotEmpty())
        assert(AppState.sceneName().isNotBlank())
    }
}
