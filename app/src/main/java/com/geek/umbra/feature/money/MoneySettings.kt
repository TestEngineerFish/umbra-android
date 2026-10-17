// 记账设置的端上镜像 —— 照 iOS Features/Money/MoneySettingsStore.swift，底座是 net/ServerSettings。
//
// **值不存本地**：服务端是唯一权威（这个功能的全部意义就是「两台设备看到同一个值」）。
// 乐观更新 + 回执覆盖：先把界面切过去（开关要跟手），请求失败**不做位置回滚**，重新问一次服务端、以它为准 ——
// 「写成功了但回执丢了」是弱网下最常见的一种，按 before 摆回去反而和服务端不一致。
// 在途的键留住不被广播覆盖：不然开关会当场弹回去、一两秒后又弹回来。
package com.geek.umbra.feature.money

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.ServerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object MoneySettings {
    /** 正在提交的键 → 乐观值。界面要把它画出来（行变灰），不然连点两下的第二下被静默吞掉。 */
    private val _busy = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    val busy: StateFlow<Map<String, JsonElement>> = _busy
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    /** 拉失败了（而且一次都没拉到过）。设置页拿它画重试卡。 */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    /** 服务端那份全量叠上在途的乐观值。null = 还没拉到过（界面画骨架，不先画一个猜的值）。 */
    val values: StateFlow<JsonObject?> = combine(ServerSettings.settings, _busy) { s, b ->
        if (s == null && b.isEmpty()) null else JsonObject((s ?: JsonObject(emptyMap())) + b)
    }.stateIn(AppGraph.scope, SharingStarted.Eagerly, null)

    val loadedOnce: Boolean get() = ServerSettings.settings.value != null

    fun bool(key: String, fallback: Boolean): Boolean =
        (values.value?.get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback

    fun string(key: String, fallback: String): String =
        (values.value?.get(key) as? JsonPrimitive)?.contentOrNull ?: fallback

    /** 这个用途现在是哪一档。**没拉到设置时回 null**（不猜一个）。 */
    fun imageTier(p: ImagePurpose): ImageTier? = ImageTier.of((values.value?.get(p.settingKey) as? JsonPrimitive)?.contentOrNull)

    /** 拉一次。拉失败**不清掉手上那份**。 */
    suspend fun reload() {
        if (_loading.value) return
        _loading.value = true
        val got = ServerSettings.load()
        _loading.value = false
        _failed.value = got == null && !loadedOnce
    }

    /** 进页面 / 用前预热。已经拉到过就不再拉。 */
    fun loadIfNeeded() {
        if (loadedOnce || _loading.value) return
        AppGraph.scope.launch { reload() }
    }

    suspend fun retry() { _failed.value = false; reload() }

    /** 改一个开关。乐观 + 回执覆盖；失败一律重新问一次服务端，再让调用方说一句。 */
    fun put(key: String, value: JsonElement, onFail: (() -> Unit)? = null) {
        if (_busy.value.containsKey(key)) return
        _busy.value = _busy.value + (key to value)
        AppGraph.scope.launch {
            val r = ServerSettings.put(mapOf(key to value))
            _busy.value = _busy.value - key
            if (r is ApiResult.Ok) return@launch
            onFail?.invoke()
            reload()
        }
    }
}
