package com.geek.umbra.feature.remote

/**
 * 远程会话页要、而 iOS 图标表（UmbraIconPaths）里没有的几颗 —— iOS 那边用的是 SF Symbols（cursorarrow、dial、
 * doc.on.clipboard…），设计侧没给 path。这里取同形的 Lucide 描边图（24 viewBox、圆头，和 UmbraIcon 同一套画法）。
 * 放在模块里而不是 designsystem/UmbraExtraIcons：那个文件归地基所有，移植期不改它；等别的模块也要时再挪过去。
 */
object RemoteIcons {
    /** Lucide mouse-pointer：虚拟光标、「申请控制」。 */
    const val pointer = "M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3zM13 13l6 6"
    /** Lucide pointer（手指）：触摸模式。 */
    const val hand = "M22 14a8 8 0 0 1-8 8M18 11v-1a2 2 0 0 0-2-2a2 2 0 0 0-2 2M14 10V9a2 2 0 0 0-2-2a2 2 0 0 0-2 2v1M10 9.5V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v10M18 11a2 2 0 1 1 4 0v3a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"
    /** Lucide mouse：触控板模式。 */
    const val mouse = "M12 2a7 7 0 0 1 7 7v6a7 7 0 0 1-14 0V9a7 7 0 0 1 7-7zM12 6v4"
    /** Lucide gauge：画质。 */
    const val gauge = "M12 14l4-4M3.34 19a10 10 0 1 1 17.32 0"
    /** Lucide clipboard：剪贴板。 */
    const val clipboard = "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"
    /** Lucide refresh-cw：刷新快照。 */
    const val refresh = "M3 12a9 9 0 0 1 9-9a9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9a9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5"
    /** Lucide minimize-2：恢复原大小。 */
    const val fit = "M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7"
    /** Lucide hourglass：升档等对方允许中。 */
    const val hourglass = "M5 22h14M5 2h14M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2"
    /** Lucide arrow-down / arrow-up：传输方向。 */
    const val arrowDown = "M12 5v14M19 12l-7 7-7-7"
    const val arrowUp = "M12 19V5M5 12l7-7 7 7"
    /** Lucide arrow-down-circle：拉回一个文件。 */
    const val download = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 8v8M8 12l4 4 4-4"
    /** Lucide upload：推一个文件过去。 */
    const val upload = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"
    /** Lucide keyboard-off 的简化：键盘开着时那颗钮画「收起」。 */
    const val keyboardHide = "M4 4h16a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 8h.01M12 8h.01M16 8h.01M8 11h8M9 18l3 3 3-3"
    /** Lucide scan（取景框）：看一眼。 */
    const val viewfinder = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"
    /** Lucide monitor-x：已结束。 */
    const val monitorOff = "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM8 21h8M12 17v4M9.5 7.5l5 5M14.5 7.5l-5 5"
    /** Lucide delete（左指的退格键）：无人值守页数字键盘的 ⌫（iOS SF Symbol delete.left）。 */
    const val deleteLeft = "M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zM12 9l6 6M18 9l-6 6"
    /** Lucide shield + 锁孔：无人值守页 PIN / 核对短码那两步（iOS SF Symbol lock.shield）。 */
    const val lockShield = "M12 2L4 5.5v6c0 5 3.4 8.3 8 10.5c4.6-2.2 8-5.5 8-10.5v-6L12 2zM10 11V9.5a2 2 0 0 1 4 0V11M9.5 11h5v4h-5z"
    /** Lucide arrow-down-to-line：文件行行尾的下载钮（iOS SF Symbol arrow.down.to.line）。 */
    const val downloadLine = "M12 17V3M6 11l6 6 6-6M19 21H5"
}
