package com.fengshui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 轮播基础理论：仅原文 + 小字出处。取底层义理与纲领性语句。 */
object TheoryProvider {
    data class Quote(val text: String, val source: String)

    val QUOTES = listOf(
        Quote("葬者乘生气也", "《葬书》"),
        Quote("气乘风则散，界水则止", "《葬书》"),
        Quote("明堂惜水如惜血，堂里避风如避贼", "《葬书》"),
        Quote("古人聚之使不散，行之使有止，故谓之风水", "《葬书》"),
        Quote("有气者为生，无气者为死", "《发微论》"),
        Quote("小聚则地小成，大聚则地大成，散而不聚，不可以言地矣", "《发微论》"),
        Quote("观形貌者得其伪，观性情者得其真", "《发微论》"),
        Quote("宅有五虚令人贫耗，五实令人富贵", "《宅经》"),
        Quote("刑祸之方缩复缩，犹恐灾殃枉相逐", "《宅经》"),
        Quote("凡修宅次第，先修刑祸后修福德即吉；先修福德后修刑祸即凶", "《宅经》"),
        Quote("福德之方，生气到其位即修，令清洁阔厚，一家获安", "《宅经》"),
        Quote("阳宅更招东方北方为重", "《宅经》"),
        Quote("震巽坎离为东四位生人，乾坤艮兑为西四位生人，东西各异修之，祸福无差", "《阳宅十书》"),
        Quote("居室通气只此门户耳，和气则致祥，乖气则致戾，乃造化一定之理", "《阳宅十书》"),
        Quote("吉星宜高大，凶星宜低小", "《阳宅十书》"),
        Quote("凡安门专主福元，配合吉星，无不大发，须避直冲尖射", "《阳宅十书》"),
        Quote("凡宅左有流水谓之青龙，右有长道谓之白虎，前有污池谓之朱雀，后有丘陵谓之玄武，为最贵地", "《阳宅十书》"),
        Quote("凡宅前低后高世出英豪，前高后低长幼昏迷", "《阳宅十书》"),
        Quote("火淹即是厨房，止宜在宅凶方，不宜在吉方", "《阳宅十书》"),
        Quote("六事关系甚重，专从宅上轮布九星，宜在本宅生旺方，忌在关煞死退方", "《宅法举隅》"),
        Quote("安床宜择宅之吉方吉间", "《宅法举隅》"),
        Quote("东西命房宜东四方，西四命房宜西四方", "《宅法举隅》"),
        Quote("灶座宜压主命四凶方，灶门宜向主命四吉方，谓之压煞向生", "《宅法举隅》"),
        Quote("灶位与床位忌相反为冲关，宜相生为吉", "《宅法举隅》"),
        Quote("大门为全宅之气口，最要端正，顺理顺势", "《宅法举隅》"),
        Quote("卧房宜择本宅生旺方，房内喜明忌暗", "《宅法举隅》"),
        Quote("凡作书室，宜取宅之一白四绿方", "《宅法举隅》"),
        Quote("仓库属土，宜在土方，不宜在木方", "《宅法举隅》"),
        Quote("库房宜兑及乾艮方，不宜震巽离方", "《宅法举隅》"),
        Quote("店铺以门为重，要迎水开门，忌开在煞方", "《宅法举隅》")
    )
}

/** 起批过程中轮播基础理论（原文 + 出处，点按切下一条）。 */
@Composable
fun TheoryCarousel(modifier: Modifier = Modifier) {
    val items = TheoryProvider.QUOTES
    var idx by remember { mutableIntStateOf(0) }

    LaunchedEffect(items.size) {
        while (true) {
            delay(5000)
            idx = (idx + 1) % items.size
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().clickable { idx = (idx + 1) % items.size },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val q = items[idx]
            Text(
                q.text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                q.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
