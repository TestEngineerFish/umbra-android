// 深链 / 详情落到一条已经删掉的提醒或灵感时的那一页（设计侧批次 290 §五 A）。回收站归保险箱这一区管（同一页两套存储），所以放这里。
package com.geek.umbra.feature.vault

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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

/**
 * 这一页 Android 手写的那几句（批次 290 §五 A）。「可能刚删掉 / 回到提醒」两句和 iOS 逐字一样，读 iOS key（reminder.gone.* / insp.gone.*），不在这里。
 * 留在这里的都是 iOS 没有 key 的：标题（iOS 也是 Brand.nicknamed 拼的字面）、「去回收站」和「超过 30 天」两档（iOS 这一页没有这两档）。
 */
object TrashGoneCopy {
    /** [noun] 中文名词（提醒 / 灵感），[nounEn] 英文单数（reminder / idea）。中文带昵称；英文不用昵称。 */
    fun title(noun: String, nounEn: String, english: Boolean) =
        if (english) "This $nounEn is gone" else "${Brand.nickname}没找到这条$noun"

    /** 超过 30 天那一档（英文是 Android 自拟、geek-99 攒给设计侧认）。 */
    fun expiredHint(english: Boolean) =
        if (english) "It was deleted more than 30 days ago and can't be recovered." else "它被删掉超过 30 天了，找不回来。"

    fun openTrash(english: Boolean) = if (english) "Open Trash" else "去回收站"
}

/**
 * 已删对象那一页：［去回收站］［回到列表］；回收站里也没有（超过 30 天）只留［回到列表］。
 * [kind] 是回收站里的种类（reminder / idea），[pendingLocalDelete] = 本机还有一条没推上去的删除。
 */
@Composable
fun TrashGoneState(
    icon: String, kind: String, id: String, noun: String, nounEn: String,
    @StringRes bodyRes: Int, @StringRes backRes: Int, pendingLocalDelete: Boolean, onBack: () -> Unit,
) {
    var expired by remember(kind, id) { mutableStateOf(false) }
    LaunchedEffect(kind, id) {
        // 没登录（单机）不拉：那时根本没有服务端回收站，照「可能刚删掉」说。
        val trash = if (AppGraph.auth.signedIn) runCatching { VaultStore.fetchTrash() }.getOrNull() else null
        expired = TrashGoneRules.expired(trash, kind, id, pendingLocalDelete)
    }
    val en = UmbraDateText.english
    val back = stringResource(backRes)
    UmbraEmptyState(
        icon, TrashGoneCopy.title(noun, nounEn, en), hint = if (expired) TrashGoneCopy.expiredHint(en) else stringResource(bodyRes),
        action = if (expired) back else TrashGoneCopy.openTrash(en),
        onAction = if (expired) onBack else ({ AppGraph.router.jump(Route.VaultTrash) }),
        secondary = if (expired) null else back, onSecondary = onBack,
    )
}
