package com.geek.umbra.feature.chat

/**
 * 长连接断线重连后补拉一个会话（geek-13 11-12，调度在 app/ReconnectCatchUp）：拉最新一页，和手上的块比，
 * 决定「把新的接到后面」还是「整段重拉」还是「什么都不动」。纯函数，有用例。
 */
object ChatCatchUpRules {
    /** 拉回来的一行：id、角色、正文（比回声用）。 */
    data class Row(val id: Long, val role: String, val content: String)

    sealed interface Plan {
        data object None : Plan
        /** 拉回来的整页都比手上的新、页又是满的：中间可能还有没拉到的 —— 整段重拉，不留缺口。 */
        data object Reload : Plan
        data class Append(val ids: List<Long>) : Plan
    }

    /**
     * [knownMaxId]：手上已落库的块里最大的服务端 id（没有 = 这个会话手上一条落库的都没有）。
     * [echoes]：手上**没带服务端 id** 的块的（角色, 正文）—— 发出去 / 推过来时还没拿到 id 的那几条；
     * 拉回来的同角色同正文的行就是它们，不再接一遍（不然断线前刚发的那句会出现两次）。
     */
    fun plan(knownMaxId: Long?, rows: List<Row>, echoes: List<Pair<String, String>>, pageSize: Int): Plan {
        if (rows.isEmpty()) return Plan.None
        val max = knownMaxId ?: return Plan.Reload
        val fresh = rows.filter { it.id > max }
        if (fresh.isEmpty()) return Plan.None
        if (fresh.size == rows.size && rows.size >= pageSize) return Plan.Reload
        // 回声逐条认领：两句一样的话各认一次，不是见到一样的就全吞。
        val left = echoes.toMutableList()
        val ids = fresh.sortedBy { it.id }.filter { r ->
            val i = left.indexOf(r.role to r.content)
            if (i >= 0) { left.removeAt(i); false } else true
        }.map { it.id }
        return if (ids.isEmpty()) Plan.None else Plan.Append(ids)
    }
}
