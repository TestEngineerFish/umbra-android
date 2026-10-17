package com.geek.umbra.feature.knowledge

/** 其他模块调知识库的入口（桩，由知识库模块实现）。 */
object KbApi {
    /** 聊天出处 chip：下载 `/kb/{kb}/raw/<path>` 到缓存后交系统打开（iOS QuickLook）。 */
    fun openRaw(kbId: String, path: String, title: String) {}
}
