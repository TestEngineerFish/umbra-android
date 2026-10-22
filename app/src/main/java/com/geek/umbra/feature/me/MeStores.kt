package com.geek.umbra.feature.me

import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.Capability
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.UmbraUrl
import com.geek.umbra.net.isOk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * 执行端设备（`GET /devices/all`）—— iOS 放在 ChatViewModel.devices（ChatViewModel.swift:199-216），聊天联系人与「我」共读那一份。
 * Android 也只存这一份：聊天联系人 / 会话标题、「我」首页那一行的台数、设备与能力列表、设备详情都读它。
 * 从前聊天包另存了一份，长连接推来「设备上下线」只刷新聊天那份，「我」里的在线状态要等进页面才跟上（开发文档第五节技术债）。
 */
object DevicesStore {
    private val _devices = MutableStateFlow<List<KnownDevice>>(emptyList())
    val devices: StateFlow<List<KnownDevice>> = _devices

    /** 拉一次。**拉失败（null）保留旧列表** —— 别把列表清成空的（iOS reloadDevices 同）。 */
    suspend fun reload() {
        val list = AppGraph.http.get("/devices/all", ListSerializer(KnownDevice.serializer())) ?: return
        _devices.value = list
    }

    /** background：长连接推来的刷新信号（设备上下线）、守卫「开」连带的请求不是人点的，统一出口不打「人引起的」头。 */
    fun load(background: Boolean = false) {
        AppGraph.scope.launch { if (background) withContext(BackgroundWork) { reload() } else reload() }
    }

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
