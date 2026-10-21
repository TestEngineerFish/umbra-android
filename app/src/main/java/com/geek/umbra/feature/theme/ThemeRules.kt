// 主题包 `tokens.json` 的判据（主题规格 v1.1，umbra-plugins `sdk/theme-keys.json`；设计侧 themeKit 3.48.0 / 批次 267）。
//
// 逐条照抄 iOS `Features/Theme/ThemeRules.swift`（它又照搬 umbra-plugins `scripts/lib/theme.mjs`），规则编号 T1～T4 与 T7 同那边；
// PC `electron/core/shared/theme/tokens.ts`、服务端 `theme_tokens.py` 是同一套判据的另外两份。
// 那边 25 条跨语言用例（`cases.json`）和规格文件复制在 `app/src/test/resources/theme/`，`ThemeRulesTest` 逐条比
// expect / paths / pairs / warnings，并钉住这里抄的常量和复制件一致、复制件和 umbra-plugins 正本一致。**正本改了就重新复制，再改到全过**。
//
// 和 iOS 唯一的不同：本端读 `platforms.android` 那一档覆盖（[PLATFORM]）。
//
// ⚠️ 只许用 Kotlin 标准库与 kotlinx.serialization 的 JsonElement，不许 import android.*：JUnit 直接跑它。
package com.geek.umbra.feature.theme

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.pow

/** 主题键（规格 v1.1 的 20 个，顺序同 `theme-keys.json › keys`）。`wire` 就是包里写的名字。 */
enum class ThemeKey(val wire: String) {
    Bg("bg"), Card("card"), Titlebar("titlebar"), Rail("rail"), Chip("chip"), Hover("hover"), Track("track"),
    Nav("nav"), UserBubble("userBubble"),
    Border("border"), BorderSoft("borderSoft"),
    Text("text"), Muted("muted"), InkQuiet("inkQuiet"), Faint("faint"),
    Accent("accent"), AccentDeep("accentDeep"), AccentSoft("accentSoft"), AccentText("accentText"), OnAccent("onAccent");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun of(wire: String): ThemeKey? = byWire[wire]
    }
}

enum class ThemeMode(val wire: String) {
    Light("light"), Dark("dark");

    companion object {
        fun of(wire: String): ThemeMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一套主题的完整生效值：模式 → 键 → `#RRGGBB` / `#RRGGBBAA`。 */
typealias ThemePalette = Map<ThemeMode, Map<ThemeKey, String>>

/** 一处不过线。`pair` 只有 T3 有：「套|字|底」如 `dark|muted|track`、`ios · dark|muted|bg`（同正本）。 */
data class ThemeProblem(val rule: String, val path: String, val why: String, val pair: String? = null)

data class ThemeCheck(
    val problems: List<ThemeProblem>,
    /** T7：写了锁死键的位置（`$.light.danger`）—— 不挡安装，只是不生效。 */
    val notes: List<String>,
    /** 本平台合并好的整套。只在没有问题时有。 */
    val palette: ThemePalette?,
) {
    val ok: Boolean get() = problems.isEmpty()
}

/** 一种颜色的通道（0～255，可以是合成出来的小数）与透明度（0～1）。 */
data class ThemeRgba(val rgb: DoubleArray, val a: Double)

object ThemeRules {
    // MARK: 规格常量（抄自 theme-keys.json，用例钉着）

    const val SPEC_VERSION = "1.1"
    const val COLOR_PATTERN = "^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$"
    private val colorRe = Regex(COLOR_PATTERN)
    val PLATFORMS = listOf("ios", "android", "pc")
    /** 本端读哪个平台覆盖。iOS 是 "ios"。 */
    const val PLATFORM = "android"

    /** 锁死键：写了不报错、不生效（`locked.keys`）。 */
    val LOCKED_KEYS = listOf(
        "success", "successSoft", "successText", "onSuccess", "warning", "warningSoft", "warningText", "onWarning",
        "danger", "dangerSoft", "dangerText", "onDanger",
        "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10", "catTint",
        "rcView", "rcControl", "viewerBg", "scrim", "shadow", "shadowFloating", "shadowModal", "desk",
    )
    private val lockedSet = LOCKED_KEYS.toSet()

    /** 锁死的语义色在两档里的实际值（`lockedValues`）：锁死不等于不查，主题换了底，它们落在新底上也要过线。 */
    val LOCKED_VALUES: Map<ThemeMode, Map<String, String>> = mapOf(
        ThemeMode.Light to mapOf("warningText" to "#97460A", "dangerText" to "#B42318", "success" to "#0F766E"),
        ThemeMode.Dark to mapOf("warningText" to "#D98A29", "dangerText" to "#EE8B80", "success" to "#34B5A6"),
    )

    /** 必须不透明的键（`opaque`）。 */
    val OPAQUE = listOf(ThemeKey.Bg, ThemeKey.Card)
    /** 半透明的底先压在同一套的这个键上（`composite.base`）。 */
    val COMPOSITE_BASE = ThemeKey.Card

    data class Pair(val fg: String, val on: List<ThemeKey>, val min: Double, val why: String)

    private val K = ThemeKey.entries.associateBy { it.wire }
    private fun keys(vararg w: String) = w.map { K.getValue(it) }

    /** 14 组对子（`contrast`）。fg 可能是锁死键（warningText / dangerText / success），所以是字符串。 */
    val CONTRAST = listOf(
        Pair("text", keys("bg", "card", "titlebar", "rail", "chip", "hover", "track", "userBubble", "accentSoft"), 4.5, "正文落到的每一张底"),
        Pair("muted", keys("bg", "card", "titlebar", "rail", "chip", "hover", "track", "userBubble"), 4.5, "只压中性底；软底上的字走各族 -text"),
        Pair("inkQuiet", keys("chip", "titlebar", "hover", "track"), 4.5, "它就是为这四张底存在的"),
        Pair("faint", keys("bg", "card", "rail"), 2.2, "不当正文，只保证看得见"),
        Pair("accentText", keys("bg", "card", "rail", "hover", "accentSoft"), 4.5, "链接、选中项文字"),
        Pair("onAccent", keys("accent", "accentDeep"), 3.0, "强调色实底上的字（端上一律 ≥ 600 字重）"),
        Pair("accent", keys("bg", "card", "rail", "track", "nav"), 3.0, "非文字指示：选中底线、进度、开关、导航指示"),
        Pair("border", keys("bg", "card", "rail"), 1.15, "分层靠描边"),
        Pair("borderSoft", keys("card", "rail"), 1.06, "卡内分隔"),
        Pair("hover", keys("card", "rail"), 1.05, "悬停要看得出"),
        Pair("accentSoft", keys("card"), 1.05, "选中底要看得出"),
        Pair("warningText", keys("bg", "card", "chip", "hover"), 4.5, "锁死色落在主题的底上"),
        Pair("dangerText", keys("bg", "card", "chip", "hover"), 4.5, "锁死色落在主题的底上"),
        Pair("success", keys("bg", "card"), 4.5, "锁死色落在主题的底上"),
    )

    /** 明暗（`lightness`）：四张主表面浅色那套 ≥ 0.5、深色那套 < 0.5；nav 两套 ≤ 0.05（导航字写死白）。 */
    val LIGHTNESS_KEYS = listOf(ThemeKey.Bg, ThemeKey.Card, ThemeKey.Rail, ThemeKey.Titlebar)
    const val LIGHT_MIN = 0.5
    const val DARK_MAX_EXCLUSIVE = 0.5
    const val NAV_MAX = 0.05

    /** 主题包里所有文件 base64 后的总长上限（`maxPackageBase64Bytes`）。 */
    const val MAX_PACKAGE_BASE64_BYTES = 262144

    // MARK: 颜色算术（同正本 contrastFormula / composite）

    /** `#RRGGBB` / `#RRGGBBAA` → 通道与透明度。形状不对回 null。 */
    fun parseColor(s: String): ThemeRgba? {
        if (!colorRe.matches(s)) return null
        val h = s.substring(1)
        fun byte(i: Int) = h.substring(i, i + 2).toInt(16).toDouble()
        return ThemeRgba(doubleArrayOf(byte(0), byte(2), byte(4)), if (h.length == 8) byte(6) / 255 else 1.0)
    }

    /** WCAG 2.x 相对亮度（通道可以是合成出来的小数）。 */
    fun luminance(c: DoubleArray): Double {
        fun f(v: Double): Double {
            val x = v / 255
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])
    }

    /** 对比度原始值（不取整）。 */
    fun contrastRatio(a: DoubleArray, b: DoubleArray): Double {
        val x = luminance(a) + 0.05
        val y = luminance(b) + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    /** 拿来和下限比的那个数：截断到两位小数（不四舍五入进线）。 */
    fun truncate2(r: Double): Double = floor(r * 100) / 100

    /** 半透明压在不透明底上：逐通道 fg × a + bg × (1 − a)，不取整。 */
    fun over(c: ThemeRgba, base: DoubleArray): DoubleArray =
        if (c.a >= 1) c.rgb else DoubleArray(3) { c.rgb[it] * c.a + base[it] * (1 - c.a) }

    // MARK: T1 形状

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isColor(v: JsonElement?): Boolean = v.asStr()?.let { parseColor(it) } != null

    /** 一档（或一档平台覆盖）的形状。`required`：通用两档 20 键必填；平台覆盖只写要改的键。 */
    private fun checkMode(raw: JsonElement?, path: String, required: Boolean, out: MutableList<ThemeProblem>) {
        val obj = raw.asObj() ?: run { out += ThemeProblem("T1", path, "不是对象"); return }
        for ((k, v) in obj) {
            if (k in lockedSet) continue                // 锁死键：值不校验、不生效
            if (ThemeKey.of(k) == null) { out += ThemeProblem("T1", "$path.$k", "不认识的主题键"); continue }
            if (!isColor(v)) out += ThemeProblem("T1", "$path.$k", "值只能是 #RRGGBB 或 #RRGGBBAA")
        }
        if (required) {
            for (k in ThemeKey.entries) if (obj[k.wire] == null) out += ThemeProblem("T1", "$path.${k.wire}", "缺这个键（20 个键两套都必填）")
        }
    }

    /** 只做形状：顶层只许 $schema / $comment / light / dark / platforms，light / dark 必填，平台只认 ios / android / pc，平台下只认 light / dark。 */
    fun shapeProblems(raw: JsonElement?): List<ThemeProblem> {
        val out = mutableListOf<ThemeProblem>()
        val top = raw.asObj() ?: return listOf(ThemeProblem("T1", "$", "不是 JSON 对象"))
        for (k in top.keys) if (k !in listOf("\$schema", "\$comment", "light", "dark", "platforms")) out += ThemeProblem("T1", "$.$k", "不认识的顶层键")
        for (k in listOf("\$schema", "\$comment")) if (top[k] != null && top[k].asStr() == null) out += ThemeProblem("T1", "$.$k", "只能是字符串")
        for (m in ThemeMode.entries) {
            if (top[m.wire] == null) out += ThemeProblem("T1", "$.${m.wire}", "缺这一套（浅、深两套都必须有）")
            else checkMode(top[m.wire], "$.${m.wire}", true, out)
        }
        top["platforms"]?.let { p ->
            val plats = p.asObj()
            if (plats == null) { out += ThemeProblem("T1", "$.platforms", "不是对象"); return@let }
            for ((name, block) in plats) {
                if (name !in PLATFORMS) { out += ThemeProblem("T1", "$.platforms.$name", "不认识的平台"); continue }
                val modes = block.asObj() ?: run { out += ThemeProblem("T1", "$.platforms.$name", "不是对象"); null } ?: continue
                for ((m, v) in modes) {
                    if (ThemeMode.of(m) == null) { out += ThemeProblem("T1", "$.platforms.$name.$m", "只能是 light / dark"); continue }
                    checkMode(v, "$.platforms.$name.$m", false, out)
                }
            }
        }
        return out
    }

    // MARK: 生效值

    /** 一档里去掉锁死键之后的主题键值（形状已经过了才调）。 */
    private fun unlocked(raw: JsonElement?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw.asObj() ?: return out) if (k !in lockedSet) v.asStr()?.let { out[k] = it }
        return out
    }

    data class EffectiveSet(val path: String, val label: String, val mode: ThemeMode, val values: Map<String, String>)

    /**
     * 要校验的每一套实际生效值（同正本 `effectiveSets`）：通用 light / dark，加上 platforms 里出现过的每个平台 × 模式。
     * 生效值 = 主题这一档（去锁死键）← 平台覆盖（去锁死键）← lockedValues。平台按 [PLATFORMS] 的顺序（同正本）。
     */
    fun effectiveSets(raw: JsonElement?): List<EffectiveSet> {
        val top = raw.asObj() ?: JsonObject(emptyMap())
        val plats = top["platforms"].asObj() ?: JsonObject(emptyMap())
        val sets = mutableListOf<EffectiveSet>()
        for (m in ThemeMode.entries) {
            val common = unlocked(top[m.wire])
            val locked = LOCKED_VALUES[m].orEmpty()
            sets += EffectiveSet("$.${m.wire}", m.wire, m, common + locked)
            for (p in PLATFORMS) {
                val ov = plats[p].asObj()?.get(m.wire).asObj() ?: continue
                sets += EffectiveSet("$.platforms.$p.${m.wire}", "$p · ${m.wire}", m, common + unlocked(ov) + locked)
            }
        }
        return sets
    }

    // MARK: 整条校验

    /**
     * 形状 → 每一套生效值量 T2～T4。`ok` 才给本平台（Android）的整套 `palette`。
     * 别的平台坏了也整包拒 —— 一份包三端通用，不能在这里装一个 PC 上必坏的。
     */
    fun check(raw: JsonElement?): ThemeCheck {
        val notes = lockedWarnings(raw)
        val shape = shapeProblems(raw)
        if (shape.isNotEmpty()) return ThemeCheck(shape, notes, null)
        val out = mutableListOf<ThemeProblem>()
        for (set in effectiveSets(raw)) {
            val col = HashMap<String, ThemeRgba>()
            for ((k, v) in set.values) parseColor(v)?.let { col[k] = it }
            var opaqueOk = true
            for (k in OPAQUE) if ((col[k.wire]?.a ?: 1.0) < 1) {
                out += ThemeProblem("T2", "${set.path}.${k.wire}", "${set.label} · ${k.wire} 必须不透明（半透明的底量不出对比度）")
                opaqueOk = false
            }
            val base = col[COMPOSITE_BASE.wire]?.rgb
            if (!opaqueOk || base == null) continue
            for (pair in CONTRAST) {
                val fg = col[pair.fg] ?: continue
                for (on in pair.on) {
                    val b = col[on.wire] ?: continue
                    val bg = over(b, base)
                    val r = truncate2(contrastRatio(over(fg, bg), bg))
                    if (r < pair.min) out += ThemeProblem(
                        "T3", "${set.path}.${pair.fg}",
                        "${set.label} · ${pair.fg} 在 ${on.wire} 上 ${fmt(2, r)}，要 ≥ ${pair.min}（${pair.why}）",
                        "${set.label}|${pair.fg}|${on.wire}",
                    )
                }
            }
            fun lum(k: ThemeKey): Double? = col[k.wire]?.let { luminance(over(it, base)) }
            for (k in LIGHTNESS_KEYS) {
                val l = lum(k) ?: continue
                if (set.mode == ThemeMode.Light && !(l >= LIGHT_MIN)) {
                    out += ThemeProblem("T4", "${set.path}.${k.wire}", "${set.label} · ${k.wire} 相对亮度 ${fmt(3, l)}，浅色那套要 ≥ $LIGHT_MIN")
                }
                if (set.mode == ThemeMode.Dark && !(l < DARK_MAX_EXCLUSIVE)) {
                    out += ThemeProblem("T4", "${set.path}.${k.wire}", "${set.label} · ${k.wire} 相对亮度 ${fmt(3, l)}，深色那套要 < $DARK_MAX_EXCLUSIVE")
                }
            }
            lum(ThemeKey.Nav)?.let { l ->
                if (!(l <= NAV_MAX)) out += ThemeProblem("T4", "${set.path}.nav", "${set.label} · nav 相对亮度 ${fmt(3, l)}，两套都要 ≤ $NAV_MAX（导航字写死白）")
            }
        }
        if (out.isNotEmpty()) return ThemeCheck(out, notes, null)
        return ThemeCheck(emptyList(), notes, resolve(raw))
    }

    /** 本平台实际生效的整套：Android 平台覆盖 → 通用值（20 键必填，所以不需要再回落内置值）。锁死键不进来。 */
    fun resolve(raw: JsonElement?, platform: String = PLATFORM): ThemePalette {
        val top = raw.asObj() ?: JsonObject(emptyMap())
        val plat = top["platforms"].asObj()?.get(platform).asObj()
        return ThemeMode.entries.associateWith { m ->
            val v = unlocked(top[m.wire]) + unlocked(plat?.get(m.wire))
            ThemeKey.entries.mapNotNull { k -> v[k.wire]?.let { k to it } }.toMap()
        }
    }

    /** 作者自查的提示（T7）：写了锁死键的位置。 */
    fun lockedWarnings(raw: JsonElement?): List<String> {
        val top = raw.asObj() ?: return emptyList()
        val out = mutableListOf<String>()
        fun scan(obj: JsonElement?, path: String) {
            for (k in obj.asObj()?.keys.orEmpty()) if (k in lockedSet) out += "$path.$k"
        }
        for (m in ThemeMode.entries) scan(top[m.wire], "$.${m.wire}")
        for ((p, per) in top["platforms"].asObj().orEmpty()) {
            for (m in ThemeMode.entries) scan(per.asObj()?.get(m.wire), "$.platforms.$p.${m.wire}")
        }
        return out.sorted()
    }

    /** 一句给人看的话（日志 / 提示用）：前四处，再说还有几处。 */
    fun problemsText(ps: List<ThemeProblem>): String {
        val head = ps.take(4).joinToString("；") { "${it.rule} ${it.path}：${it.why}" }
        return if (ps.size > 4) "$head；另有 ${ps.size - 4} 处" else head
    }

    data class Constant(val fg: String, val bg: String, val ratio: Double, val passes45: Boolean?)

    /** 自校准（`selfTest.constants`）：量公认常数，截断后等于登记值才算仪器可信。返回不对的条目说明。 */
    fun selfTest(constants: List<Constant>): List<String> {
        val bad = mutableListOf<String>()
        for (c in constants) {
            val f = parseColor(c.fg)
            val b = parseColor(c.bg)
            if (f == null || b == null) { bad += "${c.fg} / ${c.bg} 读不出"; continue }
            val r = truncate2(contrastRatio(f.rgb, b.rgb))
            if (r != c.ratio) bad += "${c.fg} / ${c.bg} 量出 $r，应为 ${c.ratio}"
            if (c.passes45 != null && (r >= 4.5) != c.passes45) bad += "${c.fg} / ${c.bg} 对 4.5 的判定反了"
        }
        return bad
    }

    /** 小数位固定、**不随系统区域**（德语、法语区域下 `%.2f` 会写成逗号，读数就和另外三端对不上了）。 */
    private fun fmt(digits: Int, v: Double) = String.format(java.util.Locale.ROOT, "%.${digits}f", v)
}
