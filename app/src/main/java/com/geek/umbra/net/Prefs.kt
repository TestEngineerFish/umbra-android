package com.geek.umbra.net

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.umbraStore by preferencesDataStore("umbra_prefs")

/** 全工程共用的 JSON 配置：宽松解码 —— 服务端多给字段、少给字段、给 null 都不许整条解码失败（iOS AuthUser 那次事故）。 */
val UmbraJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
}

/**
 * 本机配置 —— 对应 iOS 存在 App Group UserDefaults 里的那批键，键名照搬。
 *
 * DataStore 是异步的，而 HTTP 拦截器、WS 拼地址要同步读。所以启动时阻塞读一次，之后全靠
 * `snapshot`（一个随写入实时更新的 StateFlow）。写入走协程，写完 snapshot 自动跟上。
 */
class Prefs(private val context: Context, private val scope: CoroutineScope) {
    object K {
        val serverUrl = stringPreferencesKey("umbra.serverUrl")
        val clientId = stringPreferencesKey("umbra.clientId")
        val deviceName = stringPreferencesKey("umbra.deviceName")
        val allowDeviceSend = booleanPreferencesKey("umbra.allowDeviceSend")
        val autoApproveOperate = booleanPreferencesKey("umbra.autoApproveOperate")
        val ledger = stringPreferencesKey("umbra.money.ledger")
        val standalone = booleanPreferencesKey("umbra.standalone")
        val signedOut = booleanPreferencesKey("umbra.session.signedOut")
        val appearance = stringPreferencesKey("umbra.appearance")
        val language = stringPreferencesKey("appLanguage")
        val lastSignedOutByUser = booleanPreferencesKey("umbra.session.userSignedOut")
    }

    private val store = context.umbraStore

    val snapshot: StateFlow<Preferences> = runBlocking {
        val first = runCatching { store.data.first() }.getOrDefault(emptyPreferences())
        store.data.stateIn(scope, SharingStarted.Eagerly, first)
    }

    init {
        // 首次启动生成本机 id，之后固定。iOS 是 "ios-" + UUID 前 8 位，这里 "android-"。
        if (snapshot.value[K.clientId].isNullOrEmpty()) {
            val id = "android-" + UUID.randomUUID().toString().take(8)
            runBlocking { store.edit { it[K.clientId] = id } }
        }
    }

    operator fun <T> get(key: Preferences.Key<T>): T? = snapshot.value[key]

    fun <T> set(key: Preferences.Key<T>, value: T?) {
        scope.launch {
            store.edit { if (value == null) it.remove(key) else it[key] = value }
        }
    }

    /** 同步写：少数必须「写完再继续」的地方用（会话守卫先写标记再逐项执行）。 */
    fun <T> setNow(key: Preferences.Key<T>, value: T?) = runBlocking {
        store.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    /**
     * 服务器地址。iOS 界面上是只读的（这一版写死），全仓没有任何地方赋值 —— 这里同。
     * setter 留着给将来的「连接」页用：去首尾空白和末尾 `/`。
     */
    var serverUrl: String
        get() = this[K.serverUrl]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(v) = set(K.serverUrl, v.trim().trimEnd('/'))

    val clientId: String get() = this[K.clientId] ?: "android-unknown"

    val deviceName: String
        get() = this[K.deviceName]?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }

    var allowDeviceSend: Boolean
        get() = this[K.allowDeviceSend] ?: false
        set(v) = set(K.allowDeviceSend, v)

    var autoApproveOperate: Boolean
        get() = this[K.autoApproveOperate] ?: false
        set(v) = set(K.autoApproveOperate, v)

    var standalone: Boolean
        get() = this[K.standalone] ?: false
        set(v) = set(K.standalone, v)

    val signedOut: Boolean get() = this[K.signedOut] ?: false

    companion object {
        const val DEFAULT_SERVER = "https://umbra.tingyusha.xyz"
    }
}
