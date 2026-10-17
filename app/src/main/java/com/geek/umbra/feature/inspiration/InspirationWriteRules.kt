// 灵感增 / 删 / 改 / 改状态 / 交调研之后，屏上说哪句、页面走不走、失败了本地收回到哪
// （iOS: Features/Inspiration/InspirationWriteRules.swift，umbra-ios#60 / #69）。
// 「失败就留页」「失败的吐司不说成功」在屏上**失败时才看得出来**，所以写成判据钉住。纯 Kotlin，配 JUnit 用例。
package com.geek.umbra.feature.inspiration

object InspWriteRules {
    /** 新建 / 编辑页按下保存、回应回来之后的去留与那一句。**失败一律不离开**：页面一走草稿就没了。 */
    data class SaveOutcome(val leave: Boolean, val toast: String)

    const val saveFailed = "没存上，内容还在，稍后再点保存"
    const val deleted = "已移入回收站 · 保留 30 天"
    const val deleteFailed = "没删掉，稍后再试"
    const val statusFailed = "没改成，稍后再试"
    const val researchQueued = "已交给秘书，查完会更新到这里"
    const val researchFailed = "没交给秘书，稍后再试"
    const val deleteGone = "这条已经不在了，可能在别的设备上删掉了"
    const val saveGone = "这条已在别处删除，没存上；内容还在，可以复制下来另记一条"
    const val unchanged = "没有改动"

    /** 保存之后。没填标题的话后台会补 —— 明说一句，免得回到列表看见原文当标题以为存错了。 */
    fun afterSave(ok: Boolean, research: Boolean, titleEmpty: Boolean): SaveOutcome {
        if (!ok) return SaveOutcome(false, saveFailed)
        return SaveOutcome(true, if (research) "已记下，秘书这就去查" else if (titleEmpty) "已记下，秘书稍后补标题" else "已保存")
    }

    data class DeleteOutcome(val leave: Boolean, val toast: String)

    /** 删除之后。deleted = 真正移进回收站的条数，null = 没删成；0 条（别处已删）也离开，但不说「已移入回收站」。 */
    fun afterDelete(deleted: Int?): DeleteOutcome {
        val n = deleted ?: return DeleteOutcome(false, deleteFailed)
        return DeleteOutcome(true, if (n > 0) this.deleted else deleteGone)
    }

    /**
     * DELETE /inspirations/{id} 的回应 → 真正删掉的条数；null = 没删成。2xx 但没报数按 1 条算（删成了只是没报数）；
     * 3xx 是跨源重定向没跟，请求没到删除路由 —— 不算删成。
     */
    fun deletedCount(status: Int, body: String): Int? {
        if (status !in 200..299) return null
        val n = runCatching {
            (com.geek.umbra.net.UmbraJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)
                ?.get("deleted")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        }.getOrNull()
        return n ?: 1
    }

    /** 改状态之后要不要吐司：成功 null（徽标当场就变了），失败说没改成。 */
    fun afterStatus(ok: Boolean): String? = if (ok) null else statusFailed

    fun afterResearch(ok: Boolean): String = if (ok) researchQueued else researchFailed

    /** 「帮我查查」没送达时，乐观标的 queued 收回到**调用前**那个值（不是一律 idle：done 的纪要会整段消失）。 */
    fun researchRollback(before: String?): String? = before

    /** 没送达时列表变成什么：期间刷新过就不回滚（刷新来的就是服务端此刻的真相）。 */
    fun rollingBackResearch(list: List<Inspiration>, id: Long, before: String?, reloadedSince: Boolean): List<Inspiration> {
        if (reloadedSince) return list
        return settingResearch(list, id, researchRollback(before))
    }

    /** 这一趟拉取（发出时领的 issued）回来时还用不用 —— 先发的那趟后回来不许覆盖（late-response-overwrites-list）。 */
    fun shouldApplyLoad(issued: Int, lastApplied: Int): Boolean = issued > lastApplied

    /**
     * 写成功之后先把本地那一条改掉（status-ok-but-reload-failed-silent）：拉取失败时徽标 / 内容也不撒谎。
     * 规整照服务端：title / raw 去首尾空白；raw 空就不动；tags 去空。
     */
    fun applying(list: List<Inspiration>, id: Long, patch: InspPatch): List<Inspiration> {
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return list
        val o = list[idx]
        val raw = patch.raw?.trim()?.takeIf { it.isNotEmpty() } ?: o.raw
        val title = patch.title?.trim() ?: o.title
        val tags = patch.tags?.map { it.trim() }?.filter { it.isNotEmpty() } ?: o.tags
        val status = patch.status ?: o.status
        return list.toMutableList().also { it[idx] = o.copy(raw = raw, title = title, tags = tags, status = status) }
    }

    /** 打开编辑那一刻定格的三项（#69：保存时只上行和它不同的字段）。 */
    data class EditSnapshot(val raw: String, val title: String, val tags: List<String>)

    fun snapshot(i: Inspiration) = EditSnapshot(i.raw, i.title, i.tags)

    /** 标签串 → 数组。全角逗号、半角逗号、顿号都当分隔符 —— 输入法给什么符号是用户控制不了的事。 */
    fun parseTags(text: String): List<String> =
        text.replace("，", ",").replace("、", ",").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 编辑框里显示的标签串（与 parseTags 是一对）。 */
    fun tagsText(tags: List<String>): String = tags.joinToString("，")

    /** 保存时发给服务端的那一份：**只含和快照不同的键**；空 = 什么都没改，不该发请求。比对一律去首尾空白。 */
    fun editPatch(snap: EditSnapshot, raw: String, title: String, tagsText: String): InspPatch {
        val body = raw.trim()
        val name = title.trim()
        val tags = parseTags(tagsText)
        return InspPatch(
            raw = body.takeIf { it != snap.raw.trim() },
            title = name.takeIf { it != snap.title.trim() },
            tags = tags.takeIf { it != parseTags(tagsText(snap.tags)) },
        )
    }

    /** 新建 / 编辑页这一次保存该走哪条路。按**路由**分：带 id 就只可能是改；改不了就说已在别处删除，绝不新建。 */
    sealed interface EditMode {
        data object Create : EditMode
        data class Update(val snapshot: EditSnapshot) : EditMode
        data object Gone : EditMode
    }

    fun editMode(routeId: String?, snapshot: EditSnapshot?): EditMode = when {
        routeId == null -> EditMode.Create
        snapshot == null -> EditMode.Gone
        else -> EditMode.Update(snapshot)
    }

    enum class UpdateResult { Saved, Gone, Failed }

    /** HTTP 状态码 → 结果；null = 没拿到响应。3xx 没到服务端。 */
    fun updateResult(status: Int?): UpdateResult = when {
        status == null -> UpdateResult.Failed
        status in 200..299 -> UpdateResult.Saved
        status == 404 -> UpdateResult.Gone
        else -> UpdateResult.Failed
    }

    /** 编辑保存之后。**只有存上才离开**；Gone 也留页 —— 草稿是人唯一剩下的东西。 */
    fun afterUpdate(r: UpdateResult): SaveOutcome = when (r) {
        UpdateResult.Saved -> SaveOutcome(true, "已保存")
        UpdateResult.Gone -> SaveOutcome(false, saveGone)
        UpdateResult.Failed -> SaveOutcome(false, saveFailed)
    }

    /** 把 id 那一条的 research_status 换掉，其余字段原样；找不到就原样返回，不凭空造。 */
    fun settingResearch(list: List<Inspiration>, id: Long, status: String?): List<Inspiration> {
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return list
        return list.toMutableList().also { it[idx] = it[idx].copy(research_status = status) }
    }
}
