package com.geek.umbra.net

import com.geek.umbra.app.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 服务端界面开关 `GET/PUT /settings`（iOS HTTPService.swift:877-889）。
 * 记忆开关、使用记录统计、记账默认值、各用途发图画质都在这一张表里；服务端改了会广播 `settings_updated`（带全量）。
 * `image_tiers` 是各画质档的长边像素 —— **端上不写死**，以这里为准。
 */
object ServerSettings {
    private val _settings = MutableStateFlow<JsonObject?>(null)
    /** null = 还没拉到（与「拉到了但是空表」区分）。 */
    val settings: StateFlow<JsonObject?> = _settings
    private val _tiers = MutableStateFlow<Map<String, Int>>(emptyMap())
    val imageTiers: StateFlow<Map<String, Int>> = _tiers

    fun start() {
        AppGraph.scope.launch {
            AppGraph.socket.frames.collect { f ->
                if (f.str("type") == "settings_updated") (f["settings"] as? JsonObject)?.let { _settings.value = it }
            }
        }
    }

    suspend fun load(): JsonObject? = withContext(BackgroundWork) {
        val o = AppGraph.http.get<JsonObject>("/settings") ?: return@withContext null
        (o["settings"] as? JsonObject)?.let { _settings.value = it }
        (o["image_tiers"] as? JsonObject)?.let { t ->
            _tiers.value = t.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }.toMap()
        }
        _settings.value
    }

    /** `PUT /settings {patch:{…}}`。成功时用回包覆盖本地。 */
    suspend fun put(patch: Map<String, JsonElement>): ApiResult<JsonObject> {
        val r = AppGraph.http.call("PUT", "/settings", buildJsonObject { put("patch", JsonObject(patch)) })
        if (r is ApiResult.Ok) (r.value["settings"] as? JsonObject)?.let { _settings.value = it }
        return r
    }

    fun bool(key: String, default: Boolean): Boolean =
        (_settings.value?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

    fun string(key: String): String? = (_settings.value?.get(key) as? JsonPrimitive)?.contentOrNull

    /** 某用途当前的画质档与长边像素（拿不到 tiers 时 px = null）。 */
    fun tier(purpose: ImagePurpose): Pair<ImageTier, Int?> {
        val t = ImageTier.of(string(purpose.settingKey)) ?: ImageTier.Standard
        val px = if (t == ImageTier.Origin) 0 else _tiers.value[t.wire]
        return t to px
    }
}
