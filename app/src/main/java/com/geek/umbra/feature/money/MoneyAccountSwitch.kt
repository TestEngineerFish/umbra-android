// 换账号那一问（umbra-ios#89）—— 照 iOS App/AccountSwitch.swift 的 AccountSwitchGate + AppShell.presentAccountSwitch。
//
// 同步那一轮问「这次换号怎么处理」；还没答就举旗、这一轮先不推不拉（本机原样留着），人答了记下答案、补一轮同步。
// 判据与文案在 net/AccountSwitch.kt（AccountSwitchRules）。
//
// ⚠️ iOS 那一问是**记账和灵感共用一次**的（AccountSwitchGate 是 App 级单例，body 同时数两边的待推条数）。
// Android 的灵感模块在另一个分支移植，这里先只数记账（insp = 0）；合并时应把这个询问挪到共用处、两边一起数（报告里写了）。
package com.geek.umbra.feature.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.AccountSwitchChoice
import com.geek.umbra.net.AccountSwitchRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object MoneyAccountSwitch {
    data class Ask(val from: String, val to: String, val money: Int, val insp: Int)

    private val _ask = MutableStateFlow<Ask?>(null)
    /** 正在等人答的那一问（null = 没有）。 */
    val ask: StateFlow<Ask?> = _ask

    /** 本进程里已经答过的换号（键 = from→to）。「以后再说」也记：同一次换号不反复问。 */
    private val answers = HashMap<String, AccountSwitchChoice>()

    /** 这次换号怎么处理。答过回答案；没答过举旗并弹出询问，回 null（这一轮不同步）。 */
    fun choice(from: String, to: String): AccountSwitchChoice? {
        answers[AccountSwitchRules.key(from, to)]?.let { return it }
        val next = Ask(from, to, MoneyLocal.pendingCount, 0)
        if (_ask.value != next) { _ask.value = next; present(next) }
        return null
    }

    /** 人答了。记下答案、放下旗子，补同步那一轮（拉到新东西要重画页面）。 */
    fun answer(c: AccountSwitchChoice) {
        val a = _ask.value ?: return
        answers[AccountSwitchRules.key(a.from, a.to)] = c
        _ask.value = null
        if (c != AccountSwitchChoice.Later) AppGraph.scope.launch { MoneyStore.syncLocalThenRefresh() }
    }

    /**
     * 三个选项的弹层。UmbraAlert 只有两颗钮，所以用底部弹层：「带到这个账号」「丢掉」「以后再说」。
     * 「丢掉」画红（破坏性；丢前在本机另存一份备份，那句话写在正文里）。
     */
    private fun present(a: Ask) {
        AppGraph.router.present(UmbraSheet("money-account-switch") { dismiss ->
            val c = UmbraTheme.colors
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(AccountSwitchRules.TITLE, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Text(AccountSwitchRules.body(a.money, a.insp), color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                UmbraButton(AccountSwitchRules.CARRY_LABEL, { dismiss(); answer(AccountSwitchChoice.Carry) }, Modifier.fillMaxWidth())
                UmbraButton(AccountSwitchRules.DROP_LABEL, { dismiss(); answer(AccountSwitchChoice.Drop) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.DangerOutline)
                UmbraButton(AccountSwitchRules.LATER_LABEL, { dismiss(); answer(AccountSwitchChoice.Later) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
            }
        })
    }
}
