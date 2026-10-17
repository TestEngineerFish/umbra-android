package com.geek.umbra.feature.tool

/**
 * 工具页卡底那一行怎么说 —— 照抄 iOS Features/Tool/ToolCardRules.swift。
 * 规矩：拉失败 / 还没拉到 / 不知道的时候**不许说成「还没有」**（台账第五轮「轮扫 Tool」）。
 */
object ToolCardRules {
    enum class Load { Loading, Failed, Loaded }

    fun load(loadedOnce: Boolean, firstLoading: Boolean): Load = when {
        loadedOnce -> Load.Loaded
        firstLoading -> Load.Loading
        else -> Load.Failed
    }

    const val UNKNOWN = "—"
    const val FAILED_LINE = "没拉到，下拉重试"

    fun taskSub(load: Load, total: Int, running: Int, failed: Int, resumable: Int, limit: Int): String {
        when (load) {
            Load.Loading -> return UNKNOWN
            Load.Failed -> return FAILED_LINE
            Load.Loaded -> Unit
        }
        if (total == 0) return "还没有任务"
        val parts = mutableListOf("$running 个执行中", "$failed 个失败")
        if (resumable > 0) parts += "$resumable 个可接着跑"
        val body = parts.joinToString(" · ")
        return if (total >= limit) "最近 $limit 个：$body" else body
    }

    fun inspSub(load: Load, total: Int, open: Int): String = when (load) {
        Load.Loading -> UNKNOWN
        Load.Failed -> FAILED_LINE
        Load.Loaded -> if (total == 0) "还没有灵感" else "$total 条 · $open 条待整理"
    }

    fun phraseSub(count: Int, everSynced: Boolean): String = when {
        count > 0 -> "$count 条 · 跨端同步"
        everSynced -> "还没有常用语"
        else -> UNKNOWN
    }

    fun vaultSub(unlocked: Boolean, count: Int, exists: Boolean): String = when {
        unlocked -> "$count 条 · 已解锁"
        exists -> "已锁定"
        else -> UNKNOWN
    }

    fun moneySub(ledger: String?, ymLabel: String?, expense: String?): String {
        if (ymLabel == null || expense == null) return UNKNOWN
        val scope = ledger?.let { "$it · $ymLabel" } ?: ymLabel
        return "$scope $expense"
    }
}
