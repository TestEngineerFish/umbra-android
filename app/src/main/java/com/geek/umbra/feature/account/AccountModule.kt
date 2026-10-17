package com.geek.umbra.feature.account

import com.geek.umbra.app.SessionGuard

/**
 * 账号模块的进程级入口：在会话守卫上登记联网部分的「关 / 开」。
 * 账号页本身是本地功能（未登录也能进，只给「重新登录」）；联网的只有商城那一份进程级状态：
 * 关的时候停掉等待支付的轮询、清掉上一个账号的余额 / 道具 / 会员，换账号后首帧不画别人的。
 */
object AccountModule {
    fun register(guard: SessionGuard) {
        guard.register("account.shop", onLock = { ShopStore.clear() }, onUnlock = {})
    }
}
