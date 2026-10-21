// 问答卡（iOS: Features/Chat/QuestionCard.swift）：秘书在派活之前把歧义问清楚。
//
// 一屏一题「1 / N」，一次性提交。作答状态存在 ChatBlock.Question 里（不放页面状态：滚出屏再回来会丢）。
// 取值：卡宽 300、圆角 18、头部 11/13；选项行最小高 44 / 内边距 9-12 / 圆角 10 / 描边 1.5；选中标记 20（多选 6 圆角、单选全圆）。
// 「必答未答时提交键置灰并给一行原因，不弹 alert」—— 稿明写，别改成弹窗。
package com.geek.umbra.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType

private const val CUSTOM_LABEL = "其它（自己写）"

@Composable
fun QuestionCard(q: ChatBlock.Question) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    val headBg = if (q.done) c.successSoft else c.orangeSoft
    val headFg = if (q.done) c.success else c.orangeText
    Column(Modifier.width(CARD_W).clip(shape).background(c.card).border(UmbraDimens.borderW, headBg, shape)) {
        Row(Modifier.fillMaxWidth().background(headBg).padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(if (q.done) UmbraIconPaths.check else UmbraIconPaths.task, headFg, size = 16.dp, strokeWidth = 2f)
            Spacer(Modifier.width(8.dp))
            Text(if (q.done) "秘书的问题 · 已答完" else "秘书想问你几件事", color = headFg, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (!q.done) Text("${minOf(q.at + 1, q.questions.size)} / ${q.questions.size}", color = headFg, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.alpha(0.75f))
        }
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp4)) {
            if (q.title.isNotEmpty()) Text(q.title, color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560, lineHeight = 21.75.sp))
            if (q.done) Submitted(q) else Pending(q)
        }
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
private fun Pending(q: ChatBlock.Question) {
    val c = UmbraTheme.colors
    val cur = q.questions.getOrNull(minOf(q.at, q.questions.size - 1).coerceAtLeast(0))
    if (cur == null) {
        // 服务端给了一张没有题目的卡：如实说，不画一张空壳
        Text("这张问答卡没有题目，等秘书补一下。", color = c.muted, style = TextStyle(fontSize = 13.sp))
        return
    }
    val picked = q.picked[cur.id] ?: emptyList()
    val isLast = q.at >= q.questions.size - 1
    // 最后一题看整张卡答没答完，中间题只看当前这题 —— 中途拦住会让人没法先跳过再回来
    val canGo = if (isLast) q.allFilled else q.filled(cur)
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(cur.text, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560, lineHeight = 21.75.sp), modifier = Modifier.weight(1f))
            if (cur.multi) { Spacer(Modifier.width(8.dp)); Tag("可多选", c.orangeText, c.orangeSoft) }
            if (cur.optional) { Spacer(Modifier.width(8.dp)); Tag("可跳过", c.faint, c.chip) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val opts = cur.options.map { it to it } + if (cur.allowCustom) listOf(QuestionAnswerRules.CUSTOM_KEY to CUSTOM_LABEL) else emptyList()
            opts.forEach { (key, label) -> OptionRow(q.cardId, cur, key, label, key in picked) }
        }
        if (QuestionAnswerRules.CUSTOM_KEY in picked) {
            val shape = RoundedCornerShape(UmbraDimens.radiusControl)
            val value = q.custom[cur.id] ?: ""
            BasicTextField(
                value = value,
                onValueChange = { ChatStore.setCustomAnswer(q.cardId, cur.id, it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = c.text),
                cursorBrush = SolidColor(c.orange),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(c.bg).border(1.5.dp, c.orange, shape).padding(horizontal = UmbraDimens.sp4), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("在这里作答", color = c.faint, style = TextStyle(fontSize = 15.sp))
                        inner()
                    }
                },
            )
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(UmbraDimens.radiusControl)
            if (q.at > 0) {
                Box(
                    Modifier.height(44.dp).clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
                        .clickable(role = Role.Button) { ChatStore.moveQuestion(q.cardId, -1) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("上一题", color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560)) }
            }
            val notConnected = stringResource(R.string.chat_notConnected)
            Box(
                Modifier.weight(1f).height(44.dp).clip(shape).background(if (canGo) c.orange else c.chip)
                    .clickable(enabled = canGo, role = Role.Button) {
                        if (isLast) { if (!ChatStore.submitAnswers(q.cardId)) AppGraph.router.showToast(notConnected) }
                        else ChatStore.moveQuestion(q.cardId, 1)
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (isLast) "提交答案" else "下一题", color = if (canGo) c.onAccent else c.faint, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) }
        }
        // 置灰时必须给出原因，就一行，不弹窗
        if (!canGo) Text(if (isLast) "还有必答的题没答，答完才能提交。" else "这题是必答的。", color = c.faint, style = TextStyle(fontSize = 12.sp, lineHeight = 18.6.sp))
    }
}

@Composable
private fun OptionRow(cardId: String, item: QuestionItem, key: String, label: String, on: Boolean) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    val markShape = RoundedCornerShape(if (item.multi) 6.dp else 10.dp)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.tapMin).clip(shape).background(if (on) c.orangeSoft else c.card)
            .border(1.5.dp, if (on) c.orange else c.border, shape)
            .clickable(role = if (item.multi) Role.Checkbox else Role.RadioButton) { ChatStore.pickAnswer(cardId, item.id, key, item.multi) }
            .padding(horizontal = UmbraDimens.sp4, vertical = UmbraDimens.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(markShape).background(if (on) c.orange else Color.Transparent).border(1.5.dp, if (on) c.orange else c.border, markShape), contentAlignment = Alignment.Center) {
            if (on) UmbraIcon(UmbraIconPaths.check, c.onAccent, size = 12.dp, strokeWidth = 3.2f)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (on) c.orangeText else c.text, style = TextStyle(fontSize = 14.5.sp, lineHeight = 20.3.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Submitted(q: ChatBlock.Question) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.check, c.success, size = 15.dp, strokeWidth = 2.4f)
            Spacer(Modifier.width(7.dp))
            // 别的端答的，我们拿不到答案 —— 那就不显示，别编
            Text(if (q.answered.isEmpty()) "别的设备已经答过了" else "已提交，秘书这就去安排", color = c.success, style = TextStyle(fontSize = 13.5.sp, fontWeight = UmbraType.weight560))
        }
        q.answered.forEach { (qq, v) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(c.chip).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(qq, color = c.faint, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.width(96.dp))
                Spacer(Modifier.width(8.dp))
                Text(v, color = c.text, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560, lineHeight = 18.sp), modifier = Modifier.weight(1f))
            }
        }
    }
}
