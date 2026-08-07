package com.fengshui.app

import com.fengshui.solver.Pt
import com.fengshui.solver.RemediationAction
import com.fengshui.solver.RemediationPlan
import com.fengshui.solver.RuleCondition
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test

class HistoryStoreTest {

    private lateinit var dir: File

    @Before fun setup() {
        dir = File(System.getProperty("java.io.tmpdir"), "history_test_${System.nanoTime()}")
        dir.mkdirs()
    }

    @After fun teardown() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, name: String, createdAt: Long): HistoryEntry {
        val cond = RuleCondition(
            require = listOf("bed", "wardrobe"),
            spatial = mapOf("bed" to listOf("NEAR_ORIENTATION")),
            match = mapOf("gua" to "坎")
        )
        val card = RuleCard(
            id = "zfj_bed_01",
            title = "床宜吉方",
            severity = "平",
            condition = cond,
            summary = "安床宜择宅之吉方吉间。",
            remedy = listOf("床移至吉方"),
            evidence = listOf(
                Evidence("《宅法举隅》", "下册·卧房", "安床，宜擇宅之吉方吉間", "安床宜择宅之吉方吉间", "直接明确")
            )
        )
        val plan = RemediationPlan(
            actions = listOf(RemediationAction("obj0", Pt(1.5, 1.0), "床移至吉方", "满足：床宜吉方 已消除，无新凶")),
            beforeViolations = listOf("zfj_bed_01"),
            remainingViolations = emptyList(),
            blockedNotes = emptyList(),
            tradeoffNotes = emptyList()
        )
        val result = AnalysisResult(
            gua = MingGua.GuaInfo("坎", "东四命", listOf("北", "东", "东南", "南"), listOf("西南", "西", "西北", "东北")),
            northSet = true,
            objectCount = 1,
            hits = listOf(card),
            objects = listOf(ObjInfo("obj0", "bed", 1.0, 2.0, "北")),
            plan = plan,
            unknownCount = 0,
            polygon = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 3.0), Pt(0.0, 3.0))
        )
        return HistoryEntry(id, name, createdAt, "living", 0.7, result)
    }

    @Test fun save_list_sortedNewToOld_rename_delete() {
        val old = entry("e_old", "旧记录", 1_000L)
        val new = entry("e_new", "新记录", 3_000L)
        HistoryStore.saveTo(dir, old)
        HistoryStore.saveTo(dir, new)

        val list = HistoryStore.listFrom(dir)
        assert(list.size == 2) { "应存两条" }
        assert(list[0].id == "e_new") { "应从新到旧排序，首条=${list[0].id}" }
        assert(list[1].id == "e_old") { "次条=${list[1].id}" }

        // 重命名
        assert(HistoryStore.renameIn(dir, "e_new", "改名")) { "重命名应成功" }
        assert(HistoryStore.loadFrom(dir, "e_new")?.name == "改名")

        // 删除
        assert(HistoryStore.deleteIn(dir, "e_old")) { "删除应成功" }
        assert(HistoryStore.listFrom(dir).size == 1)
        assert(HistoryStore.loadFrom(dir, "e_old") == null)
    }

    @Test fun roundTrip_preservesFullResult() {
        HistoryStore.saveTo(dir, entry("e1", "客厅", 5_000L))
        val loaded = HistoryStore.loadFrom(dir, "e1")!!
        assert(loaded.name == "客厅")
        assert(loaded.createdAt == 5_000L)
        assert(loaded.scene == "living")
        assert(Math.abs(loaded.northAngle - 0.7) < 1e-9)
        val r = loaded.result
        assert(r.gua?.trigram == "坎")
        assert(r.gua?.goodSectors == listOf("北", "东", "东南", "南"))
        assert(r.objectCount == 1)
        assert(r.hits.size == 1)
        assert(r.hits[0].title == "床宜吉方")
        assert(r.hits[0].condition.require == listOf("bed", "wardrobe"))
        assert(r.hits[0].condition.spatial["bed"] == listOf("NEAR_ORIENTATION"))
        assert(r.hits[0].condition.match["gua"] == "坎")
        assert(r.hits[0].evidence.size == 1)
        assert(r.hits[0].evidence[0].original.contains("吉方"))
        assert(r.objects[0].type == "bed")
        assert(r.objects[0].sector == "北")
        assert(r.plan?.actions?.size == 1)
        assert(r.plan?.actions?.get(0)?.objectId == "obj0")
        assert(Math.abs(r.plan!!.actions[0].toPosition.x - 1.5) < 1e-9)
        assert(r.polygon.size == 4)
    }

    @Test fun corruptFile_skipped() {
        HistoryStore.saveTo(dir, entry("ok", "好记录", 1L))
        File(dir, "bad.json").writeText("{ 这不是合法 JSON")
        val list = HistoryStore.listFrom(dir)
        assert(list.size == 1) { "坏文件应跳过，实际 ${list.size}" }
        assert(list[0].id == "ok")
    }
}
