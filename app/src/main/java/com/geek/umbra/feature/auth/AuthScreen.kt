package com.geek.umbra.feature.auth

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraCard
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTextField
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.net.AuthFailure
import com.geek.umbra.net.Gate
import com.geek.umbra.net.HttpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 登录屏的几种视图（iOS AuthScreen.swift:22-32，grace 合进登录成功的吐司）。 */
private enum class AuthView { Login, Register, VerifySent, VerifyNotSent, Forgot, ResetSent }

/** 主密码最少位数（iOS AuthConst.minPasswordHint）。 */
const val MIN_PASSWORD = 10

/**
 * 登录屏 —— 整屏，不是浮层（iOS Features/Auth/AuthScreen.swift）。
 * 布局：字标 → 卡（错误卡 + 表单 + 主钮 + 「注册新账号 · 忘了密码」）→「先单机使用」+ 说明 → 底部连通行。
 */
@Composable
fun AuthScreen(gate: Gate) {
    val c = UmbraTheme.colors
    val auth = AppGraph.auth
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var config by remember { mutableStateOf(auth.config) }
    var view by remember { mutableStateOf(if (gate == Gate.Register) AuthView.Register else AuthView.Login) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fail by remember { mutableStateOf<AuthFailure?>(null) }
    var lockLeft by remember { mutableIntStateOf(0) }
    var waitingOidc by remember { mutableStateOf(false) }

    // 登录方式没问到（断网 / 冷启动网还没通）就一直补问：3 → 6 → … 封顶 30 秒。问到一次就停。
    // 不补问的话，问不到时这一屏会一直是账号密码表单，等网好了也不会换成 Casdoor 的那颗钮。
    LaunchedEffect(Unit) {
        var wait = 3_000L
        while (config == null) {
            auth.fetchConfig()?.let { config = it; return@LaunchedEffect }
            delay(wait); wait = (wait * 2).coerceAtMost(30_000L)
        }
    }
    LaunchedEffect(fail) {
        val f = fail
        if (f is AuthFailure.Locked) {
            lockLeft = f.seconds
            while (lockLeft > 0) { delay(1000); lockLeft-- }
        }
    }

    val oidc = config?.isOidc == true
    val firstAccount = gate == Gate.Register

    fun submit() {
        if (busy) return
        busy = true; fail = null
        scope.launch {
            // 还不知道这台服务器怎么登录时，按下去先再问一次：是 Casdoor 就换成开登录页那颗钮，不把账号密码发给不收它的接口。
            if (config == null) auth.fetchConfig()?.let { config = it }
            if (config?.isOidc == true) { busy = false; return@launch }
            when (view) {
                AuthView.Login -> fail = auth.loginLocal(email, password)
                AuthView.Register -> {
                    val (f, sent) = auth.register(email, password, name, invite)
                    fail = f
                    if (f == null) {
                        // 注册成功后直接登录
                        val lf = auth.loginLocal(email, password)
                        if (lf != null) view = if (sent) AuthView.VerifySent else AuthView.VerifyNotSent
                    }
                }
                AuthView.Forgot -> { fail = auth.forgot(email); if (fail == null) view = AuthView.ResetSent }
                else -> Unit
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Wordmark()
        UmbraCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (gate == Gate.SessionInvalid && fail == null) {
                    UmbraErrorCard(
                        "登录已经失效了",
                        body = "可能是在别的设备上改了密码，或者这次登录已经太久了。这台设备上什么都没坏，「我 › 连接」里也没有要改的东西。重新登录一次就好。",
                        tone = UmbraTone.Warning,
                    )
                }
                fail?.let { FailCard(it, lockLeft) }
                when {
                    oidc -> {
                        Text("用 Umbra 账号登录", color = c.text, style = UmbraType.sectionTitle)
                        Text(
                            if (waitingOidc) "等登录页那边登录…  登录页没有自动打开的话，再按一次。"
                            else "会打开系统的登录页，在那里登录；没有账号的话在那一页注册。登录完成后自动回到这里。",
                            color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                        )
                        UmbraButton("打开登录页登录", {
                            val url = config?.let { auth.beginOidc(it) }
                            if (url == null) fail = AuthFailure.Rejected("服务端给的登录地址没通过校验")
                            else {
                                waitingOidc = true
                                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                            }
                        }, Modifier.fillMaxWidth())
                    }
                    view == AuthView.VerifySent || view == AuthView.VerifyNotSent -> {
                        Text(if (view == AuthView.VerifySent) "去邮箱点那条确认链接" else "账号建好了，但验证信没发出去", color = c.text, style = UmbraType.sectionTitle)
                        Text(
                            if (view == AuthView.VerifySent) "验证信已经发出去了，24 小时内有效。点开之后还要在网页上按一次「确认」—— 那一下必须是你按的。没验证也能先登录，只是以后忘了密码找不回来。"
                            else "别去邮箱等 —— 那封信不会到。账号本身是好的，现在就能登录；验证这件事可以在「我 › 账号」里再点一次「重发验证信」。",
                            color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                        )
                        UmbraButton("回登录", { view = AuthView.Login; fail = null }, Modifier.fillMaxWidth())
                    }
                    view == AuthView.ResetSent -> {
                        Text("如果这个邮箱有账号，重置信已经发出去了", color = c.text, style = UmbraType.sectionTitle)
                        Text("点信里那条链接，在浏览器里设新密码 —— 客户端这边不用再做什么。改完之后所有设备都会退出，包括这台。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
                        UmbraButton("回登录", { view = AuthView.Login; fail = null }, Modifier.fillMaxWidth())
                    }
                    else -> {
                        val title = when (view) {
                            AuthView.Register -> if (firstAccount) "注册第一个账号" else "注册"
                            AuthView.Forgot -> "忘了密码"
                            else -> "登录"
                        }
                        Text(title, color = c.text, style = UmbraType.sectionTitle)
                        if (view == AuthView.Register && firstAccount) {
                            Text("这是这台服务器上的第一个账号，它会接管服务器上已有的全部聊天、任务与记录。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
                        }
                        if (view == AuthView.Forgot) {
                            Text("填注册时用的那个邮箱。重置信只会发到验证过的邮箱。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
                        }
                        UmbraFieldLabel("邮箱")
                        UmbraTextField(email, { email = it }, placeholder = "you@example.com", error = fail is AuthFailure.BadCredentials,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        if (view != AuthView.Forgot) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UmbraFieldLabel("密码", Modifier.weight(1f))
                                UmbraStripAction(if (showPwd) "隐藏" else "显示", c.orangeText, { showPwd = !showPwd })
                            }
                            UmbraTextField(
                                password, { password = it }, error = fail is AuthFailure.BadCredentials,
                                visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            if (view == AuthView.Register) {
                                Text("至少 $MIN_PASSWORD 位。它只管登录，和保险箱的主密码是两回事。", color = c.muted, style = UmbraType.meta)
                            }
                        }
                        if (view == AuthView.Register) {
                            UmbraFieldLabel("显示名")
                            UmbraTextField(name, { name = it })
                            Text("可以留空，留空就显示邮箱。", color = c.muted, style = UmbraType.meta)
                            if (!firstAccount) {
                                UmbraFieldLabel("邀请码")
                                UmbraTextField(invite, { invite = it }, error = fail is AuthFailure.BadInvite, mono = true)
                                Text(
                                    if (fail is AuthFailure.BadInvite) "这个邀请码不对或已经用过了。" else "大小写、连字符、空格都不算数。一码一号，用过就废了。",
                                    color = if (fail is AuthFailure.BadInvite) c.dangerText else c.muted, style = UmbraType.meta,
                                )
                            }
                        }
                        // 只要该填的格子都有内容就能点 —— 账号格不要求像邮箱、密码不在端上卡长度，对不对一律交给服务端判
                        // （sam 2026-10-17：「不应在登录时拦截，是否正确由后台校验」；iOS 登录屏同样不做这些拦截）。
                        // 锁定倒计时那几十秒照旧不让点：那是服务端刚说过的「先别试」。
                        val ready = email.isNotBlank() && when (view) {
                            AuthView.Login, AuthView.Register -> password.isNotEmpty()
                            else -> true
                        } && lockLeft == 0
                        UmbraButton(
                            when (view) { AuthView.Register -> "注册"; AuthView.Forgot -> "发重置信"; else -> if (busy) "登录中…" else "登录" },
                            { submit() }, Modifier.fillMaxWidth(), enabled = ready && !busy,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (view == AuthView.Login) {
                                UmbraStripAction("注册新账号", c.orangeText, { view = AuthView.Register; fail = null })
                                Text("·", color = c.faint)
                                UmbraStripAction("忘了密码", c.orangeText, { view = AuthView.Forgot; fail = null })
                            } else UmbraStripAction("回登录", c.orangeText, { view = AuthView.Login; fail = null })
                        }
                    }
                }
            }
        }
        if (gate != Gate.SessionInvalid) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "先单机使用", color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { auth.useStandalone(); AppGraph.router.tab = com.geek.umbra.navigation.Tab.standaloneHome }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Text(
                    "提醒、保险箱、常用语这些本机功能不用登录；聊天、任务要登录 Umbra 账号，到时在「我 › 账号」里登。",
                    color = c.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp), textAlign = TextAlign.Center,
                )
            }
        }
        Reachability()
    }
}

@Composable
private fun Wordmark() {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(c.orange), contentAlignment = Alignment.Center) {
            Text("U", color = Color.White, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(10.dp))
        Text("Umbra", color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = UmbraType.weight560))
    }
}

@Composable
private fun FailCard(f: AuthFailure, lockLeft: Int) {
    when (f) {
        AuthFailure.Offline -> UmbraErrorCard("连不上这台服务器", body = "地址写错、服务没跑、网络不通都会这样。这一档和密码不对是两回事。")
        AuthFailure.BadCredentials -> UmbraErrorCard("登不进去：邮箱或密码不对", body = "不分开说是哪一个错，因为那等于告诉别人这个邮箱存不存在。连续错太多次会先锁一段时间。")
        is AuthFailure.Locked -> UmbraErrorCard(
            "连续错太多次，先锁住了",
            body = if (lockLeft > 0) "连续错太多次，$lockLeft 秒后自动解开。密码没有被改动，等一会儿再来就行。" else "连续错太多次，先锁住了。密码没有被改动，等一会儿再来就行。",
            tone = UmbraTone.Warning,
        )
        AuthFailure.BadInvite -> Unit // 画在邀请码那一栏下面
        AuthFailure.NoMailer -> UmbraErrorCard("这台服务器没配发信", body = "重置密码这条路不通。这是这台服务器的事实，不是你这个账号的问题 —— 要么找部署它的人配上发信，要么用还记得的密码登录。", tone = UmbraTone.Warning)
        AuthFailure.NoAccountApi -> UmbraErrorCard("这台服务器没有账号功能", body = "不是密码不对，也不是网络问题 —— 重试多少次都一样。要找部署它的人把账号这块装上。", tone = UmbraTone.Warning)
        is AuthFailure.Rejected -> UmbraErrorCard("这一步没通过", body = f.detail)
        is AuthFailure.Other -> UmbraErrorCard("这一步没通过", body = f.detail ?: "HTTP ${f.status}")
    }
}

/** 底部连通行：直接请求 /health（不带凭证），显示延迟或「连不上 · 地址」。 */
@Composable
private fun Reachability() {
    val c = UmbraTheme.colors
    var text by remember { mutableStateOf("…") }
    var ok by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        val r = AppGraph.http.raw("GET", "/health", authed = false)
        ok = r is HttpResult.Ok
        text = if (ok) "服务器可达 · 延迟 ${System.currentTimeMillis() - t0}ms" else "连不上 · ${AppGraph.prefs.serverUrl}"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (ok) c.success else c.faint))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.muted, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
    }
}

