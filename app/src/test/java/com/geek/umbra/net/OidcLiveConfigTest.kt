package com.geek.umbra.net

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上 `/auth/config` 的真实回包（2026-10-17 在 ECS 本机取）原样喂进去：解得出 oidc、端点过得了校验、拼得出授权地址。
 * 为什么要这条：Android 上看到的是自家表单而不是 Casdoor 登录页，要先排除「回包本身解不出 / 校验不过」这一层。
 * 回包比 AuthConfig 多一个 account_url —— 也顺带钉住「多出来的字段不让解码失败」。
 */
class OidcLiveConfigTest {
    private val live = """{"provider":"oidc","issuer":"https://auth.tingyusha.xyz","client_id":"7d8863ed4e5d3e6b51ee","authorization_endpoint":"https://auth.tingyusha.xyz/login/oauth/authorize","token_endpoint":"https://auth.tingyusha.xyz/api/login/oauth/access_token","end_session_endpoint":"https://auth.tingyusha.xyz/api/logout","account_url":"https://auth.tingyusha.xyz/account","scope":"openid profile email"}"""

    @Test fun liveConfigGoesOidc() {
        val c = UmbraJson.decodeFromString(AuthConfig.serializer(), live)
        assertTrue(c.isOidc)
        assertTrue(OidcRules.validate(c))
        val url = OidcRules.authorizeUrl(c, OidcRules.Pkce("verifier", "challenge", "state1"))
        assertTrue(url, url.startsWith("https://auth.tingyusha.xyz/login/oauth/authorize?client_id=7d8863ed4e5d3e6b51ee&response_type=code&redirect_uri=umbra%3A%2F%2Fauth%2Fcallback"))
    }
}
