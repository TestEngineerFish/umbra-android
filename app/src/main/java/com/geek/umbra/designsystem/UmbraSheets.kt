// 三种底部弹层：一格输入（iOS 是系统 alert 里塞一格 TextField）、单钮说明（iOS `UmbraAlert(showsCancel: false)`）、
// 选择列表（iOS `UmbraSheet(title:subtitle:items:)`，UmbraOverlays.swift `UmbraSheetLayer`）。
//
// 为什么不是弹窗：Router 的确认弹窗（UmbraAlert）固定两颗钮、没有输入框；长段说明在弹窗里也读不完。
// 从前「我」（presentPrompt）、记账（moneyAskText）、执勤（dutyAskText）各有一份输入弹层，执勤（dutyInfo）、保险箱（presentVaultNotice）、
// 在线状态（「关于」那张）各有一份说明弹层，留白 / 钮高 / 正文色已经分叉 —— 收成这一份（开发文档第五节技术债）。
// 取值以调用点最多的那一份为准：输入弹层照记账 / 执勤（8 处），说明弹层照执勤（5 处）。
// 选择列表从前「我」（presentChoice）、记账（moneyPresentList）、执勤（dutyPresentList）、保险箱（presentVaultSheet）各一份，
// 标题字号（13 / 17）、行高、对勾色、取消钮（有 / 没有、46 / 50 高）都分叉了 —— 照 iOS UmbraSheetLayer 的取值收成一份。
package com.geek.umbra.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.delay

/** 弹层里的左右留白（两种弹层同一个值）。 */
private val SHEET_PAD_X = 20.dp

object UmbraPrompt {
    /**
     * 一格输入：标题 + 一句说明（可无）+ 输入框 + 取消 / 确认。确认后先收起再回调 [onConfirm]（校验失败由调用方吐司）。
     * [allowEmpty] = false 时内容为空确认钮置灰（新建知识库这类空名没有意义的）；默认放行，交给 [onConfirm] 判。
     * [key]：同一个弹层连点两次不叠两张；不给就每次新开一张。
     * [cancelLabel]：文案写死中文的模块（记账、账号）传「取消」，免得英文界面里只有这一颗变成 Cancel；有多语言的用默认。
     */
    fun present(
        title: String,
        confirmLabel: String,
        message: String? = null,
        initial: String = "",
        placeholder: String = "",
        allowEmpty: Boolean = true,
        key: String? = null,
        cancelLabel: String? = null,
        onConfirm: (String) -> Unit,
    ) {
        AppGraph.router.present(UmbraSheet("prompt-" + (key ?: System.nanoTime().toString())) { dismiss ->
            val c = UmbraTheme.colors
            var text by remember { mutableStateOf(initial) }
            val focus = remember { FocusRequester() }
            // 等弹层升起来再要焦点：动画中途 requestFocus 偶尔不弹键盘。
            LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = SHEET_PAD_X).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, color = c.text, style = UmbraType.sectionTitle)
                if (!message.isNullOrEmpty()) Text(message, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
                UmbraTextField(text, { text = it }, Modifier.focusRequester(focus), placeholder = placeholder)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UmbraButton(cancelLabel ?: stringResource(R.string.common_cancel), dismiss, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
                    UmbraButton(confirmLabel, { dismiss(); onConfirm(text) }, Modifier.weight(1f), height = 44.dp, enabled = allowEmpty || text.isNotBlank())
                }
            }
        })
    }
}

object UmbraNotice {
    /**
     * 单钮说明：标题 + 正文 + 一颗钮（默认「知道了」）。正文可能有七八行（执勤的上下文摘要），弹层能滚。
     * 只是让人读完，不是要人做决定，所以钮是次级样式。
     */
    fun present(title: String, body: String, button: String? = null, key: String? = null) {
        AppGraph.router.present(UmbraSheet("notice-" + (key ?: System.nanoTime().toString())) { dismiss ->
            val c = UmbraTheme.colors
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = SHEET_PAD_X).padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, color = c.text, style = UmbraType.sectionTitle)
                Text(body, color = c.text, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                UmbraButton(button ?: stringResource(R.string.common_gotIt), dismiss, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 48.dp)
            }
        })
    }
}

/** 选择列表的一项（iOS `UmbraSheetItem`）。[note] 写在名字下面（档位说明、破坏性后果）；[checked] 画对勾；[destructive] 红字。 */
data class UmbraChoiceItem(
    val label: String,
    val note: String? = null,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

object UmbraChoice {
    /**
     * 标题 + 副标题 + 一张行卡 + 底部「取消」。点一项**先收起再执行**（iOS 同序：先 onClose 再 action）—— 执行里常常还要再开一层
     * （确认弹窗 / 下一步选择器）。项多时整张能滚。
     * [onDismissed]：弹层**因为任何原因**离开（选了 / 取消 / 下滑关掉）都回调一次，带「是不是选了一项」——
     * 知识库同名询问靠它把「下滑关掉」认成取消（iOS KbClashPrompt.sheetsChanged 那一套）。
     * 「取消」走通用的 `common_cancel`（英文界面是 Cancel）。iOS UmbraSheetLayer 那颗写死了中文「取消」，那是 iOS 的缺陷，Android 不跟。
     */
    fun present(
        title: String,
        items: List<UmbraChoiceItem>,
        subtitle: String? = null,
        key: String? = null,
        onDismissed: ((picked: Boolean) -> Unit)? = null,
    ) {
        AppGraph.router.present(UmbraSheet("choice-" + (key ?: System.nanoTime().toString())) { dismiss ->
            val c = UmbraTheme.colors
            var picked by remember { mutableStateOf(false) }
            DisposableEffect(Unit) { onDispose { onDismissed?.invoke(picked) } }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = UmbraDimens.pagePadX).padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 头部：标题 600/13、副标题 12（iOS UmbraSheetLayer）。
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = c.text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                    if (subtitle != null) Text(subtitle, color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
                }
                UmbraGroupCard {
                    items.forEachIndexed { i, it ->
                        if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                                .clickable(role = Role.Button) { picked = true; dismiss(); it.action() }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(it.label, color = if (it.destructive) c.dangerText else c.text, style = TextStyle(fontSize = 16.sp))
                                // 要读到的话（档位说明、破坏性后果）：muted，不是 faint（227 §二2）。
                                if (it.note != null) Text(it.note, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
                            }
                            if (it.checked) {
                                Spacer(Modifier.width(10.dp))
                                UmbraIcon(UmbraIconPaths.check, c.orangeText, size = 18.dp, strokeWidth = 2.4f)
                            }
                        }
                    }
                }
                // 取消不是要推销的动作：次级样式，不用橙色。
                UmbraButton(stringResource(R.string.common_cancel), dismiss, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 50.dp)
            }
        })
    }
}
