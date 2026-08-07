package com.fengshui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 生辰输入页：公历/农历可切换，自动换算，计算命卦。仅存本地。 */
@Composable
fun BirthSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var isLunar by remember { mutableStateOf(AppState.birthIsLunar) }
    var year by remember { mutableStateOf(AppState.birthYear?.toString() ?: "1990") }
    var month by remember { mutableStateOf(AppState.birthMonth?.toString() ?: "1") }
    var day by remember { mutableStateOf(AppState.birthDay?.toString() ?: "1") }
    var gender by remember { mutableStateOf(AppState.gender ?: "男") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeContentPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("生辰设置", style = MaterialTheme.typography.headlineMedium)
            Text(
                "选择公历或农历填写，自动换算后计算命卦。仅存于本机。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
            // 公历/农历切换
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { isLunar = false }) {
                    Text(if (!isLunar) "● 公历" else "公历")
                }
                OutlinedButton(onClick = { isLunar = true }) {
                    Text(if (isLunar) "● 农历" else "农历")
                }
            }

            // 年月日
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberField(year, { year = it.take(4) }, "年份", Modifier.weight(1.5f))
                NumberField(month, { month = it.take(2) }, "月", Modifier.weight(1f))
                NumberField(day, { day = it.take(2) }, "日", Modifier.weight(1f))
            }

            // 换算预览
            val y = year.toIntOrNull(); val m = month.toIntOrNull(); val d = day.toIntOrNull()
            if (y != null && m != null && d != null && m in 1..12 && d in 1..31) {
                Text(
                    conversionText(isLunar, y, m, d),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                val ly = LunarUtil.lunarYear(isLunar, y, m, d)
                if (ly != null) {
                    val gua = MingGua.compute(ly, gender, AppState.baguaJson)
                    if (gua.trigram != "?") {
                        Text(
                            "命卦：${gua.summary}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 性别
            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { gender = "男" }) { Text(if (gender == "男") "● 男" else "男") }
                OutlinedButton(onClick = { gender = "女" }) { Text(if (gender == "女") "● 女" else "女") }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    val yy = year.toIntOrNull(); val mm = month.toIntOrNull(); val dd = day.toIntOrNull()
                    val ok = yy != null && mm != null && dd != null &&
                        yy in 1900..2100 && mm in 1..12 &&
                        (if (isLunar) dd in 1..30 else dd in 1..31) &&
                        LunarUtil.lunarYear(isLunar, yy, mm, dd) != null
                    if (!ok) {
                        error = "请输入有效生辰（${if (isLunar) "农历：月1-12，日1-30" else "公历：月1-12，日1-31"}）"
                    } else {
                        AppState.birthYear = yy
                        AppState.birthMonth = mm
                        AppState.birthDay = dd
                        AppState.birthIsLunar = isLunar
                        AppState.gender = gender
                        AppState.refreshGua()
                        AppState.save(context)
                        onDone()
                    }
                },
                modifier = Modifier.padding(top = 24.dp)
            ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { ch -> ch.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private fun conversionText(isLunar: Boolean, y: Int, m: Int, d: Int): String {
    if (isLunar) {
        val s = LunarUtil.lunarToSolar(y, m, d)
        return if (s != null) "→ 公历 ${s.first}年${s.second}月${s.third}日" else "（农历日期无效）"
    } else {
        val l = LunarUtil.solarToLunar(y, m, d)
        return if (l != null) "→ 农历 ${l.first}年${LunarUtil.lunarMonthName(l.second)}${LunarUtil.lunarDayName(l.third)}" else "（公历日期无效）"
    }
}
