// 「全部日志」页 —— 照搬 iOS Features/Duty/DutyLogView.swift（契约 §16.8 第 3 条）：全量、按条目筛；每行点开「为什么」
// → `GET /duties/decisions/{id}`（规则拦下的没有 decision，只显示理由）；被并的写「并进 08:00 早报」，延后的写「延后到 09:30」。
// 执勤页底下那 14 天用同一套行（[dutyLogRow]），两处长得一样、说法一样。
package com.geek.umbra.feature.duty

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme

@Composable
internal fun DutyLogView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    /** 只看哪一条（null = 全部）。 */
    var dutyId by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(dutyId, tick) { DutyStore.loadFullLog(dutyId) }

    val filterName = dutyId?.let { id -> st.list?.items?.firstOrNull { it.id == id }?.name } ?: ctx.getString(R.string.duty_log_filterAll)
    UmbraScreen(
        title = ctx.getString(R.string.duty_log), onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = 28.dp),
    ) {
        dutySection(DutySection(
            "filter", footer = ctx.getString(R.string.duty_log_help),
            rows = listOf(DutyRow(ctx.getString(R.string.duty_log_filter), value = filterName, chevron = true) {
                val items = listOf(DutySheetItem(ctx.getString(R.string.duty_log_filterAll), checked = dutyId == null) { dutyId = null; tick++ }) +
                    (st.list?.items ?: emptyList()).map { it -> DutySheetItem(it.name, checked = dutyId == it.id) { dutyId = it.id; tick++ } }
                dutyPresentList("log-filter", ctx.getString(R.string.duty_log_filter), null, items)
            }),
        ))
        val groups = DutyRules.groupByDay(st.fullLog)
        val err = st.fullLogError
        when {
            err != null && st.fullLog.isEmpty() -> dutySection(DutySection(
                "err", footer = err.ifEmpty { ctx.getString(R.string.duty_loadFailed) }, footerTone = DutyFooterTone.Danger,
                rows = listOf(DutyRow(ctx.getString(R.string.duty_retry), tint = c.orangeText) { tick++ }),
            ))
            groups.isEmpty() -> dutySection(DutySection(
                "empty",
                rows = listOf(DutyRow(ctx.getString(if (st.fullLogLoading) R.string.duty_loading else R.string.duty_log_emptyAll), tint = c.faint)),
            ))
            else -> for (g in groups) {
                dutySection(DutySection(
                    "log-${g.day}", header = dutyDayHeader(g.day, locale),
                    rows = g.items.map { s -> dutyLogRow(ctx, tr, c, s, st.fullLog) },
                ))
            }
        }
    }
}
