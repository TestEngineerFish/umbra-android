// 握手补发的卡（授权卡 / 问答卡 / 指位卡）怎么和本地已有的那张合并（iOS: Features/Chat/ChatCardResend.swift）。
//
// 纯判据，不 import android.*，用例在 app/src/test/.../ChatCardResendTest.kt（照抄 iOS ChatCardResendTests.swift）。
//
// 坑是这么踩的：授权 / 问答 / 指位这几个动作原来都是**先改本地、不看发没发出去** ——
// 离线时点「批准」，帧没出门，卡却变成「已批准，执行中…」。服务端重连握手时会把还挂着的卡补发一遍，
// 而端上的去重只看「这张卡在不在」，补发的那张被吞掉，本地那张停在「已处理」，任务一直挂到超时。
// 两道闸分工：点击那一侧（ChatStore）发不出去就不改卡；这里：补发到了 = 服务端没收到我们的回答，撤回本地的「已处理」。
package com.geek.umbra.feature.chat

object ChatCardResend {
    /** 本地那张卡现在的样子。 */
    enum class Local {
        /** 本地没有这张卡（第一次到）。 */
        Absent,
        /** 有，而且还没处理 —— 按钮都在。 */
        Open,
        /** 有，本地已标成处理过，而处理那一帧是在**更早的一条连接**上发的（或者根本不是本端发的）。 */
        Handled,
        /**
         * 有，本地已标成处理过，而处理那一帧就是在**收到这张补发的同一条连接**上发出去的。
         * 服务端握手时先补发完挂着的卡、再进收帧循环 —— 同一条连接上的补发说明不了「我们那一帧没到」
         * （iOS 台账 2026-10-05 `chat-card-reopen-false-positive`）。
         */
        HandledOnThisConnection,
    }

    /** 收到一张（可能是补发的）卡之后该怎么办。 */
    enum class Merge { Append, Reopen, Keep }

    /**
     * 判据本体（iOS: ChatCardResend.swift:57）。
     * ⚠️ 别把 Handled 判成 Keep：补发只会发给服务端还在等的卡，所以「补发到了却是已处理」只有一种解释 —— 我们那一帧没到。
     */
    fun merge(local: Local): Merge = when (local) {
        Local.Absent -> Merge.Append
        Local.Open -> Merge.Keep
        Local.Handled -> Merge.Reopen
        Local.HandledOnThisConnection -> Merge.Keep
    }
}
