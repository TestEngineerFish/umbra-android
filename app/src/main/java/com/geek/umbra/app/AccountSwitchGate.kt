// 换账号那一问（umbra-ios#89）—— 照 iOS App/AccountSwitch.swift 的 AccountSwitchGate + AppShell.presentAccountSwitch。
//
// **记账（生活账本）和灵感共用一次询问**：两边的同步各自走到「换了账号且本机有没推的」那一步时都来问这里，
// 同一次换号（from→to）只弹一次，正文同时列两边的待推条数；答了两边一起补一轮同步。
// 不阻塞同步等答案：没答之前那一轮到此为止（不推不拉、本机原样）。答案只记在内存里：「以后再说」下次启动会再问。
// 判据与文案在 net/AccountSwitch.kt（AccountSwitchRules）。
package com.geek.umbra.app

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
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.feature.inspiration.InspirationLocal
import com.geek.umbra.feature.inspiration.InspirationStore
import com.geek.umbra.feature.money.MoneyLocal
import com.geek.umbra.feature.money.MoneyStore
import com.geek.umbra.navigation.UmbraSheet
import com.geek.umbra.net.AccountSwitchChoice
import com.geek.umbra.net.AccountSwitchRules
import kotlinx.coroutines.launch

object AccountSwitchGate {
    private data class Ask(val from: String, val to: String)

    private var ask: Ask? = null
    /** 本进程里已经答过的换号（键 = from→to）。「以后再说」也记：同一次换号这次运行里不反复问。 */
    private val answers = HashMap<String, AccountSwitchChoice>()

    /** 这次换号怎么处理。答过回答案；没答过弹一次询问并回 null（调用方这一轮不同步）。 */
    @Synchronized
    fun choice(from: String, to: String): AccountSwitchChoice? {
        answers[AccountSwitchRules.key(from, to)]?.let { return it }
        val next = Ask(from, to)
        if (ask != next) { ask = next; present() }
        return null
    }

    @Synchronized
    private fun answer(c: AccountSwitchChoice) {
        val a = ask ?: return
        answers[AccountSwitchRules.key(a.from, a.to)] = c
        ask = null
        if (c == AccountSwitchChoice.Later) {
            AppGraph.router.showToast(AccountSwitchRules.LATER_NOTE)
            return
        }
        AppGraph.scope.launch { MoneyStore.syncLocalThenRefresh() }
        AppGraph.scope.launch { InspirationLocal.sync(); InspirationStore.load() }
    }

    /**
     * 三个选项：UmbraAlert 只有两颗钮，所以用底部弹层。「丢掉」画红（丢前本机另存一份备份，正文里写了）。
     * 下拉关掉弹层 = 没答：ask 留着，这次运行不再弹，下一次启动再问。
     */
    private fun present() {
        AppGraph.scope.launch {
            AppGraph.router.present(UmbraSheet("account-switch") { dismiss ->
                val c = UmbraTheme.colors
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(AccountSwitchRules.TITLE, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                    Text(
                        AccountSwitchRules.body(MoneyLocal.pendingCount, InspirationLocal.pendingCount),
                        color = c.muted, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                    )
                    UmbraButton(AccountSwitchRules.CARRY_LABEL, { dismiss(); answer(AccountSwitchChoice.Carry) }, Modifier.fillMaxWidth())
                    UmbraButton(AccountSwitchRules.DROP_LABEL, { dismiss(); answer(AccountSwitchChoice.Drop) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.DangerOutline)
                    UmbraButton(AccountSwitchRules.LATER_LABEL, { dismiss(); answer(AccountSwitchChoice.Later) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
                }
            })
        }
    }
}
