// 任务 / 灵感两页共用的小件：可收起搜索、首屏三态的错误卡与离线条、行内 Markdown、剪贴板、外链、轮询生命周期。
// 都是地基里暂时没有的（iOS 在 DesignSystem/UmbraPrimitives.swift、UmbraStates.swift 等处）；只在本包里用，
// 合并时宜挪进 designsystem（见交回报告）。
package com.geek.umbra.feature.task

import com.geek.umbra.designsystem.UmbraDimens
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMotion
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.net.URI

/**
 * 可收起的搜索（iOS: UmbraPrimitives.swift:803 `UmbraCollapsingSearch`）：默认收成一个放大镜，点开才占一行；
 * 失焦且没内容才收回 —— 有内容说明筛选还在生效，收掉框会让人找不到条件在哪。
 * [trailingNote] 只在展开且没输入时显示（灵感列表挂当前排序）。
 */
@Composable
fun CollapsingSearch(placeholder: String, text: String, onText: (String) -> Unit, modifier: Modifier = Modifier, trailingNote: String? = null) {
    val c = UmbraTheme.colors
    var expanded by remember { mutableStateOf(text.isNotBlank()) }
    val focus = remember { FocusRequester() }
    var wantFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.then(if (expanded) Modifier.fillMaxWidth() else Modifier).height(38.dp).clip(shape).background(c.chip)
            .animateContentSize(tween(UmbraMotion.push)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 38 宽的视觉 + 外扩热区：收起态它就是整个控件，撑热区不撑视觉。
        Box(
            Modifier.size(38.dp).clickable(role = Role.Button) {
                if (!expanded) { expanded = true; wantFocus = true }
            },
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(UmbraIconPaths.search, c.faint, size = 16.dp, strokeWidth = 2f, contentDescription = "搜索") }
        if (expanded) {
            BasicTextField(
                value = text, onValueChange = onText, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = c.text), cursorBrush = SolidColor(c.orange),
                modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { f ->
                    if (!f.isFocused && !wantFocus && text.isBlank()) expanded = false
                },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 15.sp))
                        inner()
                    }
                },
            )
            if (text.isNotEmpty()) {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { onText("") }, contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.xCircle, c.faint, size = 16.dp, contentDescription = "清空搜索")
                }
            } else if (trailingNote != null) {
                Text(trailingNote, color = c.faint, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
    // 焦点晚一拍再给：字段和 focus 同一帧生效时经常丢焦点（字段还没进树）。iOS 同样等 80ms。
    LaunchedEffect(wantFocus) {
        if (wantFocus) {
            delay(80)
            runCatching { focus.requestFocus() }
            wantFocus = false
        }
    }
}

/**
 * 首屏三态里那张整屏错误卡（iOS: UmbraStates.swift `umbraScreenPhase` 的 failed 档）：
 * 第二段分「够不着」和「够得着但没给数据」；第二颗钮把人送到连接设置去。
 */
@Composable
fun PhaseFailedCard(offline: Boolean, title: String?, retry: () -> Unit, modifier: Modifier = Modifier) {
    UmbraErrorCard(
        title = title ?: stringResource(R.string.state_failed_title),
        body = stringResource(if (offline) R.string.state_failed_reason_offline else R.string.state_failed_reason_server),
        actions = listOf(
            stringResource(R.string.state_retry) to retry,
            stringResource(R.string.state_checkServer) to { AppGraph.router.go(Route.SetConn) },
        ),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 离线条（错误卡的 edge 形取 warning 档）。任务 / 灵感是刚拿到的内容，用「下面是刚才拿到的内容」那句。 */
@Composable
fun PhaseOfflineStrip(retry: () -> Unit, live: Boolean = true) {
    val title = stringResource(R.string.state_offline_title)
    val reason = stringResource(if (live) R.string.state_offline_reason_live else R.string.state_offline_reason)
    UmbraErrorStrip("$title · $reason", tone = UmbraTone.Warning, action = stringResource(R.string.state_retry) to retry)
}

/** 进程级剪贴板写入（普通文本，不是保险箱那种敏感值）。 */
fun copyPlain(text: String) {
    val cm = AppGraph.context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Umbra", text))
}

/** 交给系统浏览器打开（去充值这类外链）。只收 http(s) 的调用方已经判过。 */
fun openExternal(url: String) {
    runCatching {
        AppGraph.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * 页面可见（STARTED）期间每 [periodMs] 跑一次 [tick]，首轮立刻跑。页面不可见（切后台 / 被推入页盖住时 NavDisplay
 * 仍组合着它）就停 —— 对应 iOS「onAppear 开、onDisappear 关」的轮询。[keys] 变了重起一轮。
 */
@Composable
fun VisiblePolling(vararg keys: Any?, periodMs: Long, tick: suspend CoroutineScope.(first: Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(tick)
    LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var first = true
            while (true) {
                latest(this, first)
                first = false
                delay(periodMs)
            }
        }
    }
}

/**
 * 行内 Markdown（粗体 / 斜体 / 行内代码 / 链接）→ AnnotatedString。对应 iOS 的
 * `AttributedString(markdown:, .inlineOnlyPreservingWhitespace)` + `ChatLinkRules.sanitize`。
 *
 * ⚠️ 链接是**不可信文本**（执行轮 / 设备 / 模型写的）：只放 https、不带 userinfo，且显示文字若像域名必须是真实
 * host 或其父域，否则剥成纯文字。这是 iOS ChatLinkRules 的简化版 —— 聊天模块的完整版落地后应改调那一份（见交回报告）。
 * 整行只有 `---` / `***` 的分隔线先剥掉（系统解析器不认块级语法，留着就是三个裸横杠）。
 */
fun inlineMarkdown(raw: String, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString {
    val cleaned = raw.split("\n").filter { line ->
        val t = line.trim()
        !(t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' }))
    }.joinToString("\n")
    return buildAnnotatedString { appendInline(cleaned, linkColor, codeBg) }
}

private val linkRe = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")

private fun AnnotatedString.Builder.appendInline(s: String, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        when {
            ch == '[' -> {
                val m = linkRe.matchAt(s, i)
                if (m != null) {
                    val label = m.groupValues[1]
                    val url = m.groupValues[2]
                    if (safeLink(label, url)) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor)))) { append(label) }
                    } else append(label)
                    i = m.range.last + 1
                    continue
                }
                append(ch); i++
            }
            ch == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(ch); i++ }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(s.substring(i + 2, end), linkColor, codeBg) }
                    i = end + 2
                } else { append(mark); i += 2 }
            }
            (ch == '*' || ch == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                val end = s.indexOf(ch, i + 1)
                if (end > i + 1 && !s[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(ch); i++ }
            }
            else -> { append(ch); i++ }
        }
    }
}

/** 简化版链接判据：https、无 userinfo；显示文字像域名时必须是真实 host 或其父域。 */
internal fun safeLink(label: String, url: String): Boolean {
    val u = runCatching { URI(url) }.getOrNull() ?: return false
    if (u.scheme?.lowercase() != "https" || u.userInfo != null) return false
    val host = u.host?.lowercase()?.trimEnd('.') ?: return false
    val domainLike = Regex("""(?i)\b([a-z0-9-]+\.)+[a-z]{2,}\b""").findAll(label.replace('。', '.'))
    for (d in domainLike) {
        val shown = d.value.lowercase()
        if (!(host == shown || host.endsWith(".$shown"))) return false
    }
    return true
}

/**
 * 块级 Markdown 的极简渲染（调研纪要用）：`#` 标题加粗、`-` / `*` / `1.` 列表加圆点、``` 代码块等宽、
 * `>` 引用去掉标记，其余段落走行内。iOS 这里复用聊天的 UmbraMarkdownText；聊天模块落地后应改调那一份（见交回报告）。
 */
@Composable
fun SimpleMarkdown(raw: String, fontSize: Float = 15f) {
    val c = UmbraTheme.colors
    val lines = raw.replace("\r\n", "\n").split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var inCode = false
        val code = StringBuilder()
        val para = StringBuilder()
        val blocks = mutableListOf<Pair<String, String>>() // kind to text
        fun flushPara() { if (para.isNotBlank()) blocks += "p" to para.toString().trim(); para.clear() }
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("```")) {
                if (inCode) { blocks += "code" to code.toString().trimEnd(); code.clear(); inCode = false } else { flushPara(); inCode = true }
                continue
            }
            if (inCode) { code.append(line).append('\n'); continue }
            when {
                t.isEmpty() -> flushPara()
                t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' }) -> flushPara()
                Regex("^#{1,6} ").containsMatchIn(t) -> { flushPara(); blocks += "h" to t.dropWhile { it == '#' }.trim() }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> { flushPara(); blocks += "li" to t.drop(2) }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> { flushPara(); blocks += "ol" to t }
                t.startsWith(">") -> { flushPara(); blocks += "q" to t.drop(1).trim() }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
            }
        }
        if (inCode && code.isNotEmpty()) blocks += "code" to code.toString().trimEnd()
        flushPara()
        val body = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp, color = c.text)
        for ((kind, text) in blocks) {
            when (kind) {
                "h" -> Text(inlineMarkdown(text, c.orangeText, c.chip), style = body.copy(fontWeight = FontWeight.SemiBold))
                "li" -> Row { Text("•", style = body, modifier = Modifier.widthIn(min = 14.dp)); Text(inlineMarkdown(text, c.orangeText, c.chip), style = body) }
                "ol" -> Text(inlineMarkdown(text, c.orangeText, c.chip), style = body)
                "q" -> Row {
                    Box(Modifier.width(3.dp).height((fontSize * 1.6f).dp).background(c.border))
                    Spacer(Modifier.width(8.dp))
                    Text(inlineMarkdown(text, c.orangeText, c.chip), style = body.copy(color = c.muted))
                }
                "code" -> Text(
                    text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.text),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(c.track).padding(10.dp),
                )
                else -> Text(inlineMarkdown(text, c.orangeText, c.chip), style = body)
            }
        }
    }
}
