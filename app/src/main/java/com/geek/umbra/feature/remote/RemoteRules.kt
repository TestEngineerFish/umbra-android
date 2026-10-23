// 远程控制（Android 与 iOS 一样只做控制端）的纯判据 —— 逐段照搬 iOS `Features/Remote/RemoteRules.swift`。
// 契约是需求文档 `doc/远程控制(Remote Control)-需求文档.md` 的 **§十四**，服务端那一半在
// `Umbra/private_ai_assistant/remote_control.py`（字段名照它一个字不差）。
//
// ⚠️ 为什么单独一个文件、且不 import android.*：`app/src/test/.../RemoteRulesTest.kt` 在 JVM 上直接跑它，
// 用例照抄 iOS `Tools/swifttests/RemoteRulesTests.swift`。这里装的全是「错了不报错、只是点歪 / 说错话 / 连不上」那一类：
//   · 触摸点 → 视频帧 0–1 坐标（aspectFit 的黑边、本地捏合缩放平移）—— 算错了不崩，只是点在别处；
//   · 触控板模式的相对移动与加速、手势 → 输入消息、修饰键锁定、ASCII 键位表；
//   · 安全短码（§14.4）—— 两端各算一遍，算法差一点两端就对不上，而且「对不上」正是它要人去警觉的信号；
//   · 快照降级判定（§14.5）、结束原因 / rc_error / 提示码 → 文案 key；
//   · 信令只认自己的 session_id（服务端先推 `rc_state{awaiting_consent}` 再回 `rc_created`，见 [RemoteSignalGate]）；
//   · DataChannel 两条通道的解析与 `file` 通道的分块编解码。
// WebRTC 本身（`org.webrtc.PeerConnection`）只在 `RemoteClient.kt` 里碰。
package com.geek.umbra.feature.remote

import com.geek.umbra.net.UmbraUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// MARK: - 几何

/** 尺寸 / 点用自己的两个小结构（iOS: RemoteRules.swift:21）：不拖进 android.graphics，用例在纯 JVM 上跑。 */
data class RemoteSize(val w: Double, val h: Double)

data class RemotePoint(val x: Double, val y: Double)

/** aspectFit 之后帧在视图里占的矩形。 */
data class RemoteFitRect(val origin: RemotePoint, val size: RemoteSize)

/** Swift `.rounded()` 是「四舍五入、半数远离零」；Kotlin 的 `round` 是银行家舍入 —— 坐标 / 滚轮量要逐位一致就自己写。 */
internal fun roundHalfAway(v: Double): Double = if (v >= 0) floor(v + 0.5) else -floor(-v + 0.5)

/**
 * 视图坐标 ↔ 视频帧 0–1 坐标（iOS: RemoteRules.swift:37）。
 *
 * 画面是 aspectFit 进视图的：比例和视图不同就有黑边（竖屏看 16:10 的 Mac 屏，上下各一大条）。
 * 本地捏合只缩放控制端的画面（RC-4.5：「只缩控制端画面，不发给被控端」），在 fit 之后再叠一层：
 * `显示点 = 视图中心 + (fit 后的点 − 视图中心) × zoom + pan`。
 * 反算时三层都要逆回去，**漏掉黑边那一层点哪儿都偏，漏掉缩放那一层放大后才偏** —— 两种都不报错。
 *
 * Android 侧调用方一律传 **dp**（不是 px）：几个阈值（拖拽 8、跟随光标留边 40）是按 iOS 的 pt 定的，dp 与 pt 同尺度。
 */
object RemoteGeometry {
    /** 捏合缩放的上下限：1 = 刚好铺满（不许缩到比 fit 还小）；5 倍够点清 Retina 屏上的小图标。 */
    const val MIN_ZOOM = 1.0
    const val MAX_ZOOM = 5.0

    /** 帧在视图里 aspectFit 之后占的矩形。帧尺寸未知（还没出第一帧）时按视图本身算，避免除零。 */
    fun fitRect(view: RemoteSize, frame: RemoteSize): RemoteFitRect {
        if (!(frame.w > 0 && frame.h > 0 && view.w > 0 && view.h > 0)) return RemoteFitRect(RemotePoint(0.0, 0.0), view)
        val s = min(view.w / frame.w, view.h / frame.h)
        val size = RemoteSize(frame.w * s, frame.h * s)
        return RemoteFitRect(RemotePoint((view.w - size.w) / 2, (view.h - size.h) / 2), size)
    }

    /** 视图里的一个触摸点 → 帧 0–1 坐标。落在黑边里回 null（那一下不该被当成点在屏幕边上）。 */
    fun toFrame(p: RemotePoint, view: RemoteSize, frame: RemoteSize, zoom: Double = 1.0, pan: RemotePoint = RemotePoint(0.0, 0.0)): RemotePoint? {
        val fit = fitRect(view, frame)
        val z = clampZoom(zoom)
        val cx = view.w / 2; val cy = view.h / 2
        // 先逆缩放 / 平移，回到「fit 之后、没缩放」的视图坐标。
        val ux = cx + (p.x - cx - pan.x) / z
        val uy = cy + (p.y - cy - pan.y) / z
        if (!(fit.size.w > 0 && fit.size.h > 0)) return null
        val nx = (ux - fit.origin.x) / fit.size.w
        val ny = (uy - fit.origin.y) / fit.size.h
        // 容差：手指压在帧的最边上时浮点误差会给出 1.0000001，不该因此判成黑边。
        val eps = 1e-6
        if (!(nx >= -eps && nx <= 1 + eps && ny >= -eps && ny <= 1 + eps)) return null
        return RemotePoint(clamp01(nx), clamp01(ny))
    }

    /** [toFrame] 的反函数：帧 0–1 坐标 → 视图坐标（画触控板模式的虚拟光标用）。 */
    fun toView(n: RemotePoint, view: RemoteSize, frame: RemoteSize, zoom: Double = 1.0, pan: RemotePoint = RemotePoint(0.0, 0.0)): RemotePoint {
        val fit = fitRect(view, frame)
        val z = clampZoom(zoom)
        val cx = view.w / 2; val cy = view.h / 2
        val ux = fit.origin.x + n.x * fit.size.w
        val uy = fit.origin.y + n.y * fit.size.h
        return RemotePoint(cx + (ux - cx) * z + pan.x, cy + (uy - cy) * z + pan.y)
    }

    /**
     * 平移夹紧：放大后最多拖到画面边缘贴着视图边缘，不许把画面整个拖出去（拖出去了人找不回来）。
     * 在 fit 尺寸小于视图的那一维（黑边那一维），放大到还没超过视图之前不许平移 —— 那一维本来就居中。
     */
    fun clampPan(pan: RemotePoint, view: RemoteSize, frame: RemoteSize, zoom: Double): RemotePoint {
        val fit = fitRect(view, frame)
        val z = clampZoom(zoom)
        // 不到千分之一的超出量当 0：浮点上会剩 1e-14，不归零的话「1 倍时不许平移」就漏一条缝。
        fun room(v: Double) = if (v > 1e-3) v else 0.0
        val maxX = room((fit.size.w * z - view.w) / 2)
        val maxY = room((fit.size.h * z - view.h) / 2)
        return RemotePoint(min(max(pan.x, -maxX), maxX), min(max(pan.y, -maxY), maxY))
    }

    /** 以捏合中心 [anchor] 为不动点从 [zoom] 缩放到 [newZoom] 之后的平移量（再经 [clampPan]）。不以手指为锚点，放大时手指底下的东西会跑走。 */
    fun panAfterZooming(pan: RemotePoint, zoom: Double, newZoom: Double, anchor: RemotePoint, view: RemoteSize, frame: RemoteSize): RemotePoint {
        val z0 = clampZoom(zoom); val z1 = clampZoom(newZoom)
        val cx = view.w / 2; val cy = view.h / 2
        // anchor 底下那个「未缩放点」u：anchor = c + (u − c)·z0 + pan → u − c = (anchor − c − pan)/z0
        val ux = (anchor.x - cx - pan.x) / z0
        val uy = (anchor.y - cy - pan.y) / z0
        val p = RemotePoint(anchor.x - cx - ux * z1, anchor.y - cy - uy * z1)
        return clampPan(p, view, frame, z1)
    }

    fun clampZoom(z: Double): Double = min(max(if (z.isFinite()) z else 1.0, MIN_ZOOM), MAX_ZOOM)

    fun clamp01(v: Double): Double = min(max(v, 0.0), 1.0)

    /** 视图里 1 个单位等于多少个帧像素（双指滚动换算成滚轮量用）。缩放之后同样一段手指距离对应的屏幕内容更少。 */
    fun framePixelsPerPoint(view: RemoteSize, frame: RemoteSize, zoom: Double): Double {
        val fit = fitRect(view, frame)
        if (!(fit.size.w > 0 && frame.w > 0)) return 1.0
        return frame.w / (fit.size.w * clampZoom(zoom))
    }
}

// MARK: - 触控板模式

/**
 * 触控板模式（RC-4.5，iOS: RemoteRules.swift:124）：手指相对移动光标，适合点小目标。光标记在帧 0–1 坐标里。
 * 加速：慢慢挪 1 倍（精确），甩得快最多 3 倍 —— 不加速的话在手机那么小的面上横穿一块 27 寸屏要搓好几下。
 */
class RemoteTrackpad(var cursor: RemotePoint = RemotePoint(0.5, 0.5)) {
    companion object {
        /** 低于这个速度不加速（dp/s，iOS pt/s）。 */
        const val SLOW_SPEED = 150.0
        /** 到这个速度加满。 */
        const val FAST_SPEED = 1200.0
        const val MAX_GAIN = 3.0

        /** 速度 → 倍率（1…MAX_GAIN，中间线性）。 */
        fun gain(speed: Double): Double {
            if (!(speed.isFinite() && speed > SLOW_SPEED)) return 1.0
            val t = min((speed - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED), 1.0)
            return 1 + t * (MAX_GAIN - 1)
        }
    }

    /** 手指挪了 dx, dy，用时 dt 秒。[displayed] 是画面此刻显示出来的尺寸（fit × 缩放）：放大后同样的手指距离挪得更少。 */
    fun move(dx: Double, dy: Double, dt: Double, displayed: RemoteSize) {
        if (!(displayed.w > 0 && displayed.h > 0)) return
        val dist = sqrt(dx * dx + dy * dy)
        val g = gain(if (dt > 0) dist / dt else 0.0)
        cursor = RemotePoint(
            RemoteGeometry.clamp01(cursor.x + dx * g / displayed.w),
            RemoteGeometry.clamp01(cursor.y + dy * g / displayed.h),
        )
    }
}

// MARK: - 输入消息

/** 鼠标键：0 左 / 1 中 / 2 右（与 W3C `MouseEvent.button` 同值，契约 §14.3）。 */
enum class RemoteButton(val raw: Int) { Left(0), Middle(1), Right(2) }

/** 发往被控端 `rc` 通道的一条输入（契约 §14.3，iOS: RemoteRules.swift:159）。坐标都是**当前视频帧的 0–1**。 */
sealed interface RemoteInput {
    data class Move(val p: RemotePoint) : RemoteInput
    data class Down(val b: RemoteButton, val p: RemotePoint) : RemoteInput
    data class Up(val b: RemoteButton, val p: RemotePoint) : RemoteInput
    data class Click(val p: RemotePoint, val b: RemoteButton, val count: Int) : RemoteInput
    data class Wheel(val dx: Int, val dy: Int) : RemoteInput
    data class Key(val code: String, val down: Boolean, val mods: List<String>) : RemoteInput
    data class Text(val s: String) : RemoteInput

    /** 指针类输入（move 也算）。被控端的黑名单闸是被控端的事，控制端只关心「只看档里不该发」。 */
    val isPointer: Boolean get() = this !is Key && this !is Text

    /** 线上的形状。坐标保留 4 位小数：0.0001 × 5K 屏 ≈ 半个像素，再多是白白多发字节。wheel / key / text 不带 display。 */
    fun json(display: String): JsonObject {
        fun r(v: Double) = roundHalfAway(v * 10_000) / 10_000
        return when (this) {
            is Move -> buildJsonObject { put("t", "move"); put("x", r(p.x)); put("y", r(p.y)); put("display", display) }
            is Down -> buildJsonObject { put("t", "down"); put("b", b.raw); put("x", r(p.x)); put("y", r(p.y)); put("display", display) }
            is Up -> buildJsonObject { put("t", "up"); put("b", b.raw); put("x", r(p.x)); put("y", r(p.y)); put("display", display) }
            is Click -> buildJsonObject {
                put("t", "click"); put("x", r(p.x)); put("y", r(p.y)); put("b", b.raw); put("n", count); put("display", display)
            }
            is Wheel -> buildJsonObject { put("t", "wheel"); put("dx", dx); put("dy", dy) }
            is Key -> buildJsonObject {
                put("t", "key"); put("code", code); put("down", down)
                put("mods", buildJsonArray { mods.forEach { add(JsonPrimitive(it)) } })
            }
            is Text -> buildJsonObject { put("t", "text"); put("s", s) }
        }
    }
}

/** 鼠标移动按 60 Hz 合并（中继时每一条都算流量）。拖拽收尾那一下调用方用 force 补发，免得最后停的位置被节流吃掉。 */
class RemoteMoveThrottle {
    companion object { const val INTERVAL = 1.0 / 60 }
    var last: Double = Double.NEGATIVE_INFINITY
        private set

    fun allow(now: Double, force: Boolean = false): Boolean {
        if (force || now - last >= INTERVAL) { last = now; return true }
        return false
    }
}

/**
 * 手势 → 输入（RC-4.5，iOS: RemoteRules.swift:221）。
 * 双击 = 左键 n=2（被控端按系统双击间隔合成，控制端不自己发两次单击 —— 中间隔着网络抖动，到那边可能已超出双击间隔）。
 * 双指拖 = 滚轮：手指往上推 = 往下滚（dy 为正），和触屏「自然滚动」一致。
 */
object RemoteGestureMap {
    fun wheel(translationDx: Double, translationDy: Double, framePxPerPt: Double): RemoteInput? {
        val dx = roundHalfAway(-translationDx * framePxPerPt).toInt()
        val dy = roundHalfAway(-translationDy * framePxPerPt).toInt()
        if (dx == 0 && dy == 0) return null
        return RemoteInput.Wheel(dx, dy)
    }

    fun tap(p: RemotePoint, count: Int = 1): List<RemoteInput> = listOf(RemoteInput.Click(p, RemoteButton.Left, max(1, min(count, 3))))

    fun secondaryTap(p: RemotePoint): List<RemoteInput> = listOf(RemoteInput.Click(p, RemoteButton.Right, 1))
}

/**
 * 长按之后的那一段：松手没动 = 右键；动了 = 拖拽（iOS: RemoteRules.swift:241）。单独做成状态机是因为它跨三次手势回调，
 * 判错的样子是「想拖一个窗口结果弹出右键菜单」或者反过来，都不报错。
 */
class RemoteLongPressDrag {
    companion object {
        /** 手指挪过多少 dp 才算拖（小于这个算手抖）。 */
        const val SLOP = 8.0
    }

    var origin: RemotePoint? = null
        private set
    var dragging = false
        private set
    private var originView = RemotePoint(0.0, 0.0)

    /** 长按成立：记下帧坐标与视图坐标（判「动没动」按视图距离 —— 缩放后帧坐标的一点点可能是手指的一大段）。 */
    fun began(frame: RemotePoint, view: RemotePoint) {
        origin = frame; originView = view; dragging = false
    }

    /** 手指在挪。第一次越过 slop 时先补一个「按下」（发在长按成立的点）再跟着移动。 */
    fun moved(frame: RemotePoint, view: RemotePoint): List<RemoteInput> {
        val o = origin ?: return emptyList()
        if (!dragging) {
            val dx = view.x - originView.x; val dy = view.y - originView.y
            if (sqrt(dx * dx + dy * dy) < SLOP) return emptyList()
            dragging = true
            return listOf(RemoteInput.Down(RemoteButton.Left, o), RemoteInput.Move(frame))
        }
        return listOf(RemoteInput.Move(frame))
    }

    /** 松手。拖过 → 抬起在最后的位置；没拖 → 右键点在长按的点。 */
    fun ended(frame: RemotePoint?): List<RemoteInput> {
        val o = origin
        val wasDragging = dragging
        origin = null; dragging = false
        if (o == null) return emptyList()
        if (wasDragging) return listOf(RemoteInput.Up(RemoteButton.Left, frame ?: o))
        return listOf(RemoteInput.Click(o, RemoteButton.Right, 1))
    }

    /** 手势被系统取消（来电、切后台）：拖着的要补一个抬起，不然被控端那边鼠标一直按着。 */
    fun cancelled(): List<RemoteInput> {
        val o = origin
        val wasDragging = dragging
        origin = null; dragging = false
        if (o == null || !wasDragging) return emptyList()
        return listOf(RemoteInput.Up(RemoteButton.Left, o))
    }
}

// MARK: - 修饰键与键位

/**
 * 修饰键栏（RC-4.6，iOS: RemoteRules.swift:288）：点一下 = 只管下一个键，再点一下 = 锁定，第三下 = 松开。
 * 和系统键盘 Shift 同一个三态 —— 人已经会用。
 */
class RemoteModifiers {
    enum class Level { Off, Once, Locked }

    companion object {
        /** 线上 `mods` 的固定顺序（契约 §14.3）。被控端不在乎顺序，用例在乎。 */
        val ORDER = listOf("cmd", "alt", "ctrl", "shift")
    }

    private val levels = mutableMapOf<String, Level>()

    fun level(m: String): Level = levels[m] ?: Level.Off

    fun tap(m: String) {
        if (m !in ORDER) return
        levels[m] = when (level(m)) {
            Level.Off -> Level.Once
            Level.Once -> Level.Locked
            Level.Locked -> Level.Off
        }
    }

    /** 此刻按着的（once + locked），按固定顺序。 */
    val active: List<String> get() = ORDER.filter { level(it) != Level.Off }

    /** 发完一个键之后：只管一次的松开，锁定的留着。 */
    fun consumeOnce() {
        for ((k, v) in levels.toMap()) if (v == Level.Once) levels[k] = Level.Off
    }

    fun copy(): RemoteModifiers = RemoteModifiers().also { it.levels.putAll(levels) }
}

/** 字符的键位结果：W3C code + 要不要 Shift。 */
data class RemoteKeyCode(val code: String, val shift: Boolean)

/**
 * 字符 → W3C `KeyboardEvent.code`（契约 §14.3：被控端按 code 合成，与键盘布局无关；iOS: RemoteRules.swift:318）。
 * 只认美式布局能直接打出来的 ASCII；别的字符（中文、全角标点、emoji）走 `{t:"text"}`。
 */
object RemoteKeys {
    /** 修饰键栏上那几颗非字符键。「Del」是 Mac 键盘上的 delete，也就是 W3C 的 `Backspace`。 */
    val special: Map<String, String> = mapOf(
        "esc" to "Escape", "tab" to "Tab", "left" to "ArrowLeft", "up" to "ArrowUp", "down" to "ArrowDown",
        "right" to "ArrowRight", "del" to "Backspace", "enter" to "Enter", "space" to "Space",
    )

    /** 不按 Shift 就能打出来的符号。 */
    private val plainSymbols: Map<Char, String> = mapOf(
        ' ' to "Space", '-' to "Minus", '=' to "Equal", '[' to "BracketLeft", ']' to "BracketRight", '\\' to "Backslash",
        ';' to "Semicolon", '\'' to "Quote", ',' to "Comma", '.' to "Period", '/' to "Slash", '`' to "Backquote",
        '\n' to "Enter", '\r' to "Enter", '\t' to "Tab",
    )

    /** 美式布局上要按 Shift 的符号 → 那颗键。 */
    private val shiftedSymbols: Map<Char, String> = mapOf(
        '!' to "Digit1", '@' to "Digit2", '#' to "Digit3", '$' to "Digit4", '%' to "Digit5", '^' to "Digit6",
        '&' to "Digit7", '*' to "Digit8", '(' to "Digit9", ')' to "Digit0", '_' to "Minus", '+' to "Equal",
        '{' to "BracketLeft", '}' to "BracketRight", '|' to "Backslash", ':' to "Semicolon", '"' to "Quote",
        '<' to "Comma", '>' to "Period", '?' to "Slash", '~' to "Backquote",
    )

    /** 一个字符（一个码点）的键与要不要 Shift；不是美式布局 ASCII 的回 null（走 text）。 */
    fun code(c: String): RemoteKeyCode? {
        if (c.length != 1) return null
        val ch = c[0]
        if (ch.code > 0x7F) return null
        return when (ch) {
            in 'a'..'z' -> RemoteKeyCode("Key" + ch.uppercaseChar(), false)
            in 'A'..'Z' -> RemoteKeyCode("Key$ch", true)
            in '0'..'9' -> RemoteKeyCode("Digit$ch", false)
            else -> plainSymbols[ch]?.let { RemoteKeyCode(it, false) } ?: shiftedSymbols[ch]?.let { RemoteKeyCode(it, true) }
        }
    }

    /**
     * Electron accelerator → 给人看的写法（iOS: RemoteRules.swift:354 `accelText`，照 PC `components/hotkey.ts › displayAccel`）：
     * Mac 修饰键按 ⌃⌥⇧⌘ 排、符号连写（⌃⌥⌘R）；其余平台 Win+Ctrl+Alt+Shift 排、加号连。
     * 结束卡「对方按了 X，会话结束。」的 X 就是它 —— 照被控端那台机器的键盘画，不照这台手机。
     */
    fun accelText(acc: String, mac: Boolean): String {
        val alias = mapOf(
            "command" to "Command", "cmd" to "Command", "meta" to "Command", "super" to "Command",
            "commandorcontrol" to "Command", "cmdorctrl" to "Command",
            "control" to "Control", "ctrl" to "Control",
            "alt" to "Alt", "option" to "Alt", "opt" to "Alt", "shift" to "Shift",
        )
        val mods = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (raw in acc.split("+")) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val m = alias[part.lowercase()]
            if (m != null) { if (m !in mods) mods += m } else rest += part
        }
        val order = if (mac) listOf("Control", "Alt", "Shift", "Command") else listOf("Command", "Control", "Alt", "Shift")
        val table = if (mac) mapOf("Command" to "⌘", "Control" to "⌃", "Alt" to "⌥", "Shift" to "⇧")
        else mapOf("Command" to "Win", "Control" to "Ctrl", "Alt" to "Alt", "Shift" to "Shift")
        val macKey = mapOf(
            "Return" to "↩", "Escape" to "⎋", "Tab" to "⇥", "Backspace" to "⌫", "Delete" to "⌦",
            "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
        )
        val head = order.filter { it in mods }.mapNotNull { table[it] }
        val tail = rest.map { if (mac) macKey[it] ?: it else it }
        return if (mac) (head + tail).joinToString("") else (head + tail).joinToString("+")
    }

    /** 一个键「按下 + 抬起」。修饰键跟着这一次一起带。 */
    fun press(code: String, mods: List<String>): List<RemoteInput> =
        listOf(RemoteInput.Key(code, true, mods), RemoteInput.Key(code, false, mods))

    /**
     * 系统键盘吐出来的一段字 → 输入（RC-4.6）：ASCII 逐键走 key；连续的非 ASCII 合成一条 text（被控端粘贴，一次比逐字快）。
     * 修饰键按着时 ASCII 要带上它们（⌘C 就是这么打出来的）；字符本身要 Shift 的再补一个 shift（不重复）。
     * 按码点切（不按 UTF-16 单元）：emoji 的代理对不能被拆成两半。
     */
    fun inputs(typed: String, mods: List<String>): List<RemoteInput> {
        val out = mutableListOf<RemoteInput>()
        val pending = StringBuilder()
        fun flush() { if (pending.isNotEmpty()) { out += RemoteInput.Text(pending.toString()); pending.clear() } }
        var i = 0
        while (i < typed.length) {
            val cp = typed.codePointAt(i)
            val s = String(Character.toChars(cp))
            i += Character.charCount(cp)
            val k = code(s)
            if (k != null) {
                flush()
                val m = mods.toMutableList()
                if (k.shift && "shift" !in m) m += "shift"
                out += press(k.code, RemoteModifiers.ORDER.filter { it in m })
            } else pending.append(s)
        }
        flush()
        return out
    }
}

// MARK: - 安全短码（§14.4）

/**
 * 双方从本地 + 对端 SDP 的 `a=fingerprint` 各算一遍、各自显示，人眼核对两边的四位数一样（iOS: RemoteRules.swift:390）。
 *
 * ⚠️ 两端必须**逐字同一种算法**：
 *   1. 指纹 = SDP 里第一行 `a=fingerprint:` 之后的整段，去首尾空白，**算法名小写、十六进制大写**；
 *   2. 两个指纹按 UTF-8 字节序排序后用 `|` 连起来做 SHA-256，取前 4 字节按**大端**转无符号整数，mod 10000，补齐 4 位。
 */
object RemoteSafetyCode {
    fun fingerprint(sdp: String): String? {
        // 按 \r 和 \n 都切：SDP 行尾是 CRLF（iOS 那边第一版就是因为只按一种换行切，整份 SDP 被当成一行）。
        for (raw in sdp.split('\r', '\n')) {
            val line = raw.trim(' ', '\t')
            if (!line.startsWith("a=fingerprint:")) continue
            val value = line.removePrefix("a=fingerprint:").trim(' ', '\t')
            val parts = value.split(" ", limit = 2)
            if (parts.size != 2 || parts[1].isEmpty()) return null
            return parts[0].lowercase() + " " + parts[1].trim(' ', '\t').uppercase()
        }
        return null
    }

    /** 无符号字节序比较（Kotlin 的 Byte 有符号，直接比会把 ≥0x80 的排到前面）。 */
    private fun utf8Compare(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until min(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    fun code(local: String, remote: String): String {
        val joined = listOf(local, remote).sortedWith(::utf8Compare).joinToString("|")
        val d = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return "%04d".format(n % 10_000)
    }

    /** 从两份 SDP 直接算；任一份没有指纹回 null（不显示短码，而不是显示一个假的）。 */
    fun codeFromSdp(localSdp: String, remoteSdp: String): String? {
        val l = fingerprint(localSdp) ?: return null
        val r = fingerprint(remoteSdp) ?: return null
        return code(l, r)
    }
}

// MARK: - 无人值守的顺序（§14.6 + S13，refs umbra-ios#95）

/**
 * 无人值守三道的顺序与「这台核过没有」。**先核短码、后给 PIN**（geek-99 10-22 定的修法）：
 * 短码防的是信令被攻破后的中间人（S13）—— 它要在 PIN 交出去**之前**确认 DataChannel 真连到了自己的电脑。
 * 原来是 face → pin → code：PIN 以明文命令先到了中间人手里，核对发生在泄密之后。
 *
 * 「核过」不再是一个布尔值（原 `umbra.remote.codeChecked.<deviceId>`，已废）：短码每次会话都变，只记「核过」等于只保护第一次。
 * 现在按 deviceId 记住**电脑那一侧的 DTLS 指纹**（PC 持久化证书后它跨会话不变），每次会话比对，对不上就重新核短码。
 * PC 证书若仍是每次新生成，指纹每次都对不上 —— 退化成「每次都核」，安全，只是多一步。
 */
object RemoteUnattended {
    enum class Step { Face, Code, Pin, Locked }

    /**
     * 这一刻该画哪一道。[codeVerified] = 这次会话已核过短码（人点了「一致」，或记住的指纹对上了）。
     * 没核过时哪怕 `needPin` 先到也停在 Code —— PIN 只记下「要」，等核过再出键盘。
     */
    fun step(localAuthPassed: Boolean, codeVerified: Boolean, pinRequested: Boolean, pinLocked: Boolean): Step = when {
        !localAuthPassed -> Step.Face
        !codeVerified -> Step.Code
        pinRequested -> if (pinLocked) Step.Locked else Step.Pin
        else -> Step.Code
    }

    /** PIN 能不能发：本机认证过了、短码也核过了。少一样都不发（PIN 是那台电脑的长期凭证）。 */
    fun maySendPin(localAuthPassed: Boolean, codeVerified: Boolean): Boolean = localAuthPassed && codeVerified

    /**
     * 记住的电脑侧指纹 [saved] 和这次会话的 [current] 对得上 → 不用再核。这次没算出指纹（null）一律当没核过：
     * 认不出对端是谁，就不能跳过核对。
     */
    fun fingerprintMatches(saved: String?, current: String?): Boolean = current != null && saved == current

    /**
     * 核对短码那一道念哪一档（批次 286 §四）：记过指纹、这次又对不上 →「要重新核对」（电脑重置过远程配对就会这样）；
     * 从没记过 →「第一次连」。这次没算出指纹不算「变了」—— 认不出就不说它变了，照第一次念。
     */
    fun fingerprintChanged(saved: String?, current: String?): Boolean = saved != null && current != null && saved != current
}

// MARK: - 推文件被拒（批次 290 §六 3）

/**
 * 控制档进行中往那台电脑推文件，被控端整单拒、回 `remote_active`（PC #129：那个「接收」框是系统对话框，正在控的人能替主人点）。
 * 原来这个码原样印在「传到 N% 断了：remote_active」里；290 给了单独的三段式。
 * 按钮选了［知道了］+「结束后再推一次就行。」，不做「等结束后重传」的排队：推文件走的是这场会话的 DataChannel，
 * 会话一结束通道就没了，Android 没有会话之外往电脑推文件的路，排进队也推不出去。
 */
object RemoteFilePush {
    const val CONTROLLED = "remote_active"
    fun rejectedWhileControlled(error: String?): Boolean = error == CONTROLLED
}

// MARK: - 快照降级与画质（§14.5 / RC-3.5）

object RemoteFallback {
    /** WebRTC 多久没连上就降到快照（秒）。 */
    const val WEBRTC_TIMEOUT = 15.0
    /** 快照模式多久拉一张（§14.5「每 2 s 一张」）。 */
    const val SNAPSHOT_INTERVAL = 2.0
    /** 等被控端同意的本地兜底（服务端 30 s 判 no_answer；多等 5 s，只防那一帧丢了让人永远在等）。 */
    const val CONSENT_WAIT = 35.0
    /** ICE 断了之后等多久（RC-8.1 的 30 s 宽限）。 */
    const val RECONNECT_GRACE = 30.0
    /** 切后台之后保留多久（RC-8.4）。 */
    const val BACKGROUND_KEEP = 30.0
    /** 同意卡自动拒绝的秒数（批次 262「10 秒后自动拒绝」；服务端 30 s 才判 no_answer，被控端 10 s 就按拒绝回）。 */
    const val CONSENT_SECONDS = 10

    /** 等待页「还剩 N 秒」：从同意卡弹出那一刻倒数，到 0 停住（不出负数；真到点由被控端回拒绝）。iOS: RemoteRules.swift:461。 */
    fun consentLeft(since: Double, now: Double): Int = max(0, CONSENT_SECONDS - floor(now - since).toInt())

    /**
     * 被控端「控制时空闲降档」的缺省分钟数（设计侧批次 264 §二 / PC 设置 › 远程访问）。`{t:"idle_downgrade"}` 今天不带分钟数，
     * 控制端提示里的 N 只能写缺省值；被控端以后带上 `min` 就照它（[RemoteHostMessage.IdleDowngrade.minutes]）。
     */
    const val CONTROL_IDLE_MIN = 15
    /** 被控端「只看时空闲结束」的缺省分钟数（批次 264 §二）。结束原因 `idle` 不带分钟数，同上只能写缺省值。 */
    const val VIEW_IDLE_MIN = 30

    /** `source=snapshot` 的「看一眼」从头到尾不建 WebRTC（§14.5）。 */
    fun usesWebRTC(source: String) = source != "snapshot"

    /** 该不该降到快照：要建 WebRTC 的会话、进 connecting 已经 15 秒、还没连上。进 connecting 之前（等同意）不计时。 */
    fun shouldFallback(source: String, connectingSince: Double?, connected: Boolean, now: Double): Boolean {
        if (!usesWebRTC(source) || connected || connectingSince == null) return false
        return now - connectingSince >= WEBRTC_TIMEOUT
    }

    /** 画质档（契约 §14.3 `quality.level`）。 */
    val qualityLevels = listOf("auto", "hd", "smooth", "saver")

    /** 默认档：蜂窝网络默认省流（RC-3.5），别的自动。 */
    fun defaultQuality(cellular: Boolean) = if (cellular) "saver" else "auto"
}

// MARK: - 文案 key

/**
 * 码 → 文案 key（iOS: RemoteRules.swift:457）。返回 key 而不是句子：这个文件要在纯 JVM 上跑；界面用
 * [remoteString]（key 里的 `.` 换成 `_` 就是 strings_ios.xml 里的资源名）。认不得的码一律落兜底那一句。
 */
object RemoteCopy {
    /**
     * 服务端 `END_REASONS` + 控制端本地的三种（ICE 断了没回来 / 本机认证没过 / 切后台太久）。
     * 末尾五种是端上**自己推出来**的结束原因（服务端那边一律只记 `by_host`，分不出来；见 [endCopy]）：
     * `shortcut` / `idle` / `access_off` / `pin_locked` 来自被控端结束前经 rc 通道发的 `{t:"bye"}`；
     * `granting` = 同意阶段服务端回 `no_permission`（对方在同意卡上点了「去系统设置」）；
     * `host_sleep` 今天没人发（PC 不检测睡眠），字面先留着。iOS: RemoteRules.swift:486。
     */
    val endReasons = listOf(
        "by_controller", "by_host", "declined", "no_answer", "host_offline", "controller_gone",
        "revoked", "signed_out", "no_membership", "idle", "error", "no_permission", "replaced",
        "ice_lost", "auth_failed", "background",
        "shortcut", "granting", "host_sleep", "access_off", "pin_locked",
    )

    /** 结束卡上给哪几颗钮（批次 262 结束原因表括号里那几颗 + 264 §五「对方忙」）。 */
    enum class EndButton { Close, Retry, ToView }

    /** 「重新连接」也是同一个答案的几种：只给「关闭」（给了就是让人重试一个必然失败的动作）。 */
    val closeOnlyReasons = listOf("replaced", "host_sleep", "access_off", "pin_locked", "no_membership", "revoked", "signed_out")

    /** 发起被拒时哪些码值得重来一次：没连上服务器 / 对方离线 / 对方忙。要会员、对方没开、缺权限重来也是同一句。 */
    val retryableErrors = listOf("not_connected", "offline", "busy")

    /**
     * 结束卡的钮（iOS: RemoteRules.swift:505）。`busyKind`（服务端 `rc_error.busy_kind`，只在 busy 时有）：
     * control = 可控名额被占 →［关闭］［改成只看］（主钮，重发一次查看请求）；view = 只看也满了 →［关闭］［重新连接］；
     * 老服务端没带 busy_kind → 照 view 那一种给（重新连接总是安全的）。
     */
    fun endButtons(errorCode: String?, busyKind: String?, endReason: String?): List<EndButton> {
        if (errorCode != null) {
            if (errorCode == "busy" && busyKind == "control") return listOf(EndButton.Close, EndButton.ToView)
            return if (errorCode in retryableErrors) listOf(EndButton.Close, EndButton.Retry) else listOf(EndButton.Close)
        }
        if (endReason != null && endReason in closeOnlyReasons) return listOf(EndButton.Close)
        return listOf(EndButton.Close, EndButton.Retry)
    }

    /** 结束卡那一句的一个参数：现成的字（键名、分钟数）或一个时刻（毫秒，界面按本机时区格式化成 HH:MM）。 */
    sealed interface EndArg {
        data class Text(val text: String) : EndArg
        data class Time(val ms: Long) : EndArg
    }

    /** 结束卡那一句：key + 参数。 */
    data class EndCopy(val key: String, val args: List<EndArg>)

    /**
     * 被控端 bye 能接管的服务端原因：服务端只知道「对方那边结束了」（by_host）或干脆没说 / 归成了 error。
     * 别的原因（你结束了、登出、被顶替……）是控制端这一侧的事实，bye 不该改写它们。
     */
    val byeOverridable = setOf("by_host", "error", "idle", "host_offline")

    /**
     * 端上真正用来选字面和钮的结束原因：服务端原因 + 被控端的 bye + 是否还在同意阶段（iOS: RemoteRules.swift:528）。
     * - bye.hotkey → shortcut；idle → idle；access_off → access_off；pin_locked → pin_locked；error / peer → 照服务端原因。
     * - 同意阶段（还没进 connecting）服务端回 `no_permission` = 对方在同意卡上点了「去系统设置」→ granting。
     * - 没收到 bye 就照服务端原因（原逻辑）。
     */
    fun effectiveEndReason(server: String?, bye: RemoteBye?, duringConsent: Boolean): String? {
        if (server == "no_permission" && duringConsent) return "granting"
        if (bye != null && (server ?: "by_host") in byeOverridable) {
            when (bye.why) {
                "hotkey" -> return "shortcut"
                "idle" -> return "idle"
                "access_off" -> return "access_off"
                "pin_locked" -> return "pin_locked"
            }
        }
        return server
    }

    /**
     * 结束卡那一句（iOS: RemoteRules.swift:545）。快捷键照 bye.key 画（Mac 画 ⌃⌥⌘R，其余 Ctrl+Alt+…；没带键时写缺省 ⌃⌥⌘R）；
     * 空闲的 N 用 bye.min（没收到 bye 才退回缺省 30）；PIN 锁到几点用 bye.until_ms（没有就不带时刻那一句退回 by_host）。
     */
    fun endCopy(server: String?, bye: RemoteBye?, duringConsent: Boolean): EndCopy =
        when (val r = effectiveEndReason(server, bye, duringConsent)) {
            "shortcut" -> {
                val k = bye?.key?.takeIf { it.isNotEmpty() }?.let { RemoteKeys.accelText(it, bye.mac) } ?: "⌃⌥⌘R"
                EndCopy("remote.end.shortcut", listOf(EndArg.Text(k)))
            }
            "idle" -> EndCopy("remote.end.idle", listOf(EndArg.Text((bye?.min ?: RemoteFallback.VIEW_IDLE_MIN).toString())))
            "pin_locked" -> {
                val t = bye?.untilMs
                if (t == null || t <= 0) EndCopy(endReasonKey("by_host"), emptyList())
                else EndCopy("remote.end.pin_locked", listOf(EndArg.Time(t)))
            }
            else -> EndCopy(endReasonKey(r), emptyList())
        }

    /** 「没连上」那一句的 key（批次 264 §五）。不是 busy → null（走结束原因 / 拒绝码那一套）。 */
    fun busyKey(errorCode: String?, busyKind: String?): String? {
        if (errorCode != "busy") return null
        return if (busyKind == "control") "remote.busy.control" else "remote.busy.view"
    }

    fun endReasonKey(r: String?): String = if (r != null && r in endReasons) "remote.end.$r" else "remote.end.error"

    /** 服务端 `REJECT_CODES` + 本地「发不出去」（长连接没连上）。 */
    val errorCodes = listOf("offline", "remote_off", "no_permission", "need_membership", "busy", "bad_request", "locate_gone", "not_connected")

    fun errorKey(code: String?): String = if (code != null && code in errorCodes) "remote.err.$code" else "remote.err.bad_request"

    /** `rc_state.notice`。 */
    val notices = listOf("upgrade_declined", "no_control_permission", "busy")

    fun noticeKey(n: String?): String? = if (n != null && n in notices) "remote.notice.$n" else null

    /** `{t:"denied", reason}`。 */
    val deniedReasons = listOf("blacklist", "local_input", "view_only")

    fun deniedKey(r: String?): String = if (r != null && r in deniedReasons) "remote.denied.$r" else "remote.denied.view_only"

    /** manifest `unavailable_reason`（§14.1）。PC 给了 `unavailable_text` 时优先照抄那一句。 */
    val unavailableReasons = listOf("remote_off", "no_screen_permission", "platform")

    fun unavailableKey(r: String?): String = if (r != null && r in unavailableReasons) "remote.gate.$r" else "remote.gate.remote_off"

    fun qualityKey(level: String) = "remote.quality.$level"

    fun modKey(m: String) = "remote.mod.$m"

    /** 本地判出来的「不能点」的原因（入口置灰那一句）。 */
    val gateKeys = listOf("remote.gate.offline", "remote.gate.no_remote", "remote.gate.no_control", "remote.gate.no_snapshot", "remote.gate.need_membership")

    /** 带设备名参数的那几句（`getString(key, 名字)`；别的 gate key 不带参数）。 */
    val gateKeysWithName = listOf("remote.gate.offline")

    /** 文案 key → Android 资源名（strings_ios.xml 由脚本生成，`.` 一律换 `_`）。 */
    fun resName(key: String) = key.replace('.', '_')
}

// MARK: - 入口能不能点（RC-1.2 / §14.7）

/** 被控端的一块屏（manifest 的 `displays` / DataChannel 的 `{t:"displays"}`）。id 统一成字符串（Electron 给数字）。 */
data class RemoteDisplayInfo(val id: String, val name: String, val primary: Boolean, val width: Int, val height: Int)

/**
 * 设备 providers 里 `remote` 那一项里控制端要看的几样（iOS: RemoteRules.swift:517）。服务端 824a61d 起 `/devices/all`
 * 在设备报了时透传 `features` / `displays` / `unavailable_text`（`model/Models.kt` 的 ProviderInfo 已收）；老服务端 / 老被控端
 * 没带时拿不到就**不在本地挡**（交给服务端判）。
 */
data class RemoteManifest(
    val available: Boolean,
    val reason: String = "",
    val text: String = "",
    val skills: List<String> = emptyList(),
    /** null = 服务端没透传（不知道）；false = 明说不能控。 */
    val control: Boolean? = null,
    val unattended: Boolean? = null,
    val displays: List<RemoteDisplayInfo> = emptyList(),
)

enum class RemoteEntry { View, Control, Peek }

sealed interface RemoteGate {
    data object Ok : RemoteGate
    /** 置灰，[text] 有值时照抄被控端那一句，否则用 [key]。 */
    data class Disabled(val key: String, val text: String?) : RemoteGate
    /** 不是会员：置灰 + 点了跳商城。 */
    data object NeedMembership : RemoteGate
}

data class RemoteRequestKind(val mode: String, val source: String)

object RemoteEntryRules {
    /**
     * 整台连不连得上（设计侧批次 262 / 264 置灰原因表里「全灰」的那几种；iOS: RemoteRules.swift:620）：
     * 不在线 → 没装 / 没开 → 缺屏幕录制 → 会员。不是 [RemoteGate.Ok] 时「看一眼」「远程连接」两颗一起灰，原因写在 hero 卡下。
     * 会员排在被控端那几条之后：对方电脑根本没开，告诉人「去开会员」是让他花钱买一个用不了的东西。
     * 批次 264 §四：**看一眼也要会员** —— 所以会员在这一层判、不分入口。`member` null = 还没问到（不挡，服务端发起时判）。
     */
    fun deviceGate(online: Boolean, manifest: RemoteManifest?, member: Boolean?): RemoteGate {
        if (!online) return RemoteGate.Disabled("remote.gate.offline", null)
        val m = manifest ?: return RemoteGate.Disabled("remote.gate.no_remote", null)
        if (!m.available) {
            val t = m.text.trim()
            return RemoteGate.Disabled(RemoteCopy.unavailableKey(m.reason.ifEmpty { null }), t.ifEmpty { null })
        }
        if (member == false) return RemoteGate.NeedMembership
        return RemoteGate.Ok
    }

    /**
     * 一个入口能不能点：先过整台那一层，再看入口自己的 —— 缺辅助功能只灰「远程控制」；被控端版本太旧不支持看一眼。
     * 批次 264 §三：「对方设成了只能看」那条删了（被控端没有这个设置），`control == false` 只表示缺辅助功能权限。
     */
    fun gate(entry: RemoteEntry, online: Boolean, manifest: RemoteManifest?, member: Boolean?): RemoteGate {
        val whole = deviceGate(online, manifest, member)
        if (whole != RemoteGate.Ok || manifest == null) return whole
        if (entry == RemoteEntry.Control && manifest.control == false) return RemoteGate.Disabled("remote.gate.no_control", null)
        if (entry == RemoteEntry.Peek && manifest.skills.isNotEmpty() && "snapshot" !in manifest.skills) {
            return RemoteGate.Disabled("remote.gate.no_snapshot", null)
        }
        return RemoteGate.Ok
    }

    /** 入口发起时用的 mode / source。看一眼 = 只看 + snapshot。 */
    fun request(entry: RemoteEntry): RemoteRequestKind = when (entry) {
        RemoteEntry.View -> RemoteRequestKind("view", "manual")
        RemoteEntry.Control -> RemoteRequestKind("control", "manual")
        RemoteEntry.Peek -> RemoteRequestKind("view", "snapshot")
    }

    /** 桩 API `RemoteLauncher.launch(deviceId, mode, source)` 只给 mode / source：反推是哪个入口（过同一道闸）。 */
    fun entry(mode: String, source: String): RemoteEntry = when {
        source == "snapshot" -> RemoteEntry.Peek
        mode == "control" -> RemoteEntry.Control
        else -> RemoteEntry.View
    }
}

// MARK: - 信令（§14.2）

/** `/ws/chat` 上发出去的 rc_* 帧。字段名照服务端 `_remote_from_controller` 一个字不差 —— 拼错一个键服务端只会当它不存在。 */
object RemoteSignal {
    fun request(
        requestId: String, deviceId: String, mode: String, source: String,
        runId: String? = null, askId: String? = null, clientId: String, clientName: String,
        clientKind: String = UmbraUrl.CLIENT_SRC,
    ): JsonObject = buildJsonObject {
        put("type", "rc_request"); put("request_id", requestId); put("device_id", deviceId); put("mode", mode)
        put("source", source); put("client_id", clientId); put("client_name", clientName); put("client_kind", clientKind)
        if (!runId.isNullOrEmpty()) put("run_id", runId)
        if (!askId.isNullOrEmpty()) put("ask_id", askId)
    }

    fun answer(sessionId: String, sdp: String): JsonObject = buildJsonObject {
        put("type", "rc_answer"); put("session_id", sessionId); put("sdp", sdp)
    }

    /** `usernameFragment` 从 candidate 串里的 `ufrag` 取（libwebrtc 的 IceCandidate 不单独给这个字段）；取不到就不带。 */
    fun ice(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): JsonObject = buildJsonObject {
        put("type", "rc_ice"); put("session_id", sessionId); put("candidate", candidate); put("sdpMLineIndex", sdpMLineIndex)
        if (sdpMid != null) put("sdpMid", sdpMid)
        ufrag(candidate)?.let { put("usernameFragment", it) }
    }

    fun ufrag(candidate: String): String? {
        val parts = candidate.split(" ").filter { it.isNotEmpty() }
        val i = parts.indexOf("ufrag")
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else null
    }

    fun mode(sessionId: String, mode: String) = buildJsonObject { put("type", "rc_mode"); put("session_id", sessionId); put("mode", mode) }
    fun end(sessionId: String) = buildJsonObject { put("type", "rc_end"); put("session_id", sessionId) }
    fun attach(sessionId: String) = buildJsonObject { put("type", "rc_attach"); put("session_id", sessionId) }
    fun handback(sessionId: String) = buildJsonObject { put("type", "rc_handback"); put("session_id", sessionId) }

    /** `rc_stats`：`connected` 只在第一次连上时带 true（服务端凭它进 active）；`relay_bytes` 累计值、不发负数。 */
    fun stats(sessionId: String, connected: Boolean, path: String, relayBytes: Long, rttMs: Int?): JsonObject = buildJsonObject {
        put("type", "rc_stats"); put("session_id", sessionId); put("path", path); put("relay_bytes", max(0L, relayBytes))
        if (connected) put("connected", true)
        if (rttMs != null && rttMs >= 0) put("rtt_ms", rttMs)
    }
}

/**
 * 只认自己的会话（§14.2，iOS: RemoteRules.swift:637）。
 *
 * ⚠️ 服务端顺序：**先**推 `rc_state{awaiting_consent}`、**再**回 `rc_created` —— 第一帧 rc_state 到时控制端还不知道自己的 id。
 * 只认已知 id 会丢掉它，来者不拒又会认到别的端发起的会话（同一账号 PC 上也在连）。所以还在等 rc_created 时先攒着，
 * 拿到 id 之后把攒着的里**是这个 id 的**放出来，别的扔掉。
 */
class RemoteSignalGate(val requestId: String, var sessionId: String? = null) {
    enum class Verdict { Accept, Buffer, Drop }

    companion object {
        /** 攒着的帧最多几条（防一个永远不来的 rc_created 把内存攒满）。 */
        const val BUFFER_LIMIT = 16
    }

    fun verdict(type: String, sessionId: String?, requestId: String?): Verdict {
        val mine = this.sessionId
        return when (type) {
            "rc_created" -> if (mine == null && requestId == this.requestId) Verdict.Accept else Verdict.Drop
            "rc_error" -> when {
                // 发起被拒：按 request_id 认。「没开远程控制」那一句不带 request_id、session_id 为空 —— 等着的时候也认。
                requestId != null -> if (requestId == this.requestId && mine == null) Verdict.Accept else Verdict.Drop
                mine != null -> if (sessionId == mine) Verdict.Accept else Verdict.Drop
                else -> if (sessionId.isNullOrEmpty()) Verdict.Accept else Verdict.Drop
            }
            else -> when {
                sessionId.isNullOrEmpty() -> Verdict.Drop
                mine != null -> if (sessionId == mine) Verdict.Accept else Verdict.Drop
                else -> Verdict.Buffer
            }
        }
    }
}

/** `rc_state.ice_servers` 的一项（`RTCConfiguration.iceServers` 同形：urls 可能是串也可能是数组）。 */
data class RemoteIceServer(val urls: List<String>, val username: String?, val credential: String?)

/** `rc_state` 里控制端要的那些（§14.2）。 */
data class RemoteState(
    val sessionId: String,
    val state: String,
    val mode: String,
    val source: String,
    val runId: String,
    val hostDeviceId: String,
    val hostName: String,
    val unattended: Boolean,
    val relayAllowed: Boolean,
    val startedAtMs: Long,
    val reason: String?,
    val notice: String?,
    val iceServers: List<RemoteIceServer>?,
) {
    companion object {
        fun parse(j: JsonObject): RemoteState? {
            if (RemoteJson.str(j["type"]) != "rc_state") return null
            val sid = RemoteJson.str(j["session_id"])?.takeIf { it.isNotEmpty() } ?: return null
            val st = RemoteJson.str(j["state"]) ?: return null
            val ice = (j["ice_servers"] as? JsonArray)?.mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                val urls: List<String> = when (val u = s["urls"]) {
                    is JsonPrimitive -> RemoteJson.str(u)?.let { listOf(it) } ?: return@mapNotNull null
                    is JsonArray -> u.mapNotNull { RemoteJson.str(it) }
                    else -> return@mapNotNull null
                }
                if (urls.isEmpty()) return@mapNotNull null
                RemoteIceServer(urls, RemoteJson.str(s["username"]), RemoteJson.str(s["credential"]))
            }
            return RemoteState(
                sessionId = sid, state = st, mode = RemoteJson.str(j["mode"]) ?: "view",
                source = RemoteJson.str(j["source"]) ?: "manual", runId = RemoteJson.str(j["run_id"]) ?: "",
                hostDeviceId = RemoteJson.str(j["host_device_id"]) ?: "", hostName = RemoteJson.str(j["host_name"]) ?: "",
                unattended = RemoteJson.bool(j["unattended"]) ?: false, relayAllowed = RemoteJson.bool(j["relay_allowed"]) ?: true,
                startedAtMs = RemoteJson.int64(j["started_at_ms"]) ?: 0, reason = RemoteJson.str(j["reason"]),
                notice = RemoteJson.str(j["notice"]), iceServers = ice,
            )
        }
    }
}

/**
 * 从 JSON 取值，口径同 iOS `RemoteJSON` + `as? T`：**类型不对就当没有**（数字不当串、串不当数字 / 布尔）。
 * kotlinx 的 `JsonPrimitive.content` 会把数字也转成串，直接用会把 `"ok":"true"` 这种当成布尔收下。
 */
object RemoteJson {
    fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

    fun int64(e: JsonElement?): Long? {
        val p = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
        p.longOrNull?.let { return it }
        return p.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }

    fun double(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull

    /** 显示器 id：串原样、数字转串（整数不带 `.0`）。 */
    fun idString(e: JsonElement?): String? = str(e) ?: int64(e)?.toString()

    fun obj(text: String): JsonObject? = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** 统计报告里的值是 Java 对象（Long / BigInteger / Double / Boolean / String）：数字都收。 */
    fun anyLong(v: Any?): Long? = when (v) {
        is Number -> if (v is Double && !v.isFinite()) null else v.toLong()
        else -> null
    }

    fun anyDouble(v: Any?): Double? = (v as? Number)?.toDouble()
}

// MARK: - DataChannel `rc`（被控端 → 控制端，§14.3）

/**
 * 被控端的 `{t:"bye", why, min?, until_ms?, key?, mac?}`（iOS: RemoteRules.swift:862）。why：hotkey / idle / access_off / pin_locked / error / peer。
 * `key` 是 Electron accelerator 原文（"Control+Alt+Command+R"），`mac` = 被控端是不是 Mac（没带当是，同 PC 控制端的接法）。
 */
data class RemoteBye(
    val why: String,
    val min: Int? = null,
    val untilMs: Long? = null,
    val key: String? = null,
    val mac: Boolean = true,
)

data class RemoteFileItem(val name: String, val size: Long, val mtimeMs: Long) {
    val id: String get() = name
}

sealed interface RemoteHostMessage {
    data class Displays(val items: List<RemoteDisplayInfo>, val current: String?) : RemoteHostMessage
    data class Clip(val text: String) : RemoteHostMessage
    data class Pong(val ts: Double) : RemoteHostMessage
    data object NeedPin : RemoteHostMessage
    /** `left`：还能试几次（被控端带了才有，PC 已带；无人值守页「PIN 不对，还能试 N 次」）。 */
    data class PinResult(val ok: Boolean, val lockedUntilMs: Long?, val left: Int? = null) : RemoteHostMessage
    data class Protected(val app: String) : RemoteHostMessage
    data object Unprotected : RemoteHostMessage
    data class Denied(val reason: String, val app: String?) : RemoteHostMessage
    /** 被控端本地结束会话前说的「为什么」（PC `logic.ts › byeOf`）。随后到的 `rc_state ended` 用它选字面（[RemoteCopy.endCopy]）。 */
    data class Bye(val bye: RemoteBye) : RemoteHostMessage
    /** 可控档空闲降为只看（批次 264 §二）。`minutes`：被控端带了 `min` 就是它，没带（今天的 PC）null，提示里写缺省 15。 */
    data class IdleDowngrade(val minutes: Int? = null) : RemoteHostMessage
    data class Files(val dir: String, val items: List<RemoteFileItem>) : RemoteHostMessage
    data class FileBegin(val id: String, val name: String, val size: Long) : RemoteHostMessage
    data class FileEnd(val id: String, val ok: Boolean, val error: String?) : RemoteHostMessage
    data class FilePutResult(val id: String, val ok: Boolean, val error: String?) : RemoteHostMessage

    companion object {
        /** 解析一条文本消息。**认不得 / 缺字段 / 类型不对一律回 null**（丢掉这一条），不猜 —— 版本错开时新消息不该把会话弄崩。 */
        fun parse(text: String): RemoteHostMessage? {
            val j = RemoteJson.obj(text) ?: return null
            val t = RemoteJson.str(j["t"]) ?: return null
            return when (t) {
                "displays" -> {
                    val arr = j["items"] as? JsonArray ?: return null
                    Displays(arr.mapNotNull { (it as? JsonObject)?.let(::display) }, RemoteJson.idString(j["current"]))
                }
                "clip" -> Clip(RemoteJson.str(j["text"]) ?: return null)
                "pong" -> Pong(RemoteJson.double(j["ts"]) ?: return null)
                "need_pin" -> NeedPin
                "pin_result" -> PinResult(
                    RemoteJson.bool(j["ok"]) ?: return null, RemoteJson.int64(j["locked_until_ms"]),
                    RemoteJson.int64(j["left"])?.let { max(0L, it).toInt() },
                )
                "protected" -> Protected(RemoteJson.str(j["app"]) ?: "")
                "unprotected" -> Unprotected
                "denied" -> Denied(RemoteJson.str(j["reason"]) ?: return null, RemoteJson.str(j["app"]))
                "bye" -> {
                    val why = RemoteJson.str(j["why"])?.takeIf { it.isNotEmpty() } ?: return null
                    Bye(RemoteBye(
                        why = why,
                        min = RemoteJson.int64(j["min"])?.toInt()?.takeIf { it > 0 },
                        untilMs = RemoteJson.int64(j["until_ms"])?.takeIf { it > 0 },
                        key = RemoteJson.str(j["key"]),
                        mac = RemoteJson.bool(j["mac"]) ?: true,
                    ))
                }
                // min = 0 当没带（0 = 不限，不会降档）。
                "idle_downgrade" -> IdleDowngrade(RemoteJson.int64(j["min"])?.toInt()?.takeIf { it > 0 })
                "files" -> {
                    val dir = RemoteJson.str(j["dir"]) ?: return null
                    val arr = j["items"] as? JsonArray ?: return null
                    Files(dir, arr.mapNotNull { e ->
                        val f = e as? JsonObject ?: return@mapNotNull null
                        val n = RemoteJson.str(f["name"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val sz = RemoteJson.int64(f["size"])?.takeIf { it >= 0 } ?: return@mapNotNull null
                        RemoteFileItem(n, sz, RemoteJson.int64(f["mtime_ms"]) ?: 0)
                    })
                }
                "file_begin" -> {
                    val id = RemoteJson.str(j["id"]) ?: return null
                    val n = RemoteJson.str(j["name"]) ?: return null
                    val sz = RemoteJson.int64(j["size"])?.takeIf { it >= 0 } ?: return null
                    FileBegin(id, n, sz)
                }
                "file_end" -> FileEnd(RemoteJson.str(j["id"]) ?: return null, RemoteJson.bool(j["ok"]) ?: return null, RemoteJson.str(j["error"]))
                "file_put_result" -> FilePutResult(RemoteJson.str(j["id"]) ?: return null, RemoteJson.bool(j["ok"]) ?: return null, RemoteJson.str(j["error"]))
                else -> null
            }
        }

        fun display(d: JsonObject): RemoteDisplayInfo? {
            val id = RemoteJson.idString(d["id"]) ?: return null
            return RemoteDisplayInfo(
                id, RemoteJson.str(d["name"]) ?: "", RemoteJson.bool(d["primary"]) ?: false,
                (RemoteJson.int64(d["width"]) ?: 0).toInt(), (RemoteJson.int64(d["height"]) ?: 0).toInt(),
            )
        }
    }
}

/** 控制端 → 被控端的非输入消息（§14.3）。这些在只看档里也照发（换屏、画质、取剪贴板、PIN）。 */
object RemoteCommand {
    fun display(id: String) = buildJsonObject { put("t", "display"); put("id", id) }
    fun quality(level: String) = buildJsonObject { put("t", "quality"); put("level", level) }
    fun clipSend(text: String) = buildJsonObject { put("t", "clip_send"); put("text", text) }
    fun clipGet() = buildJsonObject { put("t", "clip_get") }
    fun pin(pin: String) = buildJsonObject { put("t", "pin"); put("pin", pin) }
    fun ping(ts: Double) = buildJsonObject { put("t", "ping"); put("ts", ts) }
    fun filesList(dir: String) = buildJsonObject { put("t", "files_list"); put("dir", dir) }

    fun fileGet(id: String, dir: String, name: String, offset: Long = 0) = buildJsonObject {
        put("t", "file_get"); put("id", id); put("dir", dir); put("name", name)
        if (offset > 0) put("offset", offset)
    }

    fun filePutBegin(id: String, name: String, size: Long) = buildJsonObject {
        put("t", "file_put_begin"); put("id", id); put("name", name); put("size", size)
    }

    fun filePutEnd(id: String) = buildJsonObject { put("t", "file_put_end"); put("id", id) }

    /** 无人值守页的 PIN 点：至少画 6 个、输到第 7、8 位再长出来（PIN 是 6–8 位，画 8 个会让人以为一定要输 8 位）。iOS: RemoteRules.swift:992。 */
    fun pinDots(count: Int): Int = min(8, max(6, count))

    /** 无人值守页数字键盘按一下之后的 PIN：只收 ASCII 数字、最多 8 位；⌫ 删最后一位。 */
    fun pinTyped(pin: String, key: String): String {
        if (key == "⌫") return pin.dropLast(1)
        if (key.length != 1 || key[0] !in '0'..'9' || pin.length >= 8) return pin
        return pin + key
    }

    /** 无人值守 PIN：6–8 位 ASCII 数字（RC-11.2）。不合格的不发 —— 发了只是白白吃掉一次「错 5 次锁 30 分钟」的机会。 */
    fun validPin(s: String): Boolean = s.length in 6..8 && s.all { it in '0'..'9' }
}

// MARK: - DataChannel `file`（二进制分块，§14.3）

/** 一块的区间 [start, end)（推文件 / 续传用）。 */
data class RemoteSpan(val start: Long, val end: Long)

/** 每块 = 4 字节大端 id 长度 + id（UTF-8）+ 数据（≤ 64 KB）。 */
object RemoteFileCodec {
    const val MAX_CHUNK = 64 * 1024
    /** 中继时单文件上限（RC-6.2：走中继会计入流量）。 */
    const val RELAY_LIMIT: Long = 100L * 1024 * 1024
    /** 直连时单文件上限。 */
    const val DIRECT_LIMIT: Long = 500L * 1024 * 1024

    fun limit(relay: Boolean): Long = if (relay) RELAY_LIMIT else DIRECT_LIMIT

    fun encode(id: String, payload: ByteArray): ByteArray? {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        if (idBytes.isEmpty() || idBytes.size > 0xFFFF || payload.size > MAX_CHUNK) return null
        val n = idBytes.size
        val out = ByteArray(4 + n + payload.size)
        out[0] = (n ushr 24 and 0xFF).toByte(); out[1] = (n ushr 16 and 0xFF).toByte()
        out[2] = (n ushr 8 and 0xFF).toByte(); out[3] = (n and 0xFF).toByte()
        idBytes.copyInto(out, 4)
        payload.copyInto(out, 4 + n)
        return out
    }

    /** 解一块。头不完整、id 长度越界、id 不是 UTF-8、数据超过一块 → null（丢掉这块，交给 file_end 的 ok:false 收尾）。 */
    fun decode(data: ByteArray): Pair<String, ByteArray>? {
        if (data.size < 4) return null
        val n = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or
            ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
        if (n <= 0 || 4 + n > data.size) return null
        val idBytes = data.copyOfRange(4, 4 + n.toInt())
        val dec = Charsets.UTF_8.newDecoder()
        val id = runCatching { dec.decode(java.nio.ByteBuffer.wrap(idBytes)).toString() }.getOrNull() ?: return null
        val payload = data.copyOfRange(4 + n.toInt(), data.size)
        if (payload.size > MAX_CHUNK) return null
        return id to payload
    }

    /** 一个文件切成几块的区间。从 offset 开始（续传）。 */
    fun ranges(size: Long, offset: Long = 0, chunk: Int = MAX_CHUNK): List<RemoteSpan> {
        if (size <= 0 || chunk <= 0) return emptyList()
        val out = mutableListOf<RemoteSpan>()
        var at = max(0L, offset)
        while (at < size) {
            val end = min(size, at + chunk)
            out += RemoteSpan(at, end)
            at = end
        }
        return out
    }

    /** 传输用的 id：这台手机这次会话里唯一就够了（被控端只拿它对上 begin / 块 / end）。 */
    fun makeId(seq: Int) = "android-$seq"
}

// MARK: - 质量指示（§14.4）

/** 统计报告里的一项：类型 + 成员（`RTCStats.getMembers()` 原样）。压扁成这个形状，挑选逻辑才能在 JVM 上用假数据跑。 */
data class RemoteStatEntry(val type: String, val values: Map<String, Any?>)

data class RemoteStatsSummary(val path: String, val relayBytes: Long, val rttMs: Int?, val fps: Double?)

object RemoteStatsRules {
    /** 统计上报的间隔（§14.2「约 10 s 一次」）。 */
    const val INTERVAL = 10.0

    /** 任一端是 relay 就是中继（§14.4）。 */
    fun path(localType: String?, remoteType: String?) = if (localType == "relay" || remoteType == "relay") "relay" else "direct"

    /** 挑选中的那一对：优先 transport 的 `selectedCandidatePairId`；没有就找 `state == succeeded && nominated` 的。都找不到回 null。 */
    fun summarize(stats: Map<String, RemoteStatEntry>): RemoteStatsSummary? {
        var pairId: String? = null
        for (s in stats.values) {
            if (s.type != "transport") continue
            val id = s.values["selectedCandidatePairId"] as? String
            if (id != null && stats[id] != null) { pairId = id; break }
        }
        if (pairId == null) {
            pairId = stats.entries.firstOrNull {
                it.value.type == "candidate-pair" && it.value.values["state"] == "succeeded" &&
                    (it.value.values["nominated"] as? Boolean ?: false)
            }?.key
        }
        val pair = pairId?.let { stats[it] } ?: return null
        val localType = (pair.values["localCandidateId"] as? String)?.let { stats[it]?.values?.get("candidateType") as? String }
        val remoteType = (pair.values["remoteCandidateId"] as? String)?.let { stats[it]?.values?.get("candidateType") as? String }
        val p = path(localType, remoteType)
        val bytes = (RemoteJson.anyLong(pair.values["bytesSent"]) ?: 0) + (RemoteJson.anyLong(pair.values["bytesReceived"]) ?: 0)
        val rtt = RemoteJson.anyDouble(pair.values["currentRoundTripTime"])?.let { roundHalfAway(it * 1000).toInt() }
        val fps = stats.values.firstOrNull { it.type == "inbound-rtp" && it.values["kind"] == "video" }
            ?.let { RemoteJson.anyDouble(it.values["framesPerSecond"]) }
        return RemoteStatsSummary(p, if (p == "relay") bytes else 0, rtt, fps)
    }
}

// MARK: - HTTP 回包（§14.5 / §14.7）

/** `POST /remote/sessions/{id}/snapshot` 的回包 `{url, width, height, display, at_ms}`。 */
data class RemoteSnapshot(val url: String, val width: Int, val height: Int, val display: String?, val atMs: Long) {
    companion object {
        fun parse(j: JsonObject): RemoteSnapshot? {
            val url = RemoteJson.str(j["url"])?.takeIf { it.isNotEmpty() } ?: return null
            return RemoteSnapshot(
                url, (RemoteJson.int64(j["width"]) ?: 0).toInt(), (RemoteJson.int64(j["height"]) ?: 0).toInt(),
                RemoteJson.idString(j["display"]), RemoteJson.int64(j["at_ms"]) ?: 0,
            )
        }
    }
}

/** 审计列表的一行（`GET /remote/sessions`，形状见服务端 `session_out`）。**没有任何画面 / 输入 / 剪贴板内容**。 */
data class RemoteSessionRecord(
    val id: String,
    val hostName: String,
    val controllerName: String,
    val controllerKind: String,
    val source: String,
    val mode: String,
    /** 档位变化里到过 control 没有（控过又降回只看的也算控过）。 */
    val everControlled: Boolean,
    val path: String,
    val relayBytes: Long,
    val state: String,
    val endReason: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val fileCount: Int,
) {
    /** 时长（秒）。还没结束的按 nowMs 算。 */
    fun durationSec(nowMs: Long): Long = max(0L, ((endedAtMs ?: nowMs) - startedAtMs) / 1000)

    companion object {
        /** 读不懂（没有 items）回 null —— 和「一条都没有」分开说。 */
        fun parseList(j: JsonObject): List<RemoteSessionRecord>? {
            val items = j["items"] as? JsonArray ?: return null
            return items.mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val id = RemoteJson.str(r["id"]) ?: return@mapNotNull null
                val started = RemoteJson.int64(r["started_at_ms"]) ?: return@mapNotNull null
                val log = (r["mode_log"] as? JsonArray) ?: JsonArray(emptyList())
                val mode = RemoteJson.str(r["mode"]) ?: "view"
                RemoteSessionRecord(
                    id = id, hostName = RemoteJson.str(r["host_name"]) ?: "", controllerName = RemoteJson.str(r["controller_name"]) ?: "",
                    controllerKind = RemoteJson.str(r["controller_kind"]) ?: "", source = RemoteJson.str(r["source"]) ?: "manual",
                    mode = mode,
                    everControlled = mode == "control" || log.any { RemoteJson.str((it as? JsonObject)?.get("mode")) == "control" },
                    path = RemoteJson.str(r["path"]) ?: "", relayBytes = RemoteJson.int64(r["relay_bytes"]) ?: 0,
                    state = RemoteJson.str(r["state"]) ?: "", endReason = RemoteJson.str(r["end_reason"]) ?: "",
                    startedAtMs = started, endedAtMs = RemoteJson.int64(r["ended_at_ms"]),
                    fileCount = (r["files"] as? JsonArray)?.size ?: 0,
                )
            }
        }

        /** 时长拆成 (分, 时)：不到 1 分钟 / N 分钟 / H 小时 M 分由界面套文案。 */
        fun durationParts(sec: Long): Pair<Long, Long> {
            val m = sec / 60
            return (m % 60) to (m / 60)
        }
    }
}

/** `GET /remote/relay` → `{used_bytes, limit_bytes, month, turn_configured}`。 */
data class RemoteRelayStatus(val usedBytes: Long, val limitBytes: Long, val month: String, val turnConfigured: Boolean) {
    /** 用了几成（0…1，超额也封在 1）。额度为 0 时按用满算 —— 那一档等于「不能走中继」。 */
    val fraction: Double get() = if (limitBytes <= 0) 1.0 else min(1.0, max(0.0, usedBytes.toDouble() / limitBytes.toDouble()))

    companion object {
        fun parse(j: JsonObject): RemoteRelayStatus? {
            val used = RemoteJson.int64(j["used_bytes"]) ?: return null
            val limit = RemoteJson.int64(j["limit_bytes"]) ?: return null
            return RemoteRelayStatus(used, limit, RemoteJson.str(j["month"]) ?: "", RemoteJson.bool(j["turn_configured"]) ?: false)
        }
    }
}

/** 字节数说人话（ByteCountFormatter 的替身）：binary=1024 进位（中继额度，iOS `.binary`），否则 1000（文件，iOS `.file`）。 */
object RemoteBytes {
    fun format(n: Long, binary: Boolean): String {
        val base = if (binary) 1024.0 else 1000.0
        val units = listOf("KB", "MB", "GB", "TB")
        if (abs(n.toDouble()) < base) return "$n 字节"
        var v = n.toDouble() / base
        var i = 0
        while (abs(v) >= base && i < units.lastIndex) { v /= base; i++ }
        val s = if (v >= 100 || i == 0) "%.0f".format(v) else "%.1f".format(v).removeSuffix(".0")
        return "$s ${units[i]}"
    }
}

/**
 * 被控端给的文件名 → 本机落盘名（iOS: RemoteClient.swift:588 `localName` + UmbraShareName.base）：
 * 另一台机器上的字符串可能带 `../`、`:`、控制字符 —— 洗成纯文件名段；原后缀（只留字母数字、≤12）接回来，系统靠它认类型。
 */
object RemoteFileName {
    const val MAX_BASE_CHARS = 80

    fun base(raw: String): String {
        val segs = raw.split('/', '\\').map { seg ->
            var t = seg.filter { !it.isISOControl() && it != ':' }.trim()
            while (t.startsWith(".")) t = t.substring(1)
            t
        }.filter { it.isNotEmpty() }.toMutableList()
        if (segs.isNotEmpty()) {
            var last = segs.removeAt(segs.lastIndex)
            val dot = last.lastIndexOf('.')
            if (dot >= 0) last = last.substring(0, dot)
            last = last.trim()
            if (last.isNotEmpty()) segs += last
        }
        var joined = segs.joinToString("_").trim()
        if (joined.length > MAX_BASE_CHARS) joined = joined.take(MAX_BASE_CHARS)
        return joined
    }

    fun local(raw: String): String {
        val b = base(raw)
        val lastSeg = raw.split('/', '\\').last()
        val dot = lastSeg.lastIndexOf('.')
        val ext = if (dot > 0) lastSeg.substring(dot + 1).filter { it.isLetterOrDigit() }.take(12) else ""
        val stem = b.ifEmpty { "file" }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }
}
