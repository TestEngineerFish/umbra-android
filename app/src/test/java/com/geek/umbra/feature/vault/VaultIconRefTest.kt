package com.geek.umbra.feature.vault

import com.geek.umbra.designsystem.UmbraIconPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 记录图标取值与保险箱图标路径表 —— 逐条翻译 iOS `Tools/swifttests/VaultIconRefTests.swift`。
 *
 * 会再坏的几样：① 路径里混进解析器不认的命令（弧 / 相对坐标没归一化），画出来少一笔；② 几何错（弧 sweep 翻了、相对当绝对）；
 * ③ 取值的五种形状认错一种（界面只是退回首字，看不出判断错了）；④ 名字和 PC 漂了。
 *
 * Android 画图标用 Compose 的 PathParser（什么命令都认），没有 iOS 那个「只认 M/L/C/Z」的 `UmbraSVGParse`；
 * 为了照样钉住「表里只有归一化后的 M/L/C/Z、坐标在 0…24 里」，把 iOS `UmbraSVGParse.parse` 原样移植成本文件里的 [SvgParse]。
 */
class VaultIconRefTest {
    /** 一条绘制指令（iOS `UmbraSVGOp`）。 */
    sealed interface SvgOp {
        data class Move(val x: Double, val y: Double) : SvgOp
        data class Line(val x: Double, val y: Double) : SvgOp
        data class Curve(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : SvgOp
        data object Close : SvgOp
    }

    /** 解析结果：指令 + 被丢掉的命令字母。 */
    data class SvgResult(val ops: List<SvgOp>, val dropped: List<Char>)

    /**
     * iOS `UmbraSVGParse.parse` 的逐行移植：只认 M / L / C / Z（绝对坐标）；**任何其它字母都是命令边界**，
     * 那一段连同参数整段丢掉、记进 dropped。指数里的 e / E 由数字扫描当场吃掉。
     */
    object SvgParse {
        fun parse(d: String): SvgResult {
            val ops = mutableListOf<SvgOp>()
            val dropped = mutableListOf<Char>()
            val nums = mutableListOf<Double>()
            var cmd: Char? = null

            fun flush() {
                val c = cmd
                if (c != null) when (c) {
                    'M' -> {
                        if (nums.size >= 2) ops += SvgOp.Move(nums[0], nums[1])
                        var k = 2
                        while (k + 1 < nums.size) { ops += SvgOp.Line(nums[k], nums[k + 1]); k += 2 }
                    }
                    'L' -> { var k = 0; while (k + 1 < nums.size) { ops += SvgOp.Line(nums[k], nums[k + 1]); k += 2 } }
                    'C' -> {
                        var k = 0
                        while (k + 5 < nums.size) {
                            ops += SvgOp.Curve(nums[k], nums[k + 1], nums[k + 2], nums[k + 3], nums[k + 4], nums[k + 5]); k += 6
                        }
                    }
                    'Z' -> ops += SvgOp.Close
                    else -> dropped += c
                }
                nums.clear()
            }

            var i = 0
            while (i < d.length) {
                val ch = d[i]
                if (ch == '-' || ch == '.' || ch in '0'..'9') {
                    var j = i
                    if (d[j] == '-') j++
                    var dot = false
                    var exp = false
                    while (j < d.length) {
                        val c = d[j]
                        if (c in '0'..'9') j++
                        else if (c == '.' && !dot && !exp) { dot = true; j++ }
                        else if ((c == 'e' || c == 'E') && !exp) {
                            exp = true; j++
                            if (j < d.length && (d[j] == '-' || d[j] == '+')) j++
                        } else break
                    }
                    d.substring(i, j).toDoubleOrNull()?.let { nums += it }
                    i = maxOf(j, i + 1)
                } else if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    flush(); cmd = ch; i++
                } else i++
            }
            flush()
            return SvgResult(ops, dropped)
        }
    }

    /** 指令的锚点（落在线上的点，不含贝塞尔控制点）。 */
    private fun anchors(ops: List<SvgOp>): List<Pair<Double, Double>> = ops.mapNotNull { op ->
        when (op) {
            is SvgOp.Move -> op.x to op.y
            is SvgOp.Line -> op.x to op.y
            is SvgOp.Curve -> op.x to op.y
            SvgOp.Close -> null
        }
    }

    /** 指令里所有坐标点（含控制点）。 */
    private fun points(ops: List<SvgOp>): List<Pair<Double, Double>> = ops.flatMap { op ->
        when (op) {
            is SvgOp.Move -> listOf(op.x to op.y)
            is SvgOp.Line -> listOf(op.x to op.y)
            is SvgOp.Curve -> listOf(op.x1 to op.y1, op.x2 to op.y2, op.x to op.y)
            SvgOp.Close -> emptyList()
        }
    }

    /** 16 个名字与 PC `VAULT_ICONS` 同序同字；每条只含 M/L/C/Z、以 M 开头、坐标在 24 的 viewBox 里；记账那批不在表里。 */
    @Test fun table() {
        assertEquals("16 个语义名与 PC VAULT_ICONS 一致", listOf("key", "credit-card", "id-card", "wallet", "membership-card", "server", "wifi",
            "cloud", "globe", "mail", "device-phone", "device-mac", "building", "shield", "lock", "folder"), VaultIconPaths.allNames)
        for (name in VaultIconPaths.allNames) {
            val d = VaultIconPaths.named(name)
            assertNotNull("allNames 里的名字 $name 查不到路径", d)
            val r = SvgParse.parse(d!!)
            assertEquals("$name 有解析器不认识的命令（没归一化？）", emptyList<Char>(), r.dropped)
            assertEquals("$name 以 M 开头", true, r.ops.firstOrNull() is SvgOp.Move)
            val inBox = points(r.ops).all { (x, y) -> x in 0.0..24.0 && y in 0.0..24.0 }
            assertEquals("$name 的坐标都在 0…24 里（相对坐标当绝对会跑出去）", true, inBox)
        }
        assertEquals("记账那一批不在表里（调用方退回首字）", null, VaultIconPaths.named("gift"))
    }

    /** 几处已知答案的几何：globe 的圆（8 段、锚点与弧中点都在圆上、顺时针往下）、lock 的圆角矩形、key 的环、wifi 的点。 */
    @Test fun geometry() {
        val globe = SvgParse.parse(VaultIconPaths.globe).ops
        val firstSub = globe.takeWhile { it != SvgOp.Close }
        assertEquals("globe 的圆是 8 段 45° 贝塞尔", 8, firstSub.count { it is SvgOp.Curve })
        val onCircle = anchors(firstSub).all { (x, y) -> abs(hypot(x - 12, y - 12) - 9) < 0.01 }
        assertEquals("globe 圆上的锚点到 (12,12) 都是 9", true, onCircle)
        // iOS 用 `if case` 取前两条；这里先断言形状，再算中点（形状不对 iOS 那两条会被静默跳过，这里直接红）。
        val m = firstSub[0]
        val c = firstSub[1]
        assertTrue("globe 第一条是 M、第二条是 C", m is SvgOp.Move && c is SvgOp.Curve)
        m as SvgOp.Move; c as SvgOp.Curve
        val mx = (m.x + 3 * c.x1 + 3 * c.x2 + c.x) / 8
        val my = (m.y + 3 * c.y1 + 3 * c.y2 + c.y) / 8
        assertEquals("globe 第一段弧的中点在圆上", true, abs(hypot(mx - 12, my - 12) - 9) < 0.01)
        assertEquals("globe 第一段从 (21,12) 顺时针往下走（sweep=1，屏幕坐标 y 向下）", true, my > 12)

        val lock = SvgParse.parse(VaultIconPaths.lock).ops
        assertEquals("lock 的圆角矩形从 (x+rx, y) 起笔", SvgOp.Move(6.0, 10.0), lock.first())
        assertEquals("lock 上边直线到 (x+w-rx, y)", true, lock.contains(SvgOp.Line(18.0, 10.0)))
        assertEquals("lock 右上角弧落在 (20,12)", true, anchors(lock).any { (x, y) -> abs(x - 20) < 0.001 && abs(y - 12) < 0.001 })

        val key = anchors(SvgParse.parse(VaultIconPaths.key).ops)
        assertEquals("key 的环经过 (7.5,20.1)", true, key.any { (x, y) -> abs(x - 7.5) < 0.001 && abs(y - 20.1) < 0.001 })
        assertEquals("wifi 底下那个点（相对 h 归一化成 L 12.01,20.4）", true,
            SvgParse.parse(VaultIconPaths.wifi).ops.contains(SvgOp.Line(12.01, 20.4)))
    }

    /** 取值的五种形状：builtin: 前缀 / 光秃秃的名字 / 老名字 user、star / base64 data URL / 其余一律首字。 */
    @Test fun resolve() {
        val r = VaultIconRef
        assertEquals("builtin: 前缀", VaultIconRef.Builtin(VaultIconPaths.key), r.resolve("builtin:key"))
        assertEquals("光秃秃的名字", VaultIconRef.Builtin(VaultIconPaths.key), r.resolve("key"))
        assertEquals("首尾空白先去掉", VaultIconRef.Builtin(VaultIconPaths.creditCard), r.resolve("  credit-card \n"))
        assertEquals("带连字符的名字", VaultIconRef.Builtin(VaultIconPaths.devicePhone), r.resolve("builtin:device-phone"))
        assertEquals("老名字 user 仍认（AutoFill 原来认它）", VaultIconRef.Builtin(UmbraIconPaths.user), r.resolve("builtin:user"))
        assertEquals("老名字 star 仍认", VaultIconRef.Builtin(UmbraIconPaths.star), r.resolve("star"))
        assertEquals("data URL（base64）→ 图", VaultIconRef.Image("AAAA"), r.resolve("data:image/png;base64,AAAA"))
        assertEquals("base64 正文为空 → 首字", VaultIconRef.Monogram, r.resolve("data:image/jpeg;base64,"))
        assertEquals("明文 data URL 不当 base64 解 → 首字", VaultIconRef.Monogram, r.resolve("data:image/svg+xml,<svg/>"))
        assertEquals("custom: 图库 → 首字（不当内置名查）", VaultIconRef.Monogram, r.resolve("custom:abc123"))
        assertEquals("custom:key 也不当成内置 key", VaultIconRef.Monogram, r.resolve("custom:key"))
        assertEquals("网址 → 首字", VaultIconRef.Monogram, r.resolve("https://example.com/a.png"))
        assertEquals("记账那批的名字 → 首字", VaultIconRef.Monogram, r.resolve("gift"))
        assertEquals("没有图标 → 首字", VaultIconRef.Monogram, r.resolve(null))
        assertEquals("空串 → 首字", VaultIconRef.Monogram, r.resolve(""))
    }
}
