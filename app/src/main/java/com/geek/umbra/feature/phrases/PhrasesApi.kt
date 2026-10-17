package com.geek.umbra.feature.phrases

/** 其他模块调常用语的入口（桩，由常用语模块实现）。 */
object PhrasesApi {
    /**
     * 聊天长按「存为常用语」：名字取第一行前 12 字（调用方算好传进来）；内容完全相同的已存在就不重复存。
     * 返回 false = 已经有一模一样的。
     */
    fun addFromChat(name: String, content: String): Boolean = false

    /** 工具页卡片：本地条数；从没同步成功过且为空时返回 null（显示「—」）。 */
    fun countForTool(): Int? = null
}
