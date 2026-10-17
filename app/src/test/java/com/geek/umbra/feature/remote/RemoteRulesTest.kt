package com.geek.umbra.feature.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.geek.umbra.feature.remote.RemoteSignalGate.Verdict as V
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * `RemoteRules.kt` 的行为用例 —— 逐条翻译 iOS `Tools/swifttests/RemoteRulesTests.swift`（输入输出照抄）。
 *
 * 钉的都是「错了不报错」的那一类：点歪（黑边 / 缩放 / 平移反算）、手势发错消息（拖拽变右键）、
 * 修饰键松不开、短码两端算不一样、该降快照没降、认到别人的会话、文件块拆错、文案 key 缺了印出 key 本身。
 * 与 iOS 唯一的差别：`client_kind` 是 "android"、文件传输 id 前缀是 "android-"。
 */
class RemoteRulesTest {
    private fun near(a: Double, b: Double, what: String, tol: Double = 1e-6) =
        assertTrue("$what：得到 $a，应当约等于 $b", abs(a - b) <= tol)

    private fun nearPt(a: RemotePoint?, b: RemotePoint, what: String, tol: Double = 1e-6) =
        assertTrue("$what：得到 $a，应当约等于 $b", a != null && abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    private fun pt(x: Double, y: Double) = RemotePoint(x, y)

    // MARK: 几何

    @Test fun geometry() {
        // 竖屏 390×844 看一块 1512×982 的 Mac 屏：宽占满、上下黑边。
        val view = RemoteSize(390.0, 844.0)
        val frame = RemoteSize(1512.0, 982.0)
        val fit = RemoteGeometry.fitRect(view, frame)
        near(fit.size.w, 390.0, "aspectFit：宽占满")
        near(fit.size.h, 390.0 * 982 / 1512, "aspectFit：高按比例")
        near(fit.origin.y, (844 - 390.0 * 982 / 1512) / 2, "aspectFit：上下黑边各一半")
        nearPt(RemoteGeometry.toFrame(pt(195.0, 422.0), view, frame), pt(0.5, 0.5), "视图中心 → 帧 (0.5, 0.5)")
        assertNull("黑边里的点不算点在画面上", RemoteGeometry.toFrame(pt(195.0, 10.0), view, frame))
        nearPt(RemoteGeometry.toFrame(fit.origin, view, frame), pt(0.0, 0.0), "fit 原点 → (0, 0)")
        nearPt(RemoteGeometry.toFrame(pt(195 + 97.5, 422.0), view, frame, zoom = 2.0), pt(0.625, 0.5), "放大 2 倍：离中心的距离减半")
        val p = RemoteGeometry.toFrame(pt(195.0, 422.0), view, frame, zoom = 2.0, pan = pt(100.0, 0.0))
        nearPt(p, pt(0.5 - 50.0 / 390, 0.5), "放大 + 平移：反算把平移也减掉")
        val n = pt(0.3, 0.7)
        val v = RemoteGeometry.toView(n, view, frame, zoom = 2.5, pan = pt(-40.0, 30.0))
        nearPt(RemoteGeometry.toFrame(v, view, frame, zoom = 2.5, pan = pt(-40.0, 30.0)), n, "toView / toFrame 互逆", tol = 1e-9)
        near(RemoteGeometry.clampZoom(0.4), 1.0, "缩小不许小于 fit")
        near(RemoteGeometry.clampZoom(9.0), 5.0, "放大不超过 5 倍")
        near(RemoteGeometry.clampZoom(Double.NaN), 1.0, "NaN 当 1")
        assertEquals("1 倍时平移归零", pt(0.0, 0.0), RemoteGeometry.clampPan(pt(80.0, 80.0), view, frame, 1.0))
        val cp = RemoteGeometry.clampPan(pt(9999.0, 9999.0), view, frame, 2.0)
        near(cp.x, (390.0 * 2 - 390) / 2, "2 倍时横向最多拖半个超出量")
        near(cp.y, max(0.0, (fit.size.h * 2 - 844) / 2), "2 倍时竖向还没超出视图（黑边那一维）不许拖")
        // 以手指为锚点缩放：手指底下那个帧点不动。
        val anchor = pt(300.0, 450.0)
        val before = RemoteGeometry.toFrame(anchor, view, frame, zoom = 1.5, pan = pt(20.0, 0.0))!!
        val newPan = RemoteGeometry.panAfterZooming(pt(20.0, 0.0), 1.5, 4.0, anchor, view, frame)
        val after = RemoteGeometry.toFrame(anchor, view, frame, zoom = 4.0, pan = newPan)
        nearPt(after, before, "捏合以手指为锚点：手指底下的点不跑")
        val unknown = RemoteGeometry.fitRect(view, RemoteSize(0.0, 0.0))
        assertEquals("帧尺寸未知时按视图本身算", view, unknown.size)
        near(RemoteGeometry.framePixelsPerPoint(view, frame, 1.0), 1512.0 / 390, "1 倍时 pt → 帧像素")
        near(RemoteGeometry.framePixelsPerPoint(view, frame, 2.0), 1512.0 / 780, "放大后同一段手指对应更少像素")
    }

    // MARK: 触控板

    @Test fun trackpad() {
        near(RemoteTrackpad.gain(50.0), 1.0, "慢挪不加速")
        near(RemoteTrackpad.gain(5000.0), RemoteTrackpad.MAX_GAIN, "甩得快加满")
        near(RemoteTrackpad.gain((RemoteTrackpad.SLOW_SPEED + RemoteTrackpad.FAST_SPEED) / 2), 2.0, "中间速度线性插值")
        val t = RemoteTrackpad()
        t.move(39.0, 0.0, 1.0, RemoteSize(390.0, 253.0))
        near(t.cursor.x, 0.6, "慢速挪画面宽度的十分之一 → 光标挪十分之一")
        near(t.cursor.y, 0.5, "只横挪不动纵坐标")
        t.move(10_000.0, -10_000.0, 0.01, RemoteSize(390.0, 253.0))
        assertEquals("光标夹在帧里", pt(1.0, 0.0), t.cursor)
        val z = RemoteTrackpad()
        z.move(39.0, 0.0, 1.0, RemoteSize(780.0, 506.0))
        near(z.cursor.x, 0.55, "放大 2 倍后同样的手指距离挪一半")
        val dead = RemoteTrackpad()
        dead.move(10.0, 10.0, 0.1, RemoteSize(0.0, 0.0))
        assertEquals("画面尺寸未知时不动", pt(0.5, 0.5), dead.cursor)
    }

    // MARK: 手势 → 消息

    @Test fun gestures() {
        val p = pt(0.25, 0.75)
        assertEquals("单击 = 左键单击", listOf(RemoteInput.Click(p, RemoteButton.Left, 1)), RemoteGestureMap.tap(p))
        assertEquals("双击 = 左键 n=2（一条，不是两次单击）", listOf(RemoteInput.Click(p, RemoteButton.Left, 2)), RemoteGestureMap.tap(p, 2))
        assertEquals("双指单击 = 右键", listOf(RemoteInput.Click(p, RemoteButton.Right, 1)), RemoteGestureMap.secondaryTap(p))
        assertEquals("双指上推 = 往下滚，按帧像素", RemoteInput.Wheel(0, 30), RemoteGestureMap.wheel(0.0, -10.0, 3.0))
        assertNull("不到一个像素不发", RemoteGestureMap.wheel(0.1, 0.1, 1.0))
        val click = RemoteInput.Click(pt(0.123456, 0.5), RemoteButton.Right, 1).json("2")
        assertEquals("click 的 t", "click", click["t"]!!.jsonPrimitive.content)
        assertEquals("右键 b=2", 2, click["b"]!!.jsonPrimitive.int)
        assertEquals("click 带 n", 1, click["n"]!!.jsonPrimitive.int)
        assertEquals("click 带 display", "2", click["display"]!!.jsonPrimitive.content)
        near(click["x"]!!.jsonPrimitive.double, 0.1235, "坐标保留 4 位小数", tol = 0.0)
        val key = RemoteInput.Key("KeyC", true, listOf("cmd")).json("1")
        assertEquals("key 的 code", "KeyC", key["code"]!!.jsonPrimitive.content)
        assertEquals("key 的 down", true, key["down"]!!.jsonPrimitive.boolean)
        assertEquals("key 的 mods", listOf("cmd"), key["mods"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull("key 不带 display", key["display"])
        val wheel = RemoteInput.Wheel(1, -2).json("1")
        assertEquals("wheel 的 dy", -2, wheel["dy"]!!.jsonPrimitive.int)
        assertNull("wheel 不带 display（契约 §14.3）", wheel["display"])
        assertEquals("text 的字段叫 s", "你好", RemoteInput.Text("你好").json("1")["s"]!!.jsonPrimitive.content)
        assertEquals("down 带 b", 0, RemoteInput.Down(RemoteButton.Left, p).json("1")["b"]!!.jsonPrimitive.int)
        assertFalse("text 不是指针输入", RemoteInput.Text("x").isPointer)
        // 移动节流 60 Hz。
        val th = RemoteMoveThrottle()
        assertTrue("第一条照发", th.allow(0.0))
        assertFalse("5ms 后的那条被合并", th.allow(0.005))
        assertTrue("过了 1/60 秒照发", th.allow(0.02))
        assertTrue("收尾那一下强制发", th.allow(0.021, force = true))
        // 长按后拖。
        val lp = RemoteLongPressDrag()
        lp.began(p, pt(100.0, 100.0))
        assertEquals("手抖不算拖", emptyList<RemoteInput>(), lp.moved(pt(0.26, 0.75), pt(103.0, 100.0)))
        assertEquals("长按松手没动 = 右键，点在长按的位置", listOf(RemoteInput.Click(p, RemoteButton.Right, 1)), lp.ended(p))
        lp.began(p, pt(100.0, 100.0))
        val q = pt(0.4, 0.75)
        assertEquals("长按后拖：先在长按的点按下，再移动", listOf(RemoteInput.Down(RemoteButton.Left, p), RemoteInput.Move(q)), lp.moved(q, pt(140.0, 100.0)))
        val r = pt(0.5, 0.6)
        assertEquals("拖着继续动只发 move", listOf(RemoteInput.Move(r)), lp.moved(r, pt(180.0, 90.0)))
        assertEquals("拖完抬起在最后的位置", listOf(RemoteInput.Up(RemoteButton.Left, r)), lp.ended(r))
        assertEquals("收过尾不再发", emptyList<RemoteInput>(), lp.ended(r))
        lp.began(p, pt(0.0, 0.0))
        lp.moved(q, pt(50.0, 0.0))
        assertEquals("拖着被系统取消：补一个抬起", listOf(RemoteInput.Up(RemoteButton.Left, p)), lp.cancelled())
        lp.began(p, pt(0.0, 0.0))
        assertEquals("没拖就被取消：什么都不发（不发右键）", emptyList<RemoteInput>(), lp.cancelled())
    }

    // MARK: 修饰键与键位

    @Test fun modifiersAndKeys() {
        val m = RemoteModifiers()
        m.tap("shift"); m.tap("cmd")
        assertEquals("按固定顺序列出（cmd alt ctrl shift）", listOf("cmd", "shift"), m.active)
        m.consumeOnce()
        assertEquals("只管一次的发完就松", emptyList<String>(), m.active)
        m.tap("cmd"); m.tap("cmd")
        assertEquals("点两下 = 锁定", RemoteModifiers.Level.Locked, m.level("cmd"))
        m.consumeOnce()
        assertEquals("锁定的发完键还按着", listOf("cmd"), m.active)
        m.tap("cmd")
        assertEquals("第三下松开", RemoteModifiers.Level.Off, m.level("cmd"))
        m.tap("fn")
        assertEquals("认不得的修饰键不进状态", emptyList<String>(), m.active)

        assertEquals("小写字母", "KeyA", RemoteKeys.code("a")?.code)
        assertEquals("大写字母要 shift", true, RemoteKeys.code("A")?.shift)
        assertEquals("数字", "Digit7", RemoteKeys.code("7")?.code)
        assertEquals("问号是 Shift+/", "Slash", RemoteKeys.code("?")?.code)
        assertEquals("问号要 shift", true, RemoteKeys.code("?")?.shift)
        assertEquals("换行 = Enter", "Enter", RemoteKeys.code("\n")?.code)
        assertEquals("空格", "Space", RemoteKeys.code(" ")?.code)
        assertNull("中文不走 key", RemoteKeys.code("中"))
        assertNull("全角标点不走 key", RemoteKeys.code("，"))
        assertEquals("Del = Mac 的 delete = Backspace", "Backspace", RemoteKeys.special["del"])
        assertEquals("Esc", "Escape", RemoteKeys.special["esc"])

        fun k(c: String, d: Boolean, mods: List<String>) = RemoteInput.Key(c, d, mods)
        assertEquals("ASCII 逐键按下抬起", listOf(k("KeyA", true, listOf()), k("KeyA", false, listOf())), RemoteKeys.inputs("a", listOf()))
        assertEquals("⌘C", listOf(k("KeyC", true, listOf("cmd")), k("KeyC", false, listOf("cmd"))), RemoteKeys.inputs("c", listOf("cmd")))
        assertEquals("已经按着 shift 的大写不重复加 shift",
            listOf(k("KeyA", true, listOf("shift")), k("KeyA", false, listOf("shift"))), RemoteKeys.inputs("A", listOf("shift")))
        assertEquals("⌘ + 大写：补 shift，按固定顺序",
            listOf(k("KeyA", true, listOf("cmd", "shift")), k("KeyA", false, listOf("cmd", "shift"))), RemoteKeys.inputs("A", listOf("cmd")))
        assertEquals("连续的中文合成一条 text", listOf(RemoteInput.Text("你好")), RemoteKeys.inputs("你好", listOf()))
        assertEquals("中英混排：ASCII 走 key、其余走 text，顺序不乱",
            listOf(k("KeyA", true, listOf()), k("KeyA", false, listOf()), RemoteInput.Text("你"), k("KeyB", true, listOf()), k("KeyB", false, listOf())),
            RemoteKeys.inputs("a你b", listOf()))
        assertEquals("emoji 即使按着 ⌘ 也走 text", listOf(RemoteInput.Text("😀")), RemoteKeys.inputs("😀", listOf("cmd")))
    }

    // MARK: 安全短码

    @Test fun safetyCode() {
        val sdpA = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=fingerprint:SHA-256 ab:cd:ef:01\r\na=setup:actpass\r\n"
        val sdpB = "v=0\na=group:BUNDLE 0\na=fingerprint:sha-256 11:22:33:44\na=fingerprint:sha-256 99:99\n"
        assertEquals("指纹：算法小写、十六进制大写", "sha-256 AB:CD:EF:01", RemoteSafetyCode.fingerprint(sdpA))
        assertEquals("多行指纹取第一行", "sha-256 11:22:33:44", RemoteSafetyCode.fingerprint(sdpB))
        assertNull("没有指纹回 null", RemoteSafetyCode.fingerprint("v=0\n"))
        assertNull("指纹行残缺回 null", RemoteSafetyCode.fingerprint("a=fingerprint:sha-256\n"))
        val a = RemoteSafetyCode.fingerprint(sdpA)!!
        val b = RemoteSafetyCode.fingerprint(sdpB)!!
        assertEquals("两端调换位置短码相同", RemoteSafetyCode.code(a, b), RemoteSafetyCode.code(b, a))
        assertEquals("从 SDP 直接算 = 先取指纹再算", RemoteSafetyCode.code(a, b), RemoteSafetyCode.codeFromSdp(sdpA, sdpB))
        // 已知答案：与 python hashlib 独立计算一致（iOS 用例同一个数）。
        assertEquals("已知答案", "1123", RemoteSafetyCode.code(a, b))
        assertEquals("短码恒为 4 位（补零）", 4, RemoteSafetyCode.code("x", "y").length)
        assertNull("一边没指纹不显示假短码", RemoteSafetyCode.codeFromSdp(sdpA, "v=0"))
        assertNotEquals("指纹差一位短码就变", RemoteSafetyCode.code(a, b), RemoteSafetyCode.code(a, "sha-256 11:22:33:45"))
    }

    // MARK: 快照降级 / 画质

    @Test fun fallback() {
        assertFalse("看一眼不建 WebRTC", RemoteFallback.usesWebRTC("snapshot"))
        assertTrue("普通会话建 WebRTC", RemoteFallback.usesWebRTC("manual"))
        assertFalse("15 秒内不降", RemoteFallback.shouldFallback("manual", 100.0, false, 114.9))
        assertTrue("15 秒没连上降快照", RemoteFallback.shouldFallback("manual", 100.0, false, 115.0))
        assertFalse("连上了不降", RemoteFallback.shouldFallback("manual", 100.0, true, 200.0))
        assertFalse("还在等同意不计时", RemoteFallback.shouldFallback("manual", null, false, 999.0))
        assertFalse("看一眼本来就是快照，不谈降级", RemoteFallback.shouldFallback("snapshot", 0.0, false, 999.0))
        assertEquals("蜂窝默认省流", "saver", RemoteFallback.defaultQuality(true))
        assertEquals("Wi-Fi 默认自动", "auto", RemoteFallback.defaultQuality(false))
        assertEquals("四档画质与契约同名", listOf("auto", "hd", "smooth", "saver"), RemoteFallback.qualityLevels)
        near(RemoteFallback.SNAPSHOT_INTERVAL, 2.0, "快照每 2 秒一张")
    }

    // MARK: 文案 key

    @Test fun copyKeys() {
        assertEquals("结束原因认得的照名", "remote.end.by_host", RemoteCopy.endReasonKey("by_host"))
        assertEquals("认不得的落兜底", "remote.end.error", RemoteCopy.endReasonKey("new_reason_from_future"))
        assertEquals("没给原因落兜底", "remote.end.error", RemoteCopy.endReasonKey(null))
        for (r in listOf("by_controller", "by_host", "declined", "no_answer", "host_offline", "controller_gone", "revoked",
            "signed_out", "no_membership", "idle", "error", "no_permission", "replaced")) {
            assertTrue("服务端结束原因 $r 有文案", r in RemoteCopy.endReasons)
        }
        for (c in listOf("offline", "remote_off", "no_permission", "need_membership", "busy", "bad_request", "locate_gone")) {
            assertEquals("服务端拒绝码 $c 有文案", "remote.err.$c", RemoteCopy.errorKey(c))
        }
        assertEquals("认不得的拒绝码落兜底", "remote.err.bad_request", RemoteCopy.errorKey("zzz"))
        // 批次 264 §五：对方忙分两种「没连上」。
        val C = RemoteCopy
        val close = RemoteCopy.EndButton.Close
        val retry = RemoteCopy.EndButton.Retry
        val toView = RemoteCopy.EndButton.ToView
        assertEquals("可控被占：关闭 + 改成只看", listOf(close, toView), C.endButtons("busy", "control", null))
        assertEquals("只看已满：关闭 + 重新连接", listOf(close, retry), C.endButtons("busy", "view", null))
        assertEquals("老服务端没带 busy_kind：按只看已满给", listOf(close, retry), C.endButtons("busy", null, null))
        assertEquals("可控被占那句", "remote.busy.control", C.busyKey("busy", "control"))
        assertEquals("没带 busy_kind 的那句", "remote.busy.view", C.busyKey("busy", null))
        assertNull("不是 busy 不走这两句", C.busyKey("offline", null))
        assertEquals("要会员：重来也是同一句，只给关闭", listOf(close), C.endButtons("need_membership", null, null))
        assertEquals("没连上服务器可以重来", listOf(close, retry), C.endButtons("not_connected", null, null))
        assertEquals("对方睡眠：只有关闭", listOf(close), C.endButtons(null, null, "host_sleep"))
        assertEquals("关掉远程访问：只有关闭", listOf(close), C.endButtons(null, null, "access_off"))
        assertEquals("PIN 锁定：只有关闭", listOf(close), C.endButtons(null, null, "pin_locked"))
        assertEquals("被顶替：只有关闭", listOf(close), C.endButtons(null, null, "replaced"))
        assertEquals("空闲结束：可以重新连接", listOf(close, retry), C.endButtons(null, null, "idle"))
        // 被控端的 bye（PC logic.ts › byeOf）+ 服务端原因 → 结束卡那一句。
        fun copy(server: String?, bye: RemoteBye?, consent: Boolean = false): String {
            val c = C.endCopy(server, bye, consent)
            return c.key + c.args.joinToString("") { a ->
                when (a) { is RemoteCopy.EndArg.Text -> "|" + a.text; is RemoteCopy.EndArg.Time -> "|@${a.ms}" }
            }
        }
        assertEquals("hotkey：Mac 画符号，按 ⌃⌥⇧⌘ 排", "remote.end.shortcut|⌃⌥⌘R",
            copy("by_host", RemoteBye("hotkey", key = "Control+Alt+Command+R", mac = true)))
        assertEquals("hotkey：非 Mac 加号连", "remote.end.shortcut|Win+Ctrl+Alt+R",
            copy("by_host", RemoteBye("hotkey", key = "Control+Alt+Command+R", mac = false)))
        assertEquals("hotkey 没带键：写缺省", "remote.end.shortcut|⌃⌥⌘R", copy("by_host", RemoteBye("hotkey")))
        assertEquals("idle：N 用 bye.min", "remote.end.idle|45", copy("by_host", RemoteBye("idle", min = 45)))
        assertEquals("没收到 bye 的 idle：退回缺省 30", "remote.end.idle|30", copy("idle", null))
        assertEquals("关掉了远程访问", "remote.end.access_off", copy("by_host", RemoteBye("access_off")))
        assertEquals("PIN 锁定：锁到几点用 until_ms", "remote.end.pin_locked|@1792110600000",
            copy("by_host", RemoteBye("pin_locked", untilMs = 1_792_110_600_000)))
        assertEquals("PIN 锁定没带时刻：退回 by_host", "remote.end.by_host", copy("by_host", RemoteBye("pin_locked")))
        assertEquals("bye error：照 by_host", "remote.end.by_host", copy("by_host", RemoteBye("error")))
        assertEquals("bye peer：照 by_host", "remote.end.by_host", copy("by_host", RemoteBye("peer")))
        assertEquals("没收到 bye：原逻辑", "remote.end.by_host", copy("by_host", null))
        assertEquals("控制端自己结束的：bye 不改写", "remote.end.by_controller", copy("by_controller", RemoteBye("hotkey")))
        assertEquals("同意阶段 no_permission = 对方去开权限了", "remote.end.granting", copy("no_permission", null, consent = true))
        assertEquals("会话中途的 no_permission 照旧", "remote.end.no_permission", copy("no_permission", null, consent = false))
        assertEquals("钮也跟着推出来的原因走", "access_off", C.effectiveEndReason("by_host", RemoteBye("access_off"), false))
        assertEquals("关掉了远程访问：只有关闭", listOf(close),
            C.endButtons(null, null, C.effectiveEndReason("by_host", RemoteBye("access_off"), false)))
        assertEquals("bye idle 解析", RemoteHostMessage.Bye(RemoteBye("idle", min = 30)), RemoteHostMessage.parse("""{"t":"bye","why":"idle","min":30}"""))
        assertEquals("bye hotkey 解析", RemoteHostMessage.Bye(RemoteBye("hotkey", key = "Control+Alt+Command+R", mac = false)),
            RemoteHostMessage.parse("""{"t":"bye","why":"hotkey","key":"Control+Alt+Command+R","mac":false}"""))
        assertEquals("bye pin_locked 解析", RemoteHostMessage.Bye(RemoteBye("pin_locked", untilMs = 1_792_110_600_000)),
            RemoteHostMessage.parse("""{"t":"bye","why":"pin_locked","until_ms":1792110600000}"""))
        assertNull("bye 没 why：丢掉", RemoteHostMessage.parse("""{"t":"bye"}"""))
        assertEquals("accelerator 别名与 Mac 主键符号", "⇧⌘↩", RemoteKeys.accelText("CmdOrCtrl+Shift+Return", true))
        assertEquals("同意卡刚弹出：还剩 10 秒", 10, RemoteFallback.consentLeft(100.0, 100.0))
        assertEquals("过了 3.7 秒：还剩 7 秒（向下取整的已过秒数）", 7, RemoteFallback.consentLeft(100.0, 103.7))
        assertEquals("过了点：停在 0，不出负数", 0, RemoteFallback.consentLeft(100.0, 130.0))
        assertEquals("控制时空闲降档缺省 15 分钟（批次 264）", 15, RemoteFallback.CONTROL_IDLE_MIN)
        assertEquals("只看时空闲结束缺省 30 分钟（批次 264）", 30, RemoteFallback.VIEW_IDLE_MIN)
        assertEquals("升档被拒", "remote.notice.upgrade_declined", RemoteCopy.noticeKey("upgrade_declined"))
        assertNull("认不得的提示码不提示", RemoteCopy.noticeKey("zzz"))
        assertEquals("黑名单拒绝", "remote.denied.blacklist", RemoteCopy.deniedKey("blacklist"))
        assertEquals("缺屏幕录制权限", "remote.gate.no_screen_permission", RemoteCopy.unavailableKey("no_screen_permission"))
        assertEquals("认不得的不可用原因按没开说", "remote.gate.remote_off", RemoteCopy.unavailableKey("zzz"))
    }

    /**
     * 每个拼出来的 key 都要在 strings_ios.xml 里、中英两份都有值（iOS 比 xcstrings 的那一组）——
     * 这些 key 是运行时拼的，`R.string.x` 的编译期检查看不到它们，缺了界面上就是一句空话或崩溃。
     */
    @Test fun copyKeysHaveStrings() {
        val keys = mutableListOf<String>()
        keys += RemoteCopy.endReasons.map(RemoteCopy::endReasonKey)
        keys += RemoteCopy.errorCodes.map(RemoteCopy::errorKey)
        keys += RemoteCopy.notices.mapNotNull(RemoteCopy::noticeKey)
        keys += RemoteCopy.deniedReasons.map(RemoteCopy::deniedKey)
        keys += RemoteCopy.unavailableReasons.map(RemoteCopy::unavailableKey)
        keys += RemoteCopy.gateKeys
        keys += RemoteFallback.qualityLevels.map(RemoteCopy::qualityKey)
        keys += RemoteModifiers.ORDER.map(RemoteCopy::modKey)
        for (dir in listOf("values", "values-en")) {
            val f = listOf(File("src/main/res/$dir/strings_ios.xml"), File("app/src/main/res/$dir/strings_ios.xml")).firstOrNull { it.exists() }
            assertTrue("读不到 $dir/strings_ios.xml —— 文案 key 这一组没核对", f != null)
            val xml = f!!.readText()
            for (k in keys.toSortedSet()) {
                val m = Regex("<string name=\"${Regex.escape(RemoteCopy.resName(k))}\">([^<]+)</string>").find(xml)
                assertTrue("$dir 里 $k 有值", m != null && m.groupValues[1].isNotBlank())
            }
        }
    }

    // MARK: 入口能不能点

    @Test fun gate() {
        val ok = RemoteManifest(available = true, skills = listOf("snapshot"), control = true)
        assertEquals("离线先说离线", RemoteGate.Disabled("remote.gate.offline", null), RemoteEntryRules.gate(RemoteEntry.View, false, ok, true))
        assertEquals("没上报 remote：没开 / 版本旧", RemoteGate.Disabled("remote.gate.no_remote", null), RemoteEntryRules.gate(RemoteEntry.View, true, null, true))
        val off = RemoteManifest(available = false, reason = "no_screen_permission", text = "")
        assertEquals("缺屏幕录制权限", RemoteGate.Disabled("remote.gate.no_screen_permission", null), RemoteEntryRules.gate(RemoteEntry.View, true, off, true))
        val offText = RemoteManifest(available = false, reason = "platform", text = " Windows 还不支持 ")
        assertEquals("被控端给了那句话就照抄", RemoteGate.Disabled("remote.gate.platform", "Windows 还不支持"), RemoteEntryRules.gate(RemoteEntry.View, true, offText, true))
        val viewOnly = RemoteManifest(available = true, control = false)
        assertEquals("没辅助功能权限只能看", RemoteGate.Disabled("remote.gate.no_control", null), RemoteEntryRules.gate(RemoteEntry.Control, true, viewOnly, true))
        assertEquals("只能看时「远程查看」照常", RemoteGate.Ok, RemoteEntryRules.gate(RemoteEntry.View, true, viewOnly, true))
        val unknown = RemoteManifest(available = true)
        assertEquals("服务端没透传 features：不在本地挡", RemoteGate.Ok, RemoteEntryRules.gate(RemoteEntry.Control, true, unknown, true))
        val noSnap = RemoteManifest(available = true, skills = listOf("other"))
        assertEquals("没有 snapshot 技能不能看一眼", RemoteGate.Disabled("remote.gate.no_snapshot", null), RemoteEntryRules.gate(RemoteEntry.Peek, true, noSnap, true))
        assertEquals("非会员：跳会员页", RemoteGate.NeedMembership, RemoteEntryRules.gate(RemoteEntry.View, true, ok, false))
        assertEquals("对方没开时先说对方，不先让人去买会员", RemoteGate.Disabled("remote.gate.no_screen_permission", null), RemoteEntryRules.gate(RemoteEntry.View, true, off, false))
        assertEquals("会员还没问到：不挡", RemoteGate.Ok, RemoteEntryRules.gate(RemoteEntry.View, true, ok, null))
        // 批次 264 §四：看一眼也要会员 —— 三颗全灰，原因在整台那一层。
        assertEquals("看一眼也校验会员", RemoteGate.NeedMembership, RemoteEntryRules.gate(RemoteEntry.Peek, true, ok, false))
        assertEquals("整台：非会员三颗全灰", RemoteGate.NeedMembership, RemoteEntryRules.deviceGate(true, ok, false))
        assertEquals("非会员又缺辅助功能：先说会员（全灰），不只灰控制那一颗", RemoteGate.NeedMembership,
            RemoteEntryRules.gate(RemoteEntry.Control, true, viewOnly, false))
        assertEquals("缺辅助功能不算整台连不了（只灰「远程控制」）", RemoteGate.Ok, RemoteEntryRules.deviceGate(true, viewOnly, true))
        assertEquals("离线先于会员", RemoteGate.Disabled("remote.gate.offline", null), RemoteEntryRules.deviceGate(false, ok, false))
        assertEquals("离线那句带设备名", listOf("remote.gate.offline"), RemoteCopy.gateKeysWithName)
        assertEquals("看一眼 = source snapshot", "snapshot", RemoteEntryRules.request(RemoteEntry.Peek).source)
        assertEquals("看一眼 = 只看", "view", RemoteEntryRules.request(RemoteEntry.Peek).mode)
        assertEquals("远程控制 = control", "control", RemoteEntryRules.request(RemoteEntry.Control).mode)
        // Android 桩 API 只给 mode / source：反推入口（iOS 没有这一步）。
        assertEquals("source snapshot → 看一眼", RemoteEntry.Peek, RemoteEntryRules.entry("view", "snapshot"))
        assertEquals("control → 远程控制", RemoteEntry.Control, RemoteEntryRules.entry("control", "locate"))
        assertEquals("view task → 远程查看", RemoteEntry.View, RemoteEntryRules.entry("view", "task"))
    }

    // MARK: 信令

    @Test fun signal() {
        val req = RemoteSignal.request("r1", "d1", "control", "locate", "run1", "ask1", "c1", "sam 的手机")
        for ((k, v) in listOf("type" to "rc_request", "request_id" to "r1", "device_id" to "d1", "mode" to "control",
            "source" to "locate", "run_id" to "run1", "ask_id" to "ask1", "client_id" to "c1",
            "client_name" to "sam 的手机", "client_kind" to "android")) {
            assertEquals("rc_request.$k", v, req[k]?.jsonPrimitive?.content)
        }
        val manual = RemoteSignal.request("r", "d", "view", "manual", clientId = "c", clientName = "n")
        assertTrue("没有 run / ask 时不带这两个键", manual["run_id"] == null && manual["ask_id"] == null)
        val ice = RemoteSignal.ice("s", "candidate:1 1 udp 2122260223 192.168.1.2 5000 typ host generation 0 ufrag Ab12 network-id 1", "0", 0)
        assertEquals("rc_ice 从 candidate 串里取 ufrag", "Ab12", ice["usernameFragment"]?.jsonPrimitive?.content)
        assertEquals("rc_ice.sdpMid", "0", ice["sdpMid"]?.jsonPrimitive?.content)
        assertEquals("rc_ice.sdpMLineIndex", 0, ice["sdpMLineIndex"]?.jsonPrimitive?.int)
        assertNull("取不到 ufrag 就不带", RemoteSignal.ice("s", "candidate:1 1 udp 1 1.1.1.1 1 typ host", null, 0)["usernameFragment"])
        val st = RemoteSignal.stats("s", true, "relay", 1234, 42)
        assertEquals("第一次连上带 connected", true, st["connected"]?.jsonPrimitive?.boolean)
        assertEquals("relay_bytes", 1234L, st["relay_bytes"]?.jsonPrimitive?.long)
        assertEquals("rtt_ms", 42, st["rtt_ms"]?.jsonPrimitive?.int)
        assertNull("之后的统计不带 connected", RemoteSignal.stats("s", false, "direct", -5, null)["connected"])
        assertEquals("relay_bytes 不发负数", 0L, RemoteSignal.stats("s", false, "direct", -5, null)["relay_bytes"]?.jsonPrimitive?.long)
        assertEquals("rc_answer", "rc_answer", RemoteSignal.answer("s", "x")["type"]?.jsonPrimitive?.content)
        assertEquals("rc_handback", "rc_handback", RemoteSignal.handback("s")["type"]?.jsonPrimitive?.content)
        assertEquals("rc_attach", "rc_attach", RemoteSignal.attach("s")["type"]?.jsonPrimitive?.content)

        // 只认自己的 session_id。
        val g = RemoteSignalGate("r1", null)
        assertEquals("rc_created 之前先到的 rc_state 先攒着", V.Buffer, g.verdict("rc_state", "s1", null))
        assertEquals("别的发起的 rc_created 不认", V.Drop, g.verdict("rc_created", "s1", "r2"))
        assertEquals("自己的 rc_created 认", V.Accept, g.verdict("rc_created", "s1", "r1"))
        assertEquals("自己的 rc_error 认", V.Accept, g.verdict("rc_error", null, "r1"))
        assertEquals("别人的 rc_error 不认", V.Drop, g.verdict("rc_error", null, "rX"))
        assertEquals("服务端没开远程控制那一句（无 request_id）认", V.Accept, g.verdict("rc_error", "", null))
        g.sessionId = "s1"
        assertEquals("拿到 id 之后自己的照收", V.Accept, g.verdict("rc_state", "s1", null))
        assertEquals("别的会话的扔", V.Drop, g.verdict("rc_state", "s2", null))
        assertEquals("没有 session_id 的扔", V.Drop, g.verdict("rc_offer", null, null))
        assertEquals("已经有会话了，重复的 rc_created 不认", V.Drop, g.verdict("rc_created", "s9", "r1"))

        // rc_state 解析。
        val s = RemoteState.parse(buildJsonObject {
            put("type", "rc_state"); put("session_id", "s1"); put("state", "connecting"); put("mode", "control")
            put("unattended", true); put("started_at_ms", 1.7e12)
            putJsonArray("ice_servers") {
                add(buildJsonObject { put("urls", "stun:a:3478") })
                add(buildJsonObject {
                    put("urls", buildJsonArray { add(JsonPrimitive("turn:b")); add(JsonPrimitive("turns:b")) })
                    put("username", "u"); put("credential", "c")
                })
                add(buildJsonObject { put("urls", 5) })
            }
        })
        assertEquals("rc_state.state", "connecting", s?.state)
        assertEquals("rc_state.unattended", true, s?.unattended)
        assertEquals("started_at_ms 是 double 也收", 1_700_000_000_000L, s?.startedAtMs)
        assertEquals("ice_servers：urls 串 / 数组都收，坏的丢", 2, s?.iceServers?.size)
        assertEquals("urls 是串时包成数组", listOf("stun:a:3478"), s?.iceServers?.first()?.urls)
        assertEquals("TURN 凭证带着", "u", s?.iceServers?.last()?.username)
        assertEquals("没给 relay_allowed 当允许", true, s?.relayAllowed)
        assertNull("没有 session_id 不算", RemoteState.parse(buildJsonObject { put("type", "rc_state"); put("state", "active") }))
    }

    // MARK: DataChannel

    @Test fun hostMessages() {
        assertEquals("displays：id 数字转串、缺 id 的丢",
            RemoteHostMessage.Displays(listOf(RemoteDisplayInfo("1", "内建", true, 1512, 982), RemoteDisplayInfo("2", "外接", false, 0, 0)), "1"),
            RemoteHostMessage.parse("""{"t":"displays","items":[{"id":"1","name":"内建","primary":true,"width":1512,"height":982},{"id":2,"name":"外接"},{"name":"坏"}],"current":"1"}"""))
        assertEquals("clip", RemoteHostMessage.Clip("abc"), RemoteHostMessage.parse("""{"t":"clip","text":"abc"}"""))
        assertNull("clip 缺 text 丢", RemoteHostMessage.parse("""{"t":"clip"}"""))
        assertEquals("need_pin", RemoteHostMessage.NeedPin, RemoteHostMessage.parse("""{"t":"need_pin"}"""))
        assertEquals("pin_result 带锁定到何时", RemoteHostMessage.PinResult(false, 1_700_000_000_000),
            RemoteHostMessage.parse("""{"t":"pin_result","ok":false,"locked_until_ms":1700000000000}"""))
        assertNull("ok 不是 bool 丢", RemoteHostMessage.parse("""{"t":"pin_result","ok":"yes"}"""))
        assertEquals("pin_result 带还能试几次", RemoteHostMessage.PinResult(false, null, 3),
            RemoteHostMessage.parse("""{"t":"pin_result","ok":false,"left":3}"""))
        assertEquals("PIN 点：至少 6 个、最多 8 个", listOf(6, 6, 7, 8),
            listOf(RemoteCommand.pinDots(0), RemoteCommand.pinDots(6), RemoteCommand.pinDots(7), RemoteCommand.pinDots(12)))
        assertEquals("满 8 位不再收", "12345678", RemoteCommand.pinTyped("12345678", "9"))
        assertEquals("⌫ 删一位", "1", RemoteCommand.pinTyped("12", "⌫"))
        assertEquals("非数字不收", "12", RemoteCommand.pinTyped("12", "a"))
        assertEquals("空的再删还是空", "", RemoteCommand.pinTyped("", "⌫"))
        assertEquals("protected", RemoteHostMessage.Protected("1Password"), RemoteHostMessage.parse("""{"t":"protected","app":"1Password"}"""))
        assertEquals("denied", RemoteHostMessage.Denied("blacklist", "1Password"), RemoteHostMessage.parse("""{"t":"denied","reason":"blacklist","app":"1Password"}"""))
        assertEquals("pong", RemoteHostMessage.Pong(12.5), RemoteHostMessage.parse("""{"t":"pong","ts":12.5}"""))
        assertEquals("files：坏项丢", RemoteHostMessage.Files("desktop", listOf(RemoteFileItem("a.txt", 12, 5))),
            RemoteHostMessage.parse("""{"t":"files","dir":"desktop","items":[{"name":"a.txt","size":12,"mtime_ms":5},{"name":"","size":1},{"name":"b","size":-1}]}"""))
        assertEquals("file_begin", RemoteHostMessage.FileBegin("android-1", "a.txt", 70000), RemoteHostMessage.parse("""{"t":"file_begin","id":"android-1","name":"a.txt","size":70000}"""))
        assertEquals("file_end", RemoteHostMessage.FileEnd("android-1", false, "gone"), RemoteHostMessage.parse("""{"t":"file_end","id":"android-1","ok":false,"error":"gone"}"""))
        assertEquals("file_put_result", RemoteHostMessage.FilePutResult("android-2", true, null), RemoteHostMessage.parse("""{"t":"file_put_result","id":"android-2","ok":true}"""))
        assertEquals("idle_downgrade（今天的 PC 不带分钟数）", RemoteHostMessage.IdleDowngrade(null), RemoteHostMessage.parse("""{"t":"idle_downgrade"}"""))
        assertEquals("idle_downgrade 带了 min 就照它", RemoteHostMessage.IdleDowngrade(20), RemoteHostMessage.parse("""{"t":"idle_downgrade","min":20}"""))
        assertEquals("min = 0 当没带（0 = 不限，不会降档）", RemoteHostMessage.IdleDowngrade(null), RemoteHostMessage.parse("""{"t":"idle_downgrade","min":0}"""))
        assertNull("认不得的新消息丢、不崩", RemoteHostMessage.parse("""{"t":"brand_new_thing"}"""))
        assertNull("不是 JSON 丢", RemoteHostMessage.parse("not json"))
        assertNull("顶层不是对象丢", RemoteHostMessage.parse("""["t"]"""))

        assertNull("从头拉不带 offset", RemoteCommand.fileGet("i", "downloads", "a", 0)["offset"])
        assertEquals("续传带 offset", 65536L, RemoteCommand.fileGet("i", "downloads", "a", 65536)["offset"]?.jsonPrimitive?.long)
        assertEquals("quality.level", "saver", RemoteCommand.quality("saver")["level"]?.jsonPrimitive?.content)
        assertEquals("display.id", "2", RemoteCommand.display("2")["id"]?.jsonPrimitive?.content)
        assertTrue("6 位 PIN", RemoteCommand.validPin("123456"))
        assertTrue("8 位 PIN", RemoteCommand.validPin("12345678"))
        assertFalse("5 位不发", RemoteCommand.validPin("12345"))
        assertFalse("9 位不发", RemoteCommand.validPin("123456789"))
        assertFalse("带字母不发", RemoteCommand.validPin("12345a"))
        assertFalse("全角数字不发", RemoteCommand.validPin("１２３４５６"))
    }

    // MARK: file 通道

    @Test fun fileCodec() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val enc = RemoteFileCodec.encode("android-1", payload)!!
        assertEquals("头 4 字节是大端 id 长度", listOf<Byte>(0, 0, 0, 9), enc.take(4))
        assertEquals("接着是 id", "android-1", String(enc.copyOfRange(4, 13), Charsets.UTF_8))
        val dec = RemoteFileCodec.decode(enc)
        assertEquals("解回 id", "android-1", dec?.first)
        assertEquals("解回数据", payload.toList(), dec?.second?.toList())
        val zh = RemoteFileCodec.encode("文件", ByteArray(0))!!
        assertEquals("id 长度按 UTF-8 字节算，不按字符数", listOf<Byte>(0, 0, 0, 6), zh.take(4))
        assertEquals("中文 id 往返", "文件", RemoteFileCodec.decode(zh)?.first)
        assertNull("头不完整丢", RemoteFileCodec.decode(byteArrayOf(0, 0)))
        assertNull("id 长度越界丢", RemoteFileCodec.decode(byteArrayOf(0, 0, 0, 9, 65)))
        assertNull("id 长度为 0 丢", RemoteFileCodec.decode(byteArrayOf(0, 0, 0, 0, 65)))
        assertNull("一块超过 64 KB 不编", RemoteFileCodec.encode("x", ByteArray(64 * 1024 + 1)))
        assertEquals("正好 64 KB 可以", 64 * 1024 + 5, RemoteFileCodec.encode("x", ByteArray(64 * 1024))?.size)
        assertEquals("切块：最后一块是零头", listOf(RemoteSpan(0, 65_536), RemoteSpan(65_536, 131_072), RemoteSpan(131_072, 150_000)),
            RemoteFileCodec.ranges(150_000, chunk = 65_536))
        assertEquals("续传从 offset 开始", listOf(RemoteSpan(131_072, 150_000)), RemoteFileCodec.ranges(150_000, 131_072, 65_536))
        assertEquals("空文件没有块", emptyList<RemoteSpan>(), RemoteFileCodec.ranges(0))
        assertEquals("中继 100 MB", 100L * 1024 * 1024, RemoteFileCodec.limit(true))
        assertEquals("直连 500 MB", 500L * 1024 * 1024, RemoteFileCodec.limit(false))
        assertEquals("传输 id 带端前缀", "android-3", RemoteFileCodec.makeId(3))
    }

    // MARK: 统计

    @Test fun stats() {
        val relay = mapOf(
            "T" to RemoteStatEntry("transport", mapOf("selectedCandidatePairId" to "P")),
            "P" to RemoteStatEntry("candidate-pair", mapOf("localCandidateId" to "L", "remoteCandidateId" to "R",
                "bytesSent" to java.math.BigInteger.valueOf(100), "bytesReceived" to java.math.BigInteger.valueOf(900),
                "currentRoundTripTime" to 0.0425, "state" to "succeeded", "nominated" to true)),
            "L" to RemoteStatEntry("local-candidate", mapOf("candidateType" to "relay")),
            "R" to RemoteStatEntry("remote-candidate", mapOf("candidateType" to "srflx")),
            "V" to RemoteStatEntry("inbound-rtp", mapOf("kind" to "video", "framesPerSecond" to 24.0)),
        )
        val s = RemoteStatsRules.summarize(relay)
        assertEquals("本地是 relay → 中继", "relay", s?.path)
        assertEquals("中继字节 = 那一对的收 + 发", 1000L, s?.relayBytes)
        assertEquals("RTT 秒 → 毫秒四舍五入", 43, s?.rttMs)
        assertEquals("帧率取视频 inbound-rtp", 24.0, s?.fps)
        val direct = relay.toMutableMap().apply { put("L", RemoteStatEntry("local-candidate", mapOf("candidateType" to "host"))) }
        assertEquals("两端都不是 relay → 直连", "direct", RemoteStatsRules.summarize(direct)?.path)
        assertEquals("直连不报中继字节", 0L, RemoteStatsRules.summarize(direct)?.relayBytes)
        val noTransport = relay.toMutableMap().apply { remove("T") }
        assertEquals("没有 transport 时按 succeeded + nominated 找", "relay", RemoteStatsRules.summarize(noTransport)?.path)
        assertNull("还没选中的一对：不报", RemoteStatsRules.summarize(mapOf("P" to RemoteStatEntry("candidate-pair", mapOf("state" to "in-progress")))))
        assertEquals("对端是 relay 也是中继", "relay", RemoteStatsRules.path("srflx", "relay"))
    }

    // MARK: HTTP 回包

    @Test fun http() {
        val snap = RemoteSnapshot.parse(buildJsonObject { put("url", "/files/abc"); put("width", 1440); put("height", 900); put("display", 1); put("at_ms", 5) })
        assertEquals("快照 display 数字转串", "1", snap?.display)
        assertEquals("快照宽", 1440, snap?.width)
        assertNull("没有 url 不算", RemoteSnapshot.parse(buildJsonObject { put("width", 1) }))
        val list = RemoteSessionRecord.parseList(buildJsonObject {
            putJsonArray("items") {
                add(buildJsonObject {
                    put("id", "rc-1"); put("host_name", "Mac"); put("mode", "view")
                    putJsonArray("mode_log") {
                        add(buildJsonObject { put("mode", "control"); put("at_ms", 1) })
                        add(buildJsonObject { put("mode", "view"); put("at_ms", 2) })
                    }
                    put("path", "relay"); put("relay_bytes", 2048); put("state", "ended"); put("end_reason", "by_host")
                    put("started_at_ms", 1000); put("ended_at_ms", 61_000)
                    putJsonArray("files") { add(buildJsonObject { put("name", "a") }) }
                })
                add(buildJsonObject { put("host_name", "坏行没有 id"); put("started_at_ms", 1) })
            }
        })
        assertEquals("审计列表：坏行丢", 1, list?.size)
        assertEquals("控过又降回只看的也算控过", true, list?.first()?.everControlled)
        assertEquals("时长按结束时刻算", 60L, list?.first()?.durationSec(0))
        assertEquals("文件数", 1, list?.first()?.fileCount)
        assertNull("读不懂 ≠ 没有：回 null", RemoteSessionRecord.parseList(buildJsonObject { put("nope", 1) }))
        val ongoing = RemoteSessionRecord.parseList(buildJsonObject {
            putJsonArray("items") { add(buildJsonObject { put("id", "x"); put("started_at_ms", 0) }) }
        })?.first()
        assertEquals("没结束的按现在算", 90L, ongoing?.durationSec(90_000))
        assertEquals("时长拆小时", 3L, RemoteSessionRecord.durationParts(3 * 3600 + 25 * 60L).second)
        assertEquals("时长拆分钟", 25L, RemoteSessionRecord.durationParts(3 * 3600 + 25 * 60L).first)
        val relay = RemoteRelayStatus.parse(buildJsonObject {
            put("used_bytes", 2L * 1024 * 1024 * 1024); put("limit_bytes", 8L * 1024 * 1024 * 1024); put("month", "2026-10"); put("turn_configured", true)
        })
        near(relay?.fraction ?: -1.0, 0.25, "中继用量四分之一")
        near(RemoteRelayStatus(5, 0, "", false).fraction, 1.0, "额度 0 按用满")
        near(RemoteRelayStatus(50, 10, "", false).fraction, 1.0, "超额封在 1")
        assertNull("缺字节数不算", RemoteRelayStatus.parse(buildJsonObject { put("month", "x") }))
    }

    // MARK: 落盘名（iOS UmbraShareName.base + RemoteClient.localName）

    @Test fun localFileName() {
        assertEquals("../ 逃不出去、后缀接回", "etc_passwd.txt", RemoteFileName.local("../etc/passwd.txt"))
        assertEquals("开头的点去掉（不成隐藏文件）", "env", RemoteFileName.local(".env"))
        assertEquals("冒号与控制字符去掉", "ab.pdf", RemoteFileName.local("a:\u0001b.pdf"))
        assertEquals("洗不出字时兜底", "file", RemoteFileName.local("///"))
        assertEquals("中文名照留", "报告.docx", RemoteFileName.local("报告.docx"))
    }
}
