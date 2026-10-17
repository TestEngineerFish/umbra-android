package com.geek.umbra.feature.remote

import androidx.compose.runtime.Composable
import com.geek.umbra.model.KnownDevice

/**
 * 远程控制的入口（桩，由远程控制模块实现；iOS Features/Remote/RemoteEntryViews.swift）。
 * mode: "view" | "control"；source: "manual" | "snapshot" | "locate" | "task"。
 */
object RemoteLauncher {
    fun launch(deviceId: String, mode: String, source: String = "manual", runId: String? = null, askId: String? = null) {}
}

/** 设备详情里的「远程」组 + 会话记录 + 本月中继用量。 */
@Composable
fun RemoteDeviceSections(device: KnownDevice) {}

/** 设备会话导航栏右侧的显示器图标菜单（远程查看 / 远程控制 / 看一眼）。 */
@Composable
fun RemoteDeviceMenu(device: KnownDevice) {}

/** 全屏会话层，外壳最上层调一次（iOS fullScreenCover）。没有会话时什么都不画。 */
@Composable
fun RemoteSessionHost() {}
