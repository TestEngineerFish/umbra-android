package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 敏感剪贴板判据 —— 逐条翻译 iOS `Tools/swifttests/SensitiveClipTests.swift`，外加 iOS `PhraseVarsTests.swift`
 * 里 `SensitiveClip.holds` 那一组（iOS 把它放在那边，Android 的 holds 在这个对象上，就近放这里）。
 *
 * 平台差异：Android 没有 changeCount，记录换成剪贴板时间戳 Long（Int 也认）；iOS 拿字符串 / 布尔 / 缺字段当坏记录，
 * 这里拿 String / Boolean / Map 这些非整数值当坏记录 —— 都必须抛 Unjudgeable（判不准），不许当成「不是敏感值」。
 * iOS 的 `foreignMarkerTypes`（macOS nspasteboard.org 的两个 UTI）在 Android 上没有对应物，未翻（见报告）。
 */
class SensitiveClipTest {
    /** 别家带敏感标记放进来的也按敏感；本 App 那份按敏感；两样都没有才不敏感。 */
    @Test fun foreignMarked() {
        assertEquals("带敏感标记的别家密码没被当敏感", true, SensitiveClip.sensitive(ownHeld = false, foreignMarked = true))
        assertEquals("本 App 那份不算敏感了", true, SensitiveClip.sensitive(ownHeld = true, foreignMarked = false))
        assertEquals("普通剪贴板内容被当成了敏感", false, SensitiveClip.sensitive(ownHeld = false, foreignMarked = false))
    }

    /** 输入框长按菜单：拷贝 / 剪切只在改道时给；「共享」一律不给；改值要用的几项照常给。 */
    @Test fun menuActions() {
        assertEquals("改道了还不给拷贝", true, SensitiveClip.menuActionAllowed("copy:", rerouted = true))
        assertEquals("改道了还不给剪切", true, SensitiveClip.menuActionAllowed("cut:", rerouted = true))
        assertEquals("没改道却给了拷贝（明文进通用剪贴板）", false, SensitiveClip.menuActionAllowed("copy:", rerouted = false))
        assertEquals("没改道却给了剪切（明文进通用剪贴板）", false, SensitiveClip.menuActionAllowed("cut:", rerouted = false))
        assertEquals("给了「共享…」", false, SensitiveClip.menuActionAllowed("_share:", rerouted = true))
        for (a in listOf("paste:", "select:", "selectAll:", "delete:")) {
            assertEquals("「$a」被拦了", true, SensitiveClip.menuActionAllowed(a, rerouted = false))
        }
    }

    /** 「剪贴板里还是不是保险箱那份」：时间戳没动 → 是；动过 → 不是；没记录 → 不是；Int 形状也认。 */
    @Test fun holdsByTimestamp() {
        val rec = 42L
        assertEquals("时间戳没动：还是保险箱那份", true, SensitiveClip.holds(rec, 42L))
        assertEquals("时间戳动过：已被别的内容盖掉", false, SensitiveClip.holds(rec, 43L))
        assertEquals("没有记录：从没复制过敏感值", false, SensitiveClip.holds(null, 42L))
        // iOS 这条是「UserDefaults 桥接回来的 NSNumber 也要认」；Android 对应的是存储读回来是 Int 的形状。
        assertEquals("读回来是 Int 的形状", true, SensitiveClip.holds(7, 7L))
    }

    /** 记录坏了（字符串 / 字符串数字 / 空字典 / 布尔）要判不准（抛错），不许当成「不是敏感值」。 */
    @Test fun malformedRecordThrows() {
        val bad = listOf<Pair<Any, String>>(
            "x" to "记录是个字符串",
            "42" to "计数是字符串",
            emptyMap<String, Any>() to "缺 count",
            true to "计数是布尔",
        )
        for ((rec, what) in bad) {
            var threw = false
            try { SensitiveClip.holds(rec, 42L) } catch (_: SensitiveClip.Unjudgeable) { threw = true }
            assertEquals("记录坏了（$what）要判不准（抛错），不许当成「不是敏感值」", true, threw)
        }
    }
}
