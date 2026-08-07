package com.fengshui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 报告页：命卦 / 北向 / 物体卦位 / 命中规则（原文依据）+ 整改建议。 */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val result = AppState.analysisResult
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("风水分析报告", style = MaterialTheme.typography.headlineMedium)
            if (result == null) {
                Text(
                    "尚无分析结果。请先在首页设置生辰 → 扫描房间（校准北向）→ 完成扫描并分析。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                // 命卦 + 北向
                result.gua?.let { gua ->
                    Section("命卦") { Text(gua.summary, style = MaterialTheme.typography.bodyMedium) }
                }
                Section("北向") {
                    Text(if (result.northSet) "已校准" else "未校准", style = MaterialTheme.typography.bodyMedium)
                }
                if (result.unknownCount > 0) {
                    Section("未识别物体") {
                        Text(
                            "${result.unknownCount} 个检测未落入判断类别（分类置信度不足），不计入风水分析。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                // 物体清单
                Section("识别物体（${result.objects.size}）") {
                    if (result.objects.isEmpty()) {
                        Text("未识别到物体", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        result.objects.forEach { o ->
                            Text(
                                "${o.type} · ${o.sector}方位 (${"%.1f".format(o.x)}, ${"%.1f".format(o.z)})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                // 凶/平（需整改）——按严重度排序
                if (result.badHits.isNotEmpty()) {
                    Section("问题与整改（${result.badHits.size}）") {
                        result.badHits.sortedByDescending { sevRank(it.severity) }.forEach { hit ->
                            RuleCardView(hit)
                        }
                    }
                }
                // 吉（正面）——按严重度排序
                if (result.goodHits.isNotEmpty()) {
                    Section("吉象（${result.goodHits.size}）") {
                        result.goodHits.sortedByDescending { sevRank(it.severity) }.forEach { hit ->
                            RuleCardView(hit)
                        }
                    }
                }
                // 整改方案（L1 移动）
                result.plan?.let { plan ->
                    Section("整改方案（L1 移动）") {
                        if (plan.actions.isEmpty() && plan.remainingViolations.isEmpty()) {
                            Text("无需移动（当前布局无待消除凶势）", style = MaterialTheme.typography.bodyMedium)
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
                                Text(
                                    "仍有 ${plan.remainingViolations.size} 项未消除（${plan.remainingViolations.joinToString(",")}），需遮挡/改造（L2/L3）",
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
                        "当前未触发规则。可能因素：命卦未设置、北向未校准，或扫描环境为开放空间（卦位/墙邻判定有限）。",
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

@Composable
private fun RuleCardView(hit: RuleCard) {
    val isDerived = hit.evidence.any { it.reliability == "推演引申" }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "[${hit.severity}] ${hit.title}${if (isDerived) "（推演）" else ""}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (hit.summary.isNotEmpty()) {
                Text(hit.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (hit.remedy.isNotEmpty()) {
                Text(
                    "整改建议：" + hit.remedy.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            hit.evidence.forEach { e ->
                Text(
                    "原文（${e.book}${if (e.chapter.isNotEmpty()) "·${e.chapter}" else ""}）：${e.original}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
