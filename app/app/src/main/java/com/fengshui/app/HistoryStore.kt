package com.fengshui.app

import android.content.Context
import com.fengshui.solver.Pt
import com.fengshui.solver.RemediationAction
import com.fengshui.solver.RemediationPlan
import com.fengshui.solver.RuleCondition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条历史堪舆记录（完整结果快照）。
 * 北向为入宅会话内数据，须随记录存档，才能忠实重绘宫位图。
 */
data class HistoryEntry(
    val id: String,
    val name: String,
    val createdAt: Long,
    val scene: String,
    val northAngle: Double,
    val result: AnalysisResult
)

/**
 * 历史堪舆记录本地存储：`filesDir/history/{id}.json`，一结果一文件。
 * 内部私有存储，无需权限；list 按测试时间从新到旧排序；坏文件跳过。
 */
object HistoryStore {

    private const val DIR = "history"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 保存一条记录（成功返回 true）。 */
    fun save(context: Context, entry: HistoryEntry): Boolean = saveTo(dir(context), entry)

    /** 全部记录，按测试时间从新到旧。坏文件跳过。 */
    fun list(context: Context): List<HistoryEntry> = listFrom(dir(context))

    fun load(context: Context, id: String): HistoryEntry? = loadFrom(dir(context), id)

    /** 重命名（成功返回 true）。 */
    fun rename(context: Context, id: String, name: String): Boolean =
        renameIn(dir(context), id, name)

    fun delete(context: Context, id: String): Boolean = deleteIn(dir(context), id)

    // ---- 核心实现（以目录为界，便于纯 JVM 测试）----

    internal fun saveTo(d: File, entry: HistoryEntry): Boolean {
        return try {
            d.mkdirs()
            val f = File(d, "${entry.id}.json")
            f.writeText(toJson(entry).toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun listFrom(d: File): List<HistoryEntry> {
        if (!d.exists()) return emptyList()
        val out = mutableListOf<HistoryEntry>()
        for (f in d.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()) {
            try {
                val entry = fromJson(f.readText()) ?: continue
                out.add(entry)
            } catch (e: Exception) {
                // 跳过损坏文件
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    internal fun loadFrom(d: File, id: String): HistoryEntry? {
        val f = File(d, "$id.json")
        return try { fromJson(f.readText()) } catch (e: Exception) { null }
    }

    internal fun renameIn(d: File, id: String, name: String): Boolean {
        val cur = loadFrom(d, id) ?: return false
        return saveTo(d, cur.copy(name = name))
    }

    internal fun deleteIn(d: File, id: String): Boolean {
        return try {
            File(d, "$id.json").delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 测试时间展示：yyyy-MM-dd HH:mm。 */
    fun formatTime(createdAt: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(createdAt))

    // ---- 序列化 ----

    fun toJson(e: HistoryEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("createdAt", e.createdAt)
        put("scene", e.scene)
        put("northAngle", e.northAngle)
        put("result", resultToJson(e.result))
    }

    fun fromJson(s: String): HistoryEntry? {
        val o = JSONObject(s)
        val id = o.optString("id").ifEmpty { return null }
        return HistoryEntry(
            id = id,
            name = o.optString("name", id),
            createdAt = o.optLong("createdAt", 0L),
            scene = o.optString("scene", "bedroom"),
            northAngle = o.optDouble("northAngle", 0.0),
            result = resultFromJson(o.optJSONObject("result")) ?: return null
        )
    }

    private fun resultToJson(r: AnalysisResult): JSONObject = JSONObject().apply {
        put("gua", r.gua?.let { JSONObject().apply {
            put("trigram", it.trigram)
            put("group", it.group)
            put("goodSectors", JSONArray(it.goodSectors))
            put("badSectors", JSONArray(it.badSectors))
        } } ?: JSONObject.NULL)
        put("northSet", r.northSet)
        put("objectCount", r.objectCount)
        put("unknownCount", r.unknownCount)
        put("hits", JSONArray(r.hits.map { ruleCardToJson(it) }))
        put("objects", JSONArray(r.objects.map { objInfoToJson(it) }))
        put("plan", r.plan?.let { planToJson(it) } ?: JSONObject.NULL)
        put("polygon", JSONArray(r.polygon.map { ptToJson(it) }))
    }

    private fun resultFromJson(o: JSONObject?): AnalysisResult? {
        if (o == null) return null
        val gua = o.optJSONObject("gua")?.let { g ->
            MingGua.GuaInfo(
                trigram = g.optString("trigram"),
                group = g.optString("group"),
                goodSectors = g.optJSONArray("goodSectors")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                badSectors = g.optJSONArray("badSectors")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )
        }
        return AnalysisResult(
            gua = gua,
            northSet = o.optBoolean("northSet"),
            objectCount = o.optInt("objectCount"),
            hits = o.optJSONArray("hits")?.let { arr ->
                (0 until arr.length()).map { ruleCardFromJson(it, arr.getJSONObject(it)) }
            } ?: emptyList(),
            objects = o.optJSONArray("objects")?.let { arr ->
                (0 until arr.length()).map { objInfoFromJson(it, arr.getJSONObject(it)) }
            } ?: emptyList(),
            plan = o.optJSONObject("plan")?.let { planFromJson(it) },
            unknownCount = o.optInt("unknownCount"),
            polygon = o.optJSONArray("polygon")?.let { arr ->
                (0 until arr.length()).map { ptFromJson(it, arr.getJSONObject(it)) }
            } ?: emptyList()
        )
    }

    private fun ruleCardToJson(c: RuleCard): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("severity", c.severity)
        put("condition", conditionToJson(c.condition))
        put("summary", c.summary)
        put("remedy", JSONArray(c.remedy))
        put("evidence", JSONArray(c.evidence.map { JSONObject().apply {
            put("book", it.book); put("chapter", it.chapter)
            put("original", it.original); put("modern", it.modern)
            put("reliability", it.reliability)
        } }))
    }

    private fun ruleCardFromJson(i: Int, o: JSONObject): RuleCard = RuleCard(
        id = o.optString("id", "r$i"),
        title = o.optString("title"),
        severity = o.optString("severity", "平"),
        condition = RuleCondition.fromJson(o.optJSONObject("condition") ?: JSONObject()),
        summary = o.optString("summary"),
        remedy = o.optJSONArray("remedy")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList(),
        evidence = o.optJSONArray("evidence")?.let { arr ->
            (0 until arr.length()).map { idx ->
                val ev = arr.getJSONObject(idx)
                Evidence(
                    book = ev.optString("book"),
                    chapter = ev.optString("chapter"),
                    original = ev.optString("original"),
                    modern = ev.optString("modern"),
                    reliability = ev.optString("reliability")
                )
            }
        } ?: emptyList()
    )

    private fun conditionToJson(c: RuleCondition): JSONObject = JSONObject().apply {
        put("require", JSONObject().apply { c.require.forEach { put(it, 1) } })
        put("spatial", JSONObject().apply {
            c.spatial.forEach { (k, v) ->
                if (v == listOf("TRUE")) put(k, true) else put(k, JSONArray(v))
            }
        })
        put("match", JSONObject().apply { c.match.forEach { (k, v) -> put(k, v) } })
    }

    private fun objInfoToJson(o: ObjInfo): JSONObject = JSONObject().apply {
        put("id", o.id); put("type", o.type); put("x", o.x); put("z", o.z); put("sector", o.sector)
    }

    private fun objInfoFromJson(i: Int, o: JSONObject): ObjInfo = ObjInfo(
        id = o.optString("id", "obj$i"),
        type = o.optString("type"),
        x = o.optDouble("x"),
        z = o.optDouble("z"),
        sector = o.optString("sector")
    )

    private fun ptToJson(p: Pt): JSONObject = JSONObject().apply { put("x", p.x); put("z", p.z) }

    private fun ptFromJson(i: Int, o: JSONObject): Pt = Pt(o.optDouble("x"), o.optDouble("z"))

    private fun planToJson(p: RemediationPlan): JSONObject = JSONObject().apply {
        put("actions", JSONArray(p.actions.map { JSONObject().apply {
            put("objectId", it.objectId)
            put("toPosition", ptToJson(it.toPosition))
            put("description", it.description)
            put("satisfied", it.satisfied)
        } }))
        put("beforeViolations", JSONArray(p.beforeViolations))
        put("remainingViolations", JSONArray(p.remainingViolations))
        put("blockedNotes", JSONArray(p.blockedNotes))
        put("tradeoffNotes", JSONArray(p.tradeoffNotes))
    }

    private fun planFromJson(o: JSONObject): RemediationPlan = RemediationPlan(
        actions = o.optJSONArray("actions")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                RemediationAction(
                    objectId = a.optString("objectId"),
                    toPosition = a.optJSONObject("toPosition")?.let { Pt(it.optDouble("x"), it.optDouble("z")) }
                        ?: Pt(0.0, 0.0),
                    description = a.optString("description"),
                    satisfied = a.optString("satisfied")
                )
            }
        } ?: emptyList(),
        beforeViolations = o.optJSONArray("beforeViolations")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList(),
        remainingViolations = o.optJSONArray("remainingViolations")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList(),
        blockedNotes = o.optJSONArray("blockedNotes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList(),
        tradeoffNotes = o.optJSONArray("tradeoffNotes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList()
    )
}
