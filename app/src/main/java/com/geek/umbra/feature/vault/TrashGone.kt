// 深链 / 详情落到一条已经删掉的提醒或灵感时的那一页（设计侧批次 290 §五 A）。回收站归保险箱这一区管（同一页两套存储），所以放这里。
package com.geek.umbra.feature.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.Brand
import com.geek.umbra.designsystem.UmbraDateText
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.model.TrashList
import com.geek.umbra.navigation.Route

/**
 * 「删掉超过 30 天、回收站里也找不到」的判据。只有三件都确定才说找不回来：回收站这次真拉到了（null = 没拉到 / 没登录，
 * 不知道就照「可能刚删掉」说）、里面没有它、本机也没有一条还没推上去的删除（刚在这台手机上删的，服务端回收站里还没有）。
 * 说错的代价不对称：把找得回来的说成「找不回来」，人就真不去找了。
 */
object TrashGoneRules {
    fun expired(trash: TrashList?, kind: String, id: String, pendingLocalDelete: Boolean): Boolean =
        trash != null && !pendingLocalDelete && trash.items.none { it.kind == kind && it.id == id }
}

/** 这一页的字面（批次 290 §五 A）。中文标题带昵称；英文界面不用昵称，照平实句子。 */
object TrashGoneCopy {
    /** [noun] 中文名词（提醒 / 灵感），[nounEn] 英文单数（reminder / idea）。 */
    fun title(noun: String, nounEn: String, english: Boolean) =
        if (english) "This $nounEn is gone" else "${Brand.nickname}没找到这条$noun"

    fun hint(expired: Boolean, english: Boolean) = when {
        english && expired -> "It was deleted more than 30 days ago and can't be recovered."
        english -> "It may have just been deleted. Deleted items stay in Trash for 30 days."
        expired -> "它被删掉超过 30 天了，找不回来。"
        else -> "它可能刚被删掉了，删掉的会在回收站留 30 天。"
    }

    fun openTrash(english: Boolean) = if (english) "Open Trash" else "去回收站"
    fun back(noun: String, nounEn: String, english: Boolean) = if (english) "Back to ${nounEn}s" else "回到$noun"
}

/**
 * 已删对象那一页：［去回收站］［回到列表］；回收站里也没有（超过 30 天）只留［回到列表］。
 * [kind] 是回收站里的种类（reminder / idea），[pendingLocalDelete] = 本机还有一条没推上去的删除。
 */
@Composable
fun TrashGoneState(icon: String, kind: String, id: String, noun: String, nounEn: String, pendingLocalDelete: Boolean, onBack: () -> Unit) {
    var expired by remember(kind, id) { mutableStateOf(false) }
    LaunchedEffect(kind, id) {
        // 没登录（单机）不拉：那时根本没有服务端回收站，照「可能刚删掉」说。
        val trash = if (AppGraph.auth.signedIn) runCatching { VaultStore.fetchTrash() }.getOrNull() else null
        expired = TrashGoneRules.expired(trash, kind, id, pendingLocalDelete)
    }
    val en = UmbraDateText.english
    UmbraEmptyState(
        icon, TrashGoneCopy.title(noun, nounEn, en), hint = TrashGoneCopy.hint(expired, en),
        action = if (expired) TrashGoneCopy.back(noun, nounEn, en) else TrashGoneCopy.openTrash(en),
        onAction = if (expired) onBack else ({ AppGraph.router.jump(Route.VaultTrash) }),
        secondary = if (expired) null else TrashGoneCopy.back(noun, nounEn, en), onSecondary = onBack,
    )
}
