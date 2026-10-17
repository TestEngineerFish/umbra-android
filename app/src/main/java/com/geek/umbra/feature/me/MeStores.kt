package com.geek.umbra.feature.me

import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.Capability
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.isOk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * 执行端设备（`GET /devices/all`）—— iOS 放在 ChatViewModel.devices（ChatViewModel.swift:199-216）。
 * 「我」首页那一行的台数、设备与能力列表、设备详情三处读同一份。
 *
 * 聊天模块的联系人列表在 iOS 也读这一份；Android 聊天包有自己的，合并时可以收成一处（见交回报告）。
 */
object DevicesStore {
    private val _devices = MutableStateFlow<List<KnownDevice>>(emptyList())
    val devices: StateFlow<List<KnownDevice>> = _devices

    /** 拉一次。**拉失败（null）保留旧列表** —— 别把列表清成空的（iOS reloadDevices 同）。 */
    suspend fun reload() {
        val list = AppGraph.http.get("/devices/all", ListSerializer(KnownDevice.serializer())) ?: return
        _devices.value = list
    }

    fun load() { AppGraph.scope.launch { reload() } }

    /**
     * 从联系人列表移除：`DELETE /devices/{id}`，成功才从本地拿掉（iOS forgetDevice）。
     * id 按一个值编码（`/` 也转）—— iOS HTTPService.swift:1168 那条：路径允许集不转义斜杠，DELETE 会打到别的地址。
     */
    fun forget(id: String) {
        AppGraph.scope.launch {
            if (AppGraph.http.call("DELETE", "/devices/${UmbraUrl.value(id)}").isOk) {
                _devices.value = _devices.value.filterNot { it.device_id == id }
            }
        }
    }

    /** 守卫「关」：上一个账号的设备不留在屏上。 */
    fun clear() { _devices.value = emptyList() }
}

/**
 * 能力页的数据（iOS AbilitiesViewModel.swift）。`failed`：最近一次拉取失败 ——
 * 「读不出来」和「没有」分开说（台账 capabilities-failure-shown-empty）；失败时手上那份不清。
 */
object CapsStore {
    val capabilities = MutableStateFlow<List<Capability>>(emptyList())
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)

    suspend fun load() {
        loading.value = true
        val caps = AppGraph.http.get("/capabilities", ListSerializer(Capability.serializer()))
        if (caps != null) capabilities.value = caps
        failed.value = caps == null
        loading.value = false
    }

    fun clear() {
        capabilities.value = emptyList(); failed.value = false
    }
}
