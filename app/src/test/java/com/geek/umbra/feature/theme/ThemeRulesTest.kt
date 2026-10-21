package com.geek.umbra.feature.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * 主题判据与装包（ThemeRules / ThemePackage / Ed25519 / ThemeOfficial）的行为用例 —— 逐条照抄 iOS `Tools/swifttests/ThemeRulesTests.swift`。
 *
 * 夹具在 `app/src/test/resources/theme/`：从 iOS 仓 `Tools/swifttests/fixtures/theme/` 复制（它又是 umbra-plugins 正本的副本）。
 * 两头钉（同 iOS）：umbra-plugins / UmbraPC 仓并排在同一个父目录下时，要求副本与它们**逐字节相同**；不在就只用副本跑并打一行说明。
 * 先自校准（selfTest），再逐条比 25 条跨语言用例的 expect / paths / pairs / warnings，再钉住抄的常量与 theme-keys.json 一致。
 *
 * 写成「一个计数器 + 最后一起断言」而不是一条一个 @Test：和 iOS 读数可以直接比条数（iOS 120 条），一处不过也能看到其余全部。
 *
 * 和 iOS 的出入：
 *   - 平台覆盖读 `platforms.android`（iOS 读 `ios`）：测试向量只给了 ios 覆盖，所以这里断言的是「ios 覆盖**不**生效、用通用值」；
 *   - iOS 的 `ThemeShopRules`（iOS 不卖，#94）不移，换成 Android 自己的 [ThemeShopRules]（付费照卖、价格解不出不卖）；
 *   - 多出：许可证按账号核对（PC #128）、手写 Ed25519 与 JDK 自带实现互验。
 */
class ThemeRulesTest {
    private var failures = mutableListOf<String>()
    private var checked = 0

    private fun <T> expect(got: T, want: T, what: String) {
        checked++
        if (got != want) failures += "✗ $what：得到 $got，应当是 $want"
    }

    private fun local(rel: String): ByteArray =
        javaClass.classLoader!!.getResource("theme/$rel")?.readBytes() ?: error("主题：本仓夹具副本 $rel 不在了")

    private fun json(rel: String): JsonElement = Json.parseToJsonElement(String(local(rel), Charsets.UTF_8))

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.strs() = (this as? JsonArray)?.mapNotNull { it.str() }

    /** 单元测试的工作目录是 `app/`，并排的仓在它上面两层。 */
    private val geek = File("..").canonicalFile.parentFile

    @Test fun all() {
        upstreamCases()
        val spec = json("theme-keys.json").obj()!!
        selfTestCases(spec)
        crossLanguageCases()
        constantCases(spec)
        officialCases()
        packageCases()
        licenseCases()
        ed25519Cases()
        officialPackageCases()
        println("主题判据与装包行为用例：$checked 条，${failures.size} 条不过")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        // 读错文件 / 夹具被清空时先红：一条都没比也算「全过」，正是这类闸门最贵的坏法。
        assertTrue("条数太少（$checked）", checked >= 120)
    }

    /** 副本 = 正本（两个仓都在时）。 */
    private fun upstreamCases() {
        val plugins = File(geek, "umbra-plugins")
        val map = listOf(
            "theme-keys.json" to "sdk/theme-keys.json", "theme-base.json" to "sdk/theme-base.json",
            "cases.json" to "sdk/test-vectors/theme/cases.json",
            "canonical.bytes.json" to "sdk/test-vectors/theme/canonical.bytes.json",
            "canonical.sha256" to "sdk/test-vectors/theme/canonical.sha256",
            "signature.base64" to "sdk/test-vectors/theme/signature.base64",
            "test-publisher.pub" to "sdk/test-vectors/theme/test-publisher.pub",
            "minimal/manifest.json" to "sdk/test-vectors/theme/minimal/manifest.json",
            "minimal/tokens.json" to "sdk/test-vectors/theme/minimal/tokens.json",
            "com.example.theme-0.1.0.signed.umbraplugin" to "sdk/test-vectors/theme/com.example.theme-0.1.0.signed.umbraplugin",
            "com.example.theme-0.1.0.unsigned.umbraplugin" to "sdk/test-vectors/theme/com.example.theme-0.1.0.unsigned.umbraplugin",
            "official-default.tokens.json" to "themes/com.umbra.theme.default/tokens.json",
            "official-sea-salt.tokens.json" to "themes/com.umbra.theme.sea-salt/tokens.json",
            "official-graphite.tokens.json" to "themes/com.umbra.theme.graphite/tokens.json",
        )
        if (File(plugins, "sdk/theme-keys.json").exists()) {
            for ((mine, theirs) in map) {
                expect(File(plugins, theirs).readBytes().contentEquals(local(mine)), true, "夹具 $mine 与 umbra-plugins $theirs 逐字节相同（正本改了就重新复制）")
            }
        } else {
            map.forEach { local(it.first) }
            println("• 主题：找不到 umbra-plugins 仓，这次只用本仓副本跑，没和正本比是否漂移")
        }
        val pcKey = File(geek, "UmbraPC/keys/publisher.pub")
        if (pcKey.exists()) expect(pcKey.readBytes().contentEquals(local("publisher.pub")), true, "夹具 publisher.pub 与 UmbraPC keys/publisher.pub 逐字节相同")
        else println("• 主题：找不到 UmbraPC 仓，发布方公钥这次只和本仓副本比")
        expect(ThemePublisher.PEM.toByteArray().contentEquals(local("publisher.pub")), true, "内嵌的发布方公钥 = publisher.pub 原文")
    }

    private fun selfTestCases(spec: JsonObject) {
        val st = spec["selfTest"].obj()!!
        val cs = (st["constants"] as JsonArray).map {
            val o = it.obj()!!
            ThemeRules.Constant(o["fg"].str().orEmpty(), o["bg"].str().orEmpty(), (o["ratio"] as? JsonPrimitive)?.doubleOrNull ?: -1.0,
                (o["passes4_5"] as? JsonPrimitive)?.booleanOrNull)
        }
        expect(cs.size >= 4, true, "selfTest 常数至少 4 条")
        expect(ThemeRules.selfTest(cs), emptyList<String>(), "自校准：公认常数截断两位后等于登记值")
        // 反例包：海盐深色 muted 换成 #7A8594 → dark|muted|track 不过。海盐不内置，反例从官方包夹具里取。
        val ce = st["counterExample"].obj()!!
        val seaSalt = json("official-sea-salt.tokens.json").obj()!!
        val dark = seaSalt["dark"].obj()!!.toMutableMap()
        for ((k, v) in ce["set"].obj()!!) dark[k] = v
        val r = ThemeRules.check(JsonObject(mapOf("light" to seaSalt["light"]!!, "dark" to JsonObject(dark))))
        val mf = ce["mustFail"].obj()!!
        val want = "dark|${mf["fg"].str()}|${mf["on"].str()}"
        expect(r.problems.any { it.rule == mf["rule"].str() && it.pair == want }, true, "反例包必须报 $want")
    }

    private fun crossLanguageCases() {
        val cases = (json("cases.json").obj()!!["cases"] as JsonArray).map { it.obj()!! }
        expect(cases.size, 25, "跨语言用例齐（25 条）")
        fun uniq(xs: List<String>) = xs.toSortedSet().toList()
        for (c in cases) {
            val name = c["name"].str() ?: "?"
            val r = ThemeRules.check(c["tokens"])
            expect(uniq(r.problems.map { it.rule }), c["expect"].strs().orEmpty(), "用例「$name」规则")
            c["paths"].strs()?.let { expect(uniq(r.problems.filter { p -> p.rule != "T1" }.map { p -> p.path }), it, "用例「$name」位置") }
            c["pairs"].strs()?.let { expect(uniq(r.problems.mapNotNull { p -> p.pair }), it, "用例「$name」对子") }
            c["warnings"].strs()?.let { expect(uniq(r.notes), it, "用例「$name」锁死键提示") }
        }
    }

    private fun constantCases(spec: JsonObject) {
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull
        expect(spec["specVersion"].str(), ThemeRules.SPEC_VERSION, "规格版本")
        val keys = (spec["keys"] as JsonArray).map { it.obj()!! }
        expect(keys.mapNotNull { it["key"].str() }, ThemeKey.entries.map { it.wire }, "20 个键与顺序")
        expect(keys.any { it["optional"] != null }, false, "规格 v1.1 没有 optional 键（有了要补回落）")
        expect(spec["locked"].obj()!!["keys"].strs(), ThemeRules.LOCKED_KEYS, "锁死键")
        val lv = spec["lockedValues"].obj()!!
        for (m in ThemeMode.entries) {
            expect(lv[m.wire].obj()!!.mapValues { it.value.str() }, ThemeRules.LOCKED_VALUES[m], "lockedValues ${m.wire}")
        }
        expect(spec["opaque"].strs(), ThemeRules.OPAQUE.map { it.wire }, "不透明键")
        expect(spec["composite"].obj()!!["base"].str(), ThemeRules.COMPOSITE_BASE.wire, "合成底")
        expect(spec["colorPattern"].str(), ThemeRules.COLOR_PATTERN, "颜色形状")
        expect(spec["platforms"].strs(), ThemeRules.PLATFORMS, "平台")
        expect((spec["maxPackageBase64Bytes"] as JsonPrimitive).intOrNull, ThemeRules.MAX_PACKAGE_BASE64_BYTES, "体积上限")
        val lt = spec["lightness"].obj()!!
        expect(lt["light"].obj()!!["keys"].strs(), ThemeRules.LIGHTNESS_KEYS.map { it.wire }, "明暗四张主表面")
        expect(num(lt["light"].obj()!!["min"]), ThemeRules.LIGHT_MIN, "浅色下限")
        expect(num(lt["dark"].obj()!!["maxExclusive"]), ThemeRules.DARK_MAX_EXCLUSIVE, "深色上限")
        expect(num(lt["nav"].obj()!!["max"]), ThemeRules.NAV_MAX, "nav 上限")
        val pairs = (spec["contrast"] as JsonArray).map {
            val o = it.obj()!!
            "${o["fg"].str()}|${o["on"].strs()!!.joinToString(",")}|${num(o["min"])}"
        }
        expect(pairs, ThemeRules.CONTRAST.map { "${it.fg}|${it.on.joinToString(",") { k -> k.wire }}|${it.min}" }, "14 组对子")
    }

    private fun officialCases() {
        fun tok(n: String): JsonObject {
            val t = json("official-$n.tokens.json").obj()!!
            return JsonObject(mapOf("light" to t["light"]!!, "dark" to t["dark"]!!))
        }
        val base = json("theme-base.json").obj()!!
        expect(tok("default"), JsonObject(mapOf("light" to base["light"]!!, "dark" to base["dark"]!!)), "官方默认包 = theme-base.json")
        // 海盐 / 石墨从商城装（不内置）：照样要过本端判据，否则装回来也是一张「用不了」的卡。
        for ((n, f) in listOf("海盐" to "sea-salt", "石墨" to "graphite")) {
            val r = ThemeRules.check(tok(f))
            expect(r.problems, emptyList<ThemeProblem>(), "官方${n}过线")
            expect(r.palette?.get(ThemeMode.Dark)?.get(ThemeKey.OnAccent) != null, true, "官方${n}给出整套")
        }
        expect(ThemeRules.check(tok("graphite")).palette?.get(ThemeMode.Dark)?.get(ThemeKey.OnAccent), "#1A1A1C", "石墨深色强调实底上是深字")
        expect(ThemeOfficial.BUILTIN_IDS, listOf(ThemeOfficial.DEFAULT_ID), "内置只剩「默认」")
        // 英文对照（geek-17 交代的两条，与 iOS ThemeCopy 同字）
        expect(ThemeCopy.name(ThemeOfficial.SEA_SALT_ID, "海盐", english = true), "Sea Salt", "海盐英文名")
        expect(ThemeCopy.brief(ThemeOfficial.GRAPHITE_ID, "x", english = true), "Neutral gray with no tint and a muted mauve accent. The quietest of the set.", "石墨英文简介")
        expect(ThemeCopy.name(ThemeOfficial.SEA_SALT_ID, "海盐", english = false), "海盐", "中文界面用包里的名字")
        expect(ThemeCopy.name("com.x.theme", "别家", english = true), "别家", "表里没有的退回给的名字")
    }

    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun packageCases() {
        // gunzip + 保序解析 + canonical：未签名包重算的 canonical 等于精确字节。
        val unsigned = local("com.example.theme-0.1.0.unsigned.umbraplugin")
        val signed = local("com.example.theme-0.1.0.signed.umbraplugin")
        val bytes = local("canonical.bytes.json")
        val shaHex = String(local("canonical.sha256")).trim()
        expect(ThemeGzip.gunzip(unsigned) != null, true, "gunzip 解得开未签名包")
        val pu = (ThemePackage.decode(unsigned) as? ThemeResult.Ok)?.value
        val ps = (ThemePackage.decode(signed) as? ThemeResult.Ok)?.value
        if (pu == null || ps == null) { failures += "✗ 测试向量包解不开"; return }
        expect(ThemePackage.canonical(pu).contentEquals(bytes), true, "未签名包重算 canonical = canonical.bytes.json（${bytes.size} 字节）")
        expect(ThemePackage.canonical(ps).contentEquals(bytes), true, "已签名包重算 canonical 同一份")
        expect(sha256Hex(bytes), shaHex, "canonical 的 SHA-256")
        val testPem = String(local("test-publisher.pub"))
        val sig = String(local("signature.base64")).trim()
        expect(ThemeSignature.verify(bytes, sig, testPem), true, "测试公钥验 signature.base64 对 canonical 成立")
        expect(ps.signature, sig, "已签名包里带的就是那个签名")
        expect(ThemePackage.verify(ps, testPem), null, "已签名包用测试公钥验得过")
        expect(ThemePackage.verify(pu, testPem)?.kind, ThemeFailKind.Signature, "未签名包拒（不留开发模式后门）")
        expect(ThemePackage.verify(ps, ThemePublisher.PEM)?.kind, ThemeFailKind.Signature, "测试包用发布方公钥验不过（钥匙不同）")
        val tampered = ps.copy(files = ps.files + ("tokens.json" to ps.files["tokens.json"].orEmpty() + "AA=="))
        expect(ThemePackage.verify(tampered, testPem)?.kind, ThemeFailKind.Signature, "改一个文件验不过")
        when (val r = ThemePackage.inspectFiles(ps.files)) {
            is ThemeResult.Ok -> {
                val t = r.value
                expect(t.manifest.id, "com.example.theme", "清单 id")
                expect(t.manifest.name, "最小主题（测试向量）", "清单 name（中文）")
                // 向量只有 ios 平台覆盖：Android 读 android 那档，所以 ios 那几键**不**生效、用通用值（iOS 用例断言的是反面）。
                val mt = json("minimal/tokens.json").obj()!!
                val iosLight = mt["platforms"].obj()!!["ios"].obj()!!["light"].obj()!!
                for ((k, _) in iosLight) {
                    expect(t.palette[ThemeMode.Light]?.get(ThemeKey.of(k)!!), mt["light"].obj()!![k].str(), "ios 平台覆盖在 Android 上不生效：light.$k 用通用值")
                }
                // 反过来：给一份 android 覆盖，它要生效（resolve 读 android 那档）。
                val withAndroid = JsonObject(mt + ("platforms" to JsonObject(mapOf("android" to JsonObject(mapOf("light" to JsonObject(mapOf("bg" to JsonPrimitive("#FAFAFA")))))))))
                expect(ThemeRules.resolve(withAndroid)[ThemeMode.Light]?.get(ThemeKey.Bg), "#FAFAFA", "android 平台覆盖生效")
            }
            is ThemeResult.Err -> failures += "✗ 测试向量的文件检查不过：${r.fail.why}"
        }
        // gzip 尾巴的 CRC 不对 → 拒。
        val bad = unsigned.copyOf()
        bad[bad.size - 6] = (bad[bad.size - 6].toInt() xor 0xFF).toByte()
        expect(ThemeGzip.gunzip(bad) == null, true, "CRC 对不上拒")
        expect(ThemeGzip.gunzip("不是 gzip".toByteArray()) == null, true, "不是 gzip 拒")
        // T5 多一个文件、T6 太大。
        val extra = ThemePackage.inspectFiles(ps.files + ("preview.json" to "e30="))
        expect((extra as? ThemeResult.Err)?.fail?.kind, ThemeFailKind.Broken, "T5 多一个文件拒")
        val big = ThemePackage.inspectFiles(ps.files + ("tokens.json" to "A".repeat(ThemeRules.MAX_PACKAGE_BASE64_BYTES)))
        expect((big as? ThemeResult.Err)?.fail?.why?.startsWith("主题包太大"), true, "T6 太大拒")
        // 清单：插件字段、只有一档、id 形状。
        fun man(extra: Map<String, JsonElement>): ThemeFail? {
            val m = mapOf<String, JsonElement>(
                "id" to JsonPrimitive("com.x.theme"), "name" to JsonPrimitive("x"), "version" to JsonPrimitive("1.0.0"),
                "type" to JsonPrimitive("theme"), "themeApi" to JsonPrimitive("^1"),
                "modes" to JsonArray(listOf(JsonPrimitive("light"), JsonPrimitive("dark"))), "tokens" to JsonPrimitive("tokens.json"),
            ) + extra
            return (ThemePackage.checkManifest(JsonObject(m)) as? ThemeResult.Err)?.fail
        }
        expect(man(emptyMap()), null, "合格清单")
        expect(man(mapOf("permissions" to JsonObject(emptyMap()))) != null, true, "主题包里有 permissions 拒")
        expect(man(mapOf("modes" to JsonArray(listOf(JsonPrimitive("dark"))))) != null, true, "只有一档拒")
        expect(man(mapOf("id" to JsonPrimitive("Bad Id"))) != null, true, "id 形状不对拒")
        expect(man(mapOf("themeApi" to JsonPrimitive("^2"))) != null, true, "主题键大版本 2 本机不认")
        expect(man(mapOf("tokens" to JsonPrimitive("../x.json"))) != null, true, "tokens 路径带 .. 拒")
        // 转义（PACKAGE-FORMAT §2.1）。
        expect(OJson.quote("a\"b\\c\n\u0001/中\u007F"), "\"a\\\"b\\\\c\\n\\u0001/中\u007F\"", "字符串转义照 JSON.stringify")
        expect(OJson.parse("""{"b":1,"a":[true,null,"x"],"2":0,"1":0}""".toByteArray())?.stringify(), """{"1":0,"2":0,"b":1,"a":[true,null,"x"]}""",
            "对象键序照 JS（下标样的键在前）")
    }

    /** 临时 Ed25519 钥匙（JDK 自带）→ SPKI PEM 与签名函数。 */
    private fun tempKey(): kotlin.Pair<String, (ByteArray) -> String> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(kp.public.encoded)}\n-----END PUBLIC KEY-----\n"
        val sign = { msg: ByteArray ->
            Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() })
        }
        return pem to sign
    }

    private fun licenseCases() {
        val (pem, sign) = tempKey()
        val payload = """{"sub":"7","grants":[{"plugin":"com.a.theme","from":"2026-01-01","until":null,"src":"purchase"},{"plugin":"com.b.theme","from":"2026-01-01","until":"2026-10-01T00:00:00+00:00","src":"membership"}]}"""
        val text = ThemeLicense.text(payload, sign(payload.toByteArray())).orEmpty()
        val doc = (ThemeLicense.verify(text, pem) as? ThemeResult.Ok)?.value
        if (doc == null) { failures += "✗ 许可证验不过"; return }
        val grants = doc.grants
        expect(grants.size, 2, "许可证两条授权")
        val tampered = text.replace("com.b.theme", "com.c.theme")
        expect((ThemeLicense.verify(tampered, pem) as? ThemeResult.Err)?.fail?.kind, ThemeFailKind.License, "改了 payload 验不过")
        val now = ThemeLicense.date("2026-10-18T00:00:00Z")!!
        expect(ThemeLicense.entitlement(grants, "com.a.theme", now).state, ThemeLicense.State.Active, "买断永久可用")
        val b = ThemeLicense.entitlement(grants, "com.b.theme", now)
        expect(b.state, ThemeLicense.State.Expired, "随会员的过期了")
        expect(b.src, "membership", "过期那条记得来源（文案写会员几号到期）")
        expect(ThemeLicense.entitlement(grants, "com.z.theme", now).state, ThemeLicense.State.Unlicensed, "没有授权")
        expect(ThemeLicense.entitlement(null, "com.a.theme", now).state, ThemeLicense.State.Unlicensed, "没有许可证")
        val later = listOf(ThemeGrant("com.a.theme", "2027-01-01", null, "gift"))
        expect(ThemeLicense.entitlement(later, "com.a.theme", now).state, ThemeLicense.State.NotYet, "还没开始")
        val mixed = listOf(ThemeGrant("x.y", null, "2026-01-01", "trial"), ThemeGrant("x.y", null, null, "purchase"))
        expect(ThemeLicense.entitlement(mixed, "x.y", now), ThemeLicense.Entitlement(ThemeLicense.State.Active, null, "purchase"), "买断盖过过期的试用")

        // 按账号（PC #128 的教训）：许可证签给 sub=7，只有账号 7 能用；没登录 / 别的账号都不能用。
        expect(doc.sub, "7", "许可证读出签给的账号")
        expect(ThemeLicense.belongsTo(doc, "7"), true, "签给 7 的许可证账号 7 能用")
        expect(ThemeLicense.belongsTo(doc, "8"), false, "#128 换了账号（8）不能用上一个人的许可证")
        expect(ThemeLicense.belongsTo(doc, null), false, "没登录不能用")
        expect(ThemeLicense.belongsTo(doc.copy(sub = null), "7"), false, "许可证没写签给谁 → 不认")
        val numericSub = """{"sub":7,"grants":[]}"""
        val numDoc = (ThemeLicense.verify(ThemeLicense.text(numericSub, sign(numericSub.toByteArray()))!!, pem) as? ThemeResult.Ok)?.value
        expect(numDoc?.sub, "7", "sub 写成数字也读成同一个账号 id")

        // Android 商城（sam 10-21：付费主题照卖，积分买断）：只有价格读不出 / 负数不卖。
        val R = ThemeShopRules
        expect(listOf(0, 1, 300, -1, R.UNKNOWN_PRICE).map(R::mayBuy), listOf(true, true, true, false, false), "价格读得出、不是负数才买；解不出（#94）不调购买")
        expect(listOf(0, 1, 300).map(R::needsConfirm), listOf(false, true, true), "付费的先问一句扣多少积分，免费的直接领")
    }

    /**
     * 商城上架的那两个官方包（umbra-plugins `dist/`，10-21 上架的 1.0.1）：解包 → 清单 → 文件 → 判据，在 Android 上要过、给出整套。
     * dist 里的是未签名原件（服务端上传时才签），所以这里不验签 —— 真签名只能在真机上从商城装一次看。仓不在就跳过并说一声。
     */
    private fun officialPackageCases() {
        val dist = File(geek, "umbra-plugins/dist")
        val names = listOf("com.umbra.theme.sea-salt-1.0.1.umbraplugin" to "海盐", "com.umbra.theme.graphite-1.0.1.umbraplugin" to "石墨")
        if (!File(dist, names[0].first).exists()) { println("• 主题：找不到 umbra-plugins/dist，这次没跑官方 1.0.1 包"); return }
        for ((f, n) in names) {
            val pkg = (ThemePackage.decode(File(dist, f).readBytes()) as? ThemeResult.Ok)?.value
            expect(pkg != null, true, "官方$n 1.0.1 包解得开")
            val r = pkg?.let { ThemePackage.inspectFiles(it.files) }
            expect((r as? ThemeResult.Ok)?.value?.manifest?.name, n, "官方$n 1.0.1 包过清单与判据，名字是包里的中文")
            expect((r as? ThemeResult.Ok)?.value?.palette?.get(ThemeMode.Dark)?.size, 20, "官方$n 1.0.1 深色给出 20 键")
        }
    }

    /** 手写的 Ed25519 和 JDK 自带实现互验：随机消息、空消息、改一个字节、换一把钥匙。 */
    private fun ed25519Cases() {
        val (pem, sign) = tempKey()
        val other = tempKey()
        val rnd = java.util.Random(42)
        for (n in listOf(0, 1, 63, 64, 1000)) {
            val msg = ByteArray(n).also(rnd::nextBytes)
            val sig = sign(msg)
            expect(ThemeSignature.verify(msg, sig, pem), true, "JDK 签的 $n 字节消息，手写验签认")
            if (n > 0) {
                val bad = msg.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                expect(ThemeSignature.verify(bad, sig, pem), false, "$n 字节消息改一位验不过")
            }
            expect(ThemeSignature.verify(msg, sig, other.first), false, "$n 字节消息换一把公钥验不过")
        }
        val msg = "umbra".toByteArray()
        val raw = Base64.getDecoder().decode(sign(msg))
        raw[63] = (raw[63].toInt() or 0x20).toByte()   // s 加大到 ≥ L：必须拒（防延展性）
        expect(ThemeSignature.verify(msg, Base64.getEncoder().encodeToString(raw), pem), false, "s ≥ L 的签名拒")
        expect(ThemeSignature.verify(msg, "短", pem), false, "签名不是 base64 拒")
        expect(ThemeSignature.publicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"), null, "公钥形状不对回 null")
        // 时间：授权日期两种写法
        expect(ThemeLicense.date("2026-11-01"), Instant.parse("2026-11-01T00:00:00Z"), "纯日期按 UTC 零点")
        expect(ThemeLicense.date("2026-11-01T08:00:00.250+08:00"), Instant.parse("2026-11-01T00:00:00.250Z"), "带小数秒和时区的时刻")
        expect(ThemeLicense.date("下周"), null, "认不出回 null")
    }
}
