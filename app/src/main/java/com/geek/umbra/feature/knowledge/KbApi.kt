package com.geek.umbra.feature.knowledge

/** 其他模块调知识库的入口。 */
object KbApi {
    /**
     * 聊天出处 chip：下载 `/kb/{kb}/raw/<path>` 到缓存后交系统打开（iOS QuickLook → Android ACTION_VIEW + FileProvider）。
     * 失败（连不上、文件没了、没有能打开它的 App）吐司一句；正在取另一份时这一下不响应（[KbPreviewer.opening] 挡连点）。
     * [title] 只用于 iOS 预览的标题栏，Android 交给接手的 App 自己显示文件名，这里不用。
     */
    @Suppress("UNUSED_PARAMETER")
    fun openRaw(kbId: String, path: String, title: String) {
        KbPreviewer.open(kbId, path)
    }
}
