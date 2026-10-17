package com.geek.umbra.net

/**
 * 换账号 / 换服务器时本机待推数据怎么办 —— 对应 iOS Networking/AccountSwitchRules.swift（umbra-ios#89/#93）。
 * **不能静默合并两个人的数据**：有没推上去的就问（带过去 / 丢掉 / 以后再说）。比较身份时连服务器地址一起比。
 * 记账（生活账本）和灵感共用一次询问。
 */
enum class AccountSwitchPlan { Adopt, Same, Replace, Ask }
enum class AccountSwitchChoice { Carry, Drop, Later }

object AccountSwitchRules {
    /** 老版本共享密钥时代的占位用户名：遇到它当「没记过」。 */
    const val LEGACY_SHARED_USER = "shared"

    fun plan(lastUser: String?, lastServer: String?, user: String, server: String, pending: Int): AccountSwitchPlan {
        if (lastUser.isNullOrEmpty() || lastUser == LEGACY_SHARED_USER) return AccountSwitchPlan.Adopt
        if (lastUser == user && (lastServer == null || lastServer == server)) return AccountSwitchPlan.Same
        return if (pending > 0) AccountSwitchPlan.Ask else AccountSwitchPlan.Replace
    }

    fun identity(user: String, server: String) = server + "\u0000" + user
    fun key(from: String, to: String) = from + "\u0001" + to

    const val TITLE = "换了账号，本机还有上一个账号没同步上去的数据"
    fun body(money: Int, insp: Int): String {
        val parts = buildList {
            if (money > 0) add("记账 $money 条")
            if (insp > 0) add("灵感 $insp 条")
        }
        val list = if (parts.isEmpty()) "有几条" else parts.joinToString("，")
        return "$list。已经同步过的记账 / 灵感留在上一个账号的云端，这台设备会换成这个账号的数据。没同步的这些要带到这个账号，还是丢掉？丢掉前会在这台设备上另存一份备份。"
    }
    const val CARRY_LABEL = "带到这个账号"
    const val DROP_LABEL = "丢掉"
    const val LATER_LABEL = "以后再说"
    const val LATER_NOTE = "换了账号：本机还有上一个账号没同步的数据，等你决定带过去还是丢掉"
}
