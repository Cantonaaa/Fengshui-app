package com.fengshui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 报告页：命卦 / 北向 / 物体卦位 / 命中规则（原文依据）+ 整改建议。
 * @param result       本次（或历史）分析结果；null 显示空态引导
 * @param northAngle   分析时所用北向（历史查看时用存档值，保证宫位图朝向一致）
 * @param sceneKey     居所类别键（历史查看时用存档场景，保证相关度排序一致）
 * @param onSaveHistory 非 null 时显示「存入历史」按钮；历史查看传 null 隐藏
 */
@Composable
fun ReportScreen(
    result: AnalysisResult?,
    northAngle: Double,
    sceneKey: String,
    onBack: () -> Unit,
    onSaveHistory: (() -> Unit)? = null
) {
    var saved by remember(result) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("堪舆批语", style = MaterialTheme.typography.headlineMedium)
            if (onSaveHistory != null) {
                if (saved) {
                    Text(
                        "已存入历史",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Button(
                        onClick = { onSaveHistory(); saved = true },
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("存入历史") }
                }
            }
            if (result == null) {
                Text(
                    "尚无批语。请先在首页定生辰、起命卦 → 入宅勘察（定向正盘）→ 勘毕起批。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                // 宫位图（房间 + 八卦吉凶方 + 物体点位）
                if (result.polygon.size >= 3) {
                    Text(
                        "宅之宫位（赤=凶方 翠=吉方）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    BaguaMapView(
                        polygon = result.polygon,
                        objects = result.objects,
                        northAngle = northAngle,
                        gua = result.gua,
                        plan = result.plan,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    Text(
                        "■ 翠=吉方 ■ 赤=凶方  ● 翠点=吉位之物 ● 赤点=凶位之物  ┄→=化解迁置之处",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
                // 命卦 + 罗盘
                result.gua?.let { gua ->
                    Section("命卦", Color(0xFF2E7D32)) {
                        Text(gua.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "居所类别：${AppState.sceneName(sceneKey)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Section("罗盘", Color(0xFF2F6B3A)) {
                    Text(if (result.northSet) "已正" else "未正", style = MaterialTheme.typography.bodyMedium)
                }

                Section("所陈器物（${result.objects.size}）", Color(0xFF2F6B3A)) {
                    if (result.objects.isEmpty()) {
                        Text("未辨得器物", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        result.objects.forEach { o ->
                            Text(
                                "${typeNameZH(o.type)} · ${o.sector}方位 (${"%.1f".format(o.x)}, ${"%.1f".format(o.z)})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                // 凶/平（需化解）——按场景相关性+严重度排序
                if (result.badHits.isNotEmpty()) {
                    Section("凶煞与化解（${result.badHits.size}）", Color(0xFFC0392B)) {
                        result.badHits.sortedWith(
                            compareByDescending<RuleCard> { sceneRelevance(it, sceneKey) }
                                .thenByDescending { sevRank(it.severity) }
                        ).forEach { hit -> RuleCardView(hit) }
                    }
                }
                // 吉（正面）——按场景相关性+严重度排序
                if (result.goodHits.isNotEmpty()) {
                    Section("吉象（${result.goodHits.size}）", Color(0xFF2E7D32)) {
                        result.goodHits.sortedWith(
                            compareByDescending<RuleCard> { sceneRelevance(it, sceneKey) }
                                .thenByDescending { sevRank(it.severity) }
                        ).forEach { hit -> RuleCardView(hit) }
                    }
                }
                // 化解之法（移动调整）
                result.plan?.let { plan ->
                    Section("化解之法", Color(0xFF1565C0)) {
                        if (plan.actions.isEmpty() && plan.remainingViolations.isEmpty()) {
                            Text("无需迁动（当前陈设已无凶势）", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            plan.actions.forEach { a ->
                                Text(
                                    "• ${a.description} — ${a.satisfied}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (plan.blockedNotes.isNotEmpty()) {
                                plan.blockedNotes.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp)) }
                            }
                            if (plan.tradeoffNotes.isNotEmpty()) {
                                plan.tradeoffNotes.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
                            }
                            if (plan.remainingViolations.isNotEmpty()) {
                                val titleOf: (String) -> String = { id ->
                                    result.hits.firstOrNull { it.id == id }?.title?.ifEmpty { "相关规则" } ?: "相关规则"
                                }
                                Text(
                                    "仍有 ${plan.remainingViolations.size} 项未化解（${plan.remainingViolations.map(titleOf).joinToString("、")}），宜以遮挡或改造之法待之",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
                if (result.hits.isEmpty()) {
                    Text(
                        "当前未触发吉凶。或命卦未定、罗盘未正，抑或所居为开放之域（卦位/墙邻难判）。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Text("返回首页")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    content()
}

/** 严重度排序权重（大凶最高）。 */
private fun sevRank(s: String): Int = when (s) {
    "大凶" -> 5; "凶" -> 4; "平" -> 3; "吉" -> 2; "大吉" -> 1; else -> 0
}

/** 严重度颜色。 */
private fun sevColor(s: String): Color = when (s) {
    "大凶" -> Color(0xFFB71C1C)
    "凶" -> Color(0xFFD84315)
    "平" -> Color(0xFF616161)
    "吉" -> Color(0xFF2E7D32)
    "大吉" -> Color(0xFF1B5E20)
    else -> Color(0xFF757575)
}

/** 命中与当前场景的相关度（require 命中场景偏好类型数）。 */
private fun sceneRelevance(hit: RuleCard, sceneKey: String): Int =
    hit.condition.require.count { it in AppState.sceneTypes(sceneKey) }

@Composable
private fun Section(title: String, tint: Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(tint, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
    content()
}

@Composable
private fun SeverityBadge(severity: String) {
    val color = sevColor(severity)
    Text(
        severity,
        modifier = Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun RuleCardView(hit: RuleCard) {
    val isDerived = hit.evidence.any { it.reliability == "推演引申" }
    val accent = sevColor(hit.severity)
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 左侧严重度色条
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeverityBadge(hit.severity)
                    Text(
                        hit.title + if (isDerived) "（推演）" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (hit.summary.isNotEmpty()) {
                    Text(hit.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (hit.remedy.isNotEmpty()) {
                    Text(
                        "趋避之法：" + hit.remedy.joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                hit.evidence.forEach { e ->
                    Text(
                        "典出（${e.book}${if (e.chapter.isNotEmpty()) "·${e.chapter}" else ""}）：${e.original}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
