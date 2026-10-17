// 首屏三态（iOS: DesignSystem/UmbraStates.swift:25 `UmbraScreenPhase`）。
// 地基 designsystem 里还没有它（只有骨架 / 错误卡 / 错误条三件视觉基元），任务和灵感两页先在这里用，
// 合并时宜挪进 designsystem（见交回报告）。纯 Kotlin。
package com.geek.umbra.feature.task

/** 这一屏该画什么。 */
sealed interface ScreenPhase {
    data object Skeleton : ScreenPhase
    /** 整屏错误卡；offline 决定第二段说「够不着」还是「够得着但没给数据」。 */
    data class Failed(val offline: Boolean) : ScreenPhase
    /** 有内容可看（含拉完确实是空的）；offline 时顶部挂离线条、内容压 .6。 */
    data class Content(val offline: Boolean) : ScreenPhase

    companion object {
        /**
         * 顺序是事故换来的：有内容永远先给内容（断网时最该顶用的是手上这份）；其次没内容 + 正在拉 → 骨架；
         * 最后没内容 + 不在拉 + 没成功过 → 错误卡。loadedOnce 不能用 isEmpty 代替 —— 真的空列表该画空态。
         * loading 必须是**首屏那一次**（firstLoading），不能传轮询里每轮都翻 true 的那个，否则空账号每轮闪骨架。
         */
        fun resolve(hasContent: Boolean, loading: Boolean, loadedOnce: Boolean, offline: Boolean): ScreenPhase = when {
            hasContent -> Content(offline)
            loading -> Skeleton
            !loadedOnce -> Failed(offline)
            else -> Content(offline)
        }
    }
}
