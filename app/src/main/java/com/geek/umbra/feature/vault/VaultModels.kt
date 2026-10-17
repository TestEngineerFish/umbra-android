// 保险箱数据模型（对齐 PC 快照结构）—— 照抄 iOS VaultModels.swift。
//
// ⚠️ 纯 Kotlin（只用 kotlinx.serialization 的 JsonElement），不许 import android.*：VaultModelsTest 要在 JVM 上跑
// 「一条带 nodes / edges / 不认识的键的 JSON 过一遍 VItem 之后还剩什么」——
// 丢键的后果是手机上点一下收藏，电脑上那张画布就没了（revision 大者胜，PC 会乖乖收下空记录）。
//
// 为什么不用 @Serializable 数据类：合成的序列化器解码时会把不认识的键**静默丢掉**（我们全局开着 ignoreUnknownKeys），
// 编码时也不会再吐出来。所以 VItem / VNode / VEdge 手写 JsonObject 的进出：已知键按字段，**其余一律进 extra，按键名排序写回**。
// iOS 的 VJSON 在这里就是 kotlinx 的 JsonElement（同样是「任意 JSON 值」，且相等比较是按值的）。
package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 保险箱自己的 JSON：解码不宽松（必填键缺了要解不开，同 iOS），编码紧凑。 */
internal val VaultJson = Json { prettyPrint = false }

/** 模型解码失败（必填键缺了 / 类型不对）。同 iOS 的 DecodingError —— 整份快照解不开就是「解密失败」。 */
class VaultDecodeException(msg: String) : Exception(msg)

// MARK: - VJSON 读取（iOS VJSON 的 string / bool / strings / number / object / array）

/**
 * 控件里的值按字符串读（用户名、网址、数字都走这里）。
 * ⚠️ 数字那一支：整数值且装得进 Long 才写成整数，否则按 Double 原样（iOS 那条 trap 的教训：整数值 ≠ 装得进整型）。
 */
val JsonElement?.vString: String
    get() {
        val p = this as? JsonPrimitive ?: return ""
        if (p is JsonNull) return ""
        if (p.isString) return p.content
        if (p.booleanOrNull != null) return ""
        val x = p.doubleOrNull ?: return ""
        val i = VNum.long(x)
        return if (x == Math.rint(x) && i != null) i.toString() else swiftDoubleText(x)
    }

/**
 * Double 按 Swift `String(Double)` 的样子写出来（iOS `VJSON.string` 的 `String(x)` 那一支）。
 * ⚠️ 原来直接 `x.toString()`：Kotlin 写的是 `6.2E19` / `-1.0E300`，iOS 写的是 `6.2e+19` / `-1e+300` ——
 * 同一条记录两端屏上显示不同，iOS 用例 `numberCases` 照抄过来当场不过。
 * Swift 的规则（实测 swiftc）：最短往返数字；|x| > 2^53 或 0 < |x| < 1e-4 用指数形式（`e+19` / `e-05`，指数至少两位），
 * 其余用小数形式且至少带一位小数（`1.5`、`12345678.5`）。JDK 19+ 的 `Double.toString` 给的就是最短往返数字。
 */
private fun swiftDoubleText(x: Double): String {
    if (x.isNaN()) return "nan"
    if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
    val bd = java.math.BigDecimal(x.toString()).stripTrailingZeros()
    val ax = Math.abs(x)
    if (ax > 9.007199254740992E15 || (ax != 0.0 && ax < 1e-4)) {
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale()
        val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val sign = if (x < 0) "-" else ""
        val es = if (exp < 0) "-" else "+"
        return sign + mant + "e" + es + Math.abs(exp).toString().padStart(2, '0')
    }
    val plain = bd.toPlainString()
    return if (plain.contains('.')) plain else "$plain.0"
}

val JsonElement?.vBool: Boolean get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
val JsonElement?.vStrings: List<String> get() = (this as? JsonArray)?.map { it.vString } ?: emptyList()
/** 数字那一支（画布节点的 x / y / w / h、`fmt`）。不是数字就是 null。 */
val JsonElement?.vNumber: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull
    }
val JsonElement?.vObject: Map<String, JsonElement>? get() = this as? JsonObject
val JsonElement?.vArray: List<JsonElement>? get() = this as? JsonArray

/** 字符串值。 */
fun js(s: String): JsonElement = JsonPrimitive(s)
fun jb(b: Boolean): JsonElement = JsonPrimitive(b)
/**
 * 数字值。整数值（且 |x| < 2^53）写成整数字面：kotlinx 直接写 Double 会出 `1.7E12` 这种形状 ——
 * 合法 JSON，但 PC / iOS 写的是 `1700000000000`，日志里 diff、用例里比对都会无端多出一堆差异。
 */
fun jn(x: Double): JsonElement =
    if (x == Math.rint(x) && Math.abs(x) < 9.007199254740992E15) JsonPrimitive(x.toLong()) else JsonPrimitive(x)
fun jo(m: Map<String, JsonElement>): JsonElement = JsonObject(m)
fun ja(l: List<JsonElement>): JsonElement = JsonArray(l)

/** 同步数据里的数字转整数的唯一入口（iOS `VNum`）：NaN / 无穷 / 越界回 null，由调用方决定怎么显示。 */
object VNum {
    fun int(x: Double): Int? {
        if (x.isNaN() || x.isInfinite()) return null
        val t = if (x < 0) Math.ceil(x) else Math.floor(x)
        return if (t >= Int.MIN_VALUE && t <= Int.MAX_VALUE) t.toInt() else null
    }

    fun long(x: Double): Long? {
        if (x.isNaN() || x.isInfinite()) return null
        val t = if (x < 0) Math.ceil(x) else Math.floor(x)
        return if (t >= -9.223372036854776E18 && t < 9.223372036854776E18) t.toLong() else null
    }
}

// MARK: - 必填 / 可选取值（解码用）

private fun JsonObject.reqString(k: String): String {
    val p = this[k] as? JsonPrimitive ?: throw VaultDecodeException("缺 $k")
    if (!p.isString) throw VaultDecodeException("$k 不是字符串")
    return p.content
}

private fun JsonObject.optString(k: String): String? {
    val e = this[k] ?: return null
    if (e is JsonNull) return null
    val p = e as? JsonPrimitive ?: throw VaultDecodeException("$k 不是字符串")
    if (!p.isString) throw VaultDecodeException("$k 不是字符串")
    return p.content
}

private fun JsonObject.reqDouble(k: String): Double = this[k].vNumber ?: throw VaultDecodeException("缺 $k")
private fun JsonObject.optDouble(k: String): Double? {
    val e = this[k] ?: return null
    if (e is JsonNull) return null
    return e.vNumber ?: throw VaultDecodeException("$k 不是数字")
}

private fun JsonObject.reqInt(k: String): Int {
    val p = this[k] as? JsonPrimitive ?: throw VaultDecodeException("缺 $k")
    if (p.isString) throw VaultDecodeException("$k 不是整数")
    return p.intOrNull ?: throw VaultDecodeException("$k 不是整数")
}

private fun JsonObject.optBool(k: String): Boolean? {
    val e = this[k] ?: return null
    if (e is JsonNull) return null
    val p = e as? JsonPrimitive ?: throw VaultDecodeException("$k 不是布尔")
    if (p.isString) throw VaultDecodeException("$k 不是布尔")
    return p.booleanOrNull ?: throw VaultDecodeException("$k 不是布尔")
}

private fun JsonObject.reqArray(k: String): JsonArray = this[k] as? JsonArray ?: throw VaultDecodeException("缺 $k")
private fun JsonObject.reqObject(k: String): JsonObject = this[k] as? JsonObject ?: throw VaultDecodeException("缺 $k")

private fun JsonElement.asObject(what: String): JsonObject = this as? JsonObject ?: throw VaultDecodeException("$what 不是对象")

// MARK: - 控件块 / 类型 / 附件 / 库

/** 旧记录的一个控件块：account{username,password,url,otp} / secret / field / text {value} / images|files {atts}。 */
data class VBlock(val id: String, val type: String, val label: String?, val data: Map<String, JsonElement>) {
    fun toJson(): JsonObject = JsonObject(buildMap {
        put("id", js(id)); put("type", js(type)); if (label != null) put("label", js(label)); put("data", JsonObject(data))
    })

    companion object {
        fun from(e: JsonElement): VBlock {
            val o = e.asObject("控件")
            return VBlock(o.reqString("id"), o.reqString("type"), o.optString("label"), o.reqObject("data"))
        }
    }
}

data class VType(val id: String, val name: String, val icon: String, val order: Double) {
    fun toJson(): JsonObject = JsonObject(mapOf("id" to js(id), "name" to js(name), "icon" to js(icon), "order" to jn(order)))

    companion object {
        fun from(e: JsonElement): VType {
            val o = e.asObject("类型")
            return VType(o.reqString("id"), o.reqString("name"), o.reqString("icon"), o.reqDouble("order"))
        }
    }
}

/** 附件元数据。`w` / `h` / `fileId` 是画布版加的三格，都可选；`fileId` 只有会员把字节上了云才有。 */
data class VAtt(
    val id: String, val name: String, val mime: String, val size: Double, val addedAt: Double,
    val w: Double? = null, val h: Double? = null, val fileId: String? = null,
) {
    fun toJson(): JsonObject = JsonObject(buildMap {
        put("id", js(id)); put("name", js(name)); put("mime", js(mime)); put("size", jn(this@VAtt.size)); put("addedAt", jn(addedAt))
        if (w != null) put("w", jn(w)); if (h != null) put("h", jn(h)); if (fileId != null) put("fileId", js(fileId))
    })

    companion object {
        fun from(e: JsonElement): VAtt {
            val o = e.asObject("附件")
            return VAtt(o.reqString("id"), o.reqString("name"), o.reqString("mime"), o.reqDouble("size"), o.reqDouble("addedAt"),
                o.optDouble("w"), o.optDouble("h"), o.optString("fileId"))
        }
    }
}

data class VVaultInfo(val id: String, val name: String, val owner: String, val icon: String, val order: Double, val keyWrapped: String) {
    fun toJson(): JsonObject = JsonObject(mapOf(
        "id" to js(id), "name" to js(name), "owner" to js(owner), "icon" to js(icon), "order" to jn(order), "keyWrapped" to js(keyWrapped),
    ))

    companion object {
        fun from(e: JsonElement): VVaultInfo {
            val o = e.asObject("库")
            return VVaultInfo(o.reqString("id"), o.reqString("name"), o.reqString("owner"), o.reqString("icon"), o.reqDouble("order"), o.reqString("keyWrapped"))
        }
    }
}

// MARK: - 一条记录

/**
 * 一条记录。已知键照旧解，**其余一律进 [extra]，编码时按键名排序原样写回**（iOS VaultModels.swift:94）。
 * `nodes` / `edges` / `fmt` / `iconImg` / `order` 今天就在 extra 里，将来 PC 再加什么键也在。
 * `typeId` / `blocks` 仍是必填：PC v2 会写空值（`""` / `[]`），旧 iOS 才解得开 v2 快照。
 */
data class VItem(
    val id: String,
    val typeId: String,
    val title: String,
    val icon: String? = null,
    val favorite: Boolean? = null,
    val tags: List<String>? = null,
    val blocks: List<VBlock> = emptyList(),
    val attachments: List<VAtt> = emptyList(),
    val createdAt: Double = 0.0,
    val updatedAt: Double = 0.0,
    val revision: Double = 0.0,
    /** 删除墓碑：参与同步、界面过滤。 */
    val deleted: Boolean? = null,
    /** 这里不认识的键，原样保留。读写画布走 [nodes] / [withNodes]，别直接翻这本字典。 */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** 已知键按固定顺序写，extra 按键名排序再写（同 iOS：两次编码出来的字节一样，用例与日志才比得了）。 */
    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        put("id", js(id)); put("typeId", js(typeId)); put("title", js(title))
        if (icon != null) put("icon", js(icon))
        if (favorite != null) put("favorite", jb(favorite))
        if (tags != null) put("tags", ja(tags.map { js(it) }))
        put("blocks", ja(blocks.map { it.toJson() }))
        put("attachments", ja(attachments.map { it.toJson() }))
        put("createdAt", jn(createdAt)); put("updatedAt", jn(updatedAt)); put("revision", jn(revision))
        if (deleted != null) put("deleted", jb(deleted))
        for (k in extra.keys.sorted()) if (k !in KNOWN_KEYS) put(k, extra.getValue(k))
    })

    /** 这条记录是不是画布版（`fmt == 2`）。缺 `fmt` 的旧记录按控件显示 —— 迁移由 PC 做，iOS / Android 不迁。 */
    val isCanvas: Boolean get() = extra["fmt"].vNumber == 2.0

    /** 画布上的节点（只读视图）。`nodes` 不是数组、或某一项缺 id / kind，那一项按没有。 */
    val nodes: List<VNode> get() = (extra["nodes"].vArray ?: emptyList()).mapNotNull { VNode.from(it) }

    /** 关联线（只读视图）。手机上不画，只保住。 */
    val edges: List<VEdge> get() = (extra["edges"].vArray ?: emptyList()).mapNotNull { VEdge.from(it) }

    /** 节点按 y 再 x 排；同一位置按 id 定序，别让顺序每次刷新都抖。 */
    val sortedNodes: List<VNode>
        get() = nodes.sortedWith(compareBy<VNode> { it.y }.thenBy { it.x }.thenBy { it.id })

    /** 把改过的节点写回 `extra["nodes"]`。只换这一个键，`edges` / `fmt` / 别的键一个不动。 */
    fun withNodes(list: List<VNode>): VItem = copy(extra = extra + ("nodes" to ja(list.map { it.toJson() })))

    /** 还有没有内容（回收站三态的分界）。画布版的内容在 nodes 里，不在 blocks。 */
    val hasContent: Boolean get() = blocks.isNotEmpty() || attachments.isNotEmpty() || nodes.isNotEmpty()

    /**
     * 彻底删除时把内容擦干净（标题 / 图标 / 控件 / 附件清单 / 标签 / **画布**），只留墓碑行。
     * nodes / edges 清成**空数组**（键还在）；`fmt` / `order` 这类不是内容的键留着；`iconImg` 删掉。
     */
    fun purgedContent(): VItem {
        val ex = extra.toMutableMap()
        if (ex["nodes"] != null) ex["nodes"] = ja(emptyList())
        if (ex["edges"] != null) ex["edges"] = ja(emptyList())
        ex.remove("iconImg")
        return copy(title = "", icon = null, blocks = emptyList(), attachments = emptyList(), tags = emptyList(), extra = ex)
    }

    /** 全部 account 块（只看旧存法）。 */
    val accountBlocks: List<VBlock> get() = blocks.filter { it.type == "account" }

    /**
     * 这条记录里全部的账号（统一视图）：画布版取 acct 节点（按画布顺序），否则取 account 块。
     * ⚠️ `fmt == 2` 的记录**以节点为准**，blocks 一律不看。
     */
    val accounts: List<VAccount>
        get() {
            if (isCanvas) {
                return sortedNodes.filter { it.kind == "acct" }.map { n ->
                    val seed = n.data["seed"].vString
                    VAccount(n.id, n.title, n.data["user"].vString, n.data["pwd"].vString, n.data["url"].vString, seed, n.data["note"].vString, seed.isNotEmpty())
                }
            }
            return accountBlocks.map { b ->
                VAccount(b.id, "", b.data["username"].vString, b.data["password"].vString, b.data["url"].vString, "", "", b.data["otp"].vBool)
            }
        }

    /**
     * 系统交回来一条（记录 id + 用户名）时该填哪个账号：用户名对得上的那一个；没给用户名才退回第一个；
     * **给了却对不上回 null**（别替他填一个他没点的账号，umbra-ios#14）。
     */
    fun account(forUser: String): VAccount? {
        val all = accounts
        if (forUser.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { it.user == forUser }
    }

    /** 参与搜索的字段：**敏感值不进搜索**（pwd / seed / 遮罩正文 / 密文列）。 */
    val searchHaystack: List<String>
        get() {
            val hay = mutableListOf(title)
            hay += tags ?: emptyList()
            if (!isCanvas) {
                for (b in accountBlocks) { hay += b.data["username"].vString; hay += b.data["url"].vString }
                return hay
            }
            for (n in sortedNodes) hay += n.searchParts()
            return hay
        }

    companion object {
        val KNOWN_KEYS = setOf("id", "typeId", "title", "icon", "favorite", "tags", "blocks", "attachments", "createdAt", "updatedAt", "revision", "deleted")

        fun from(e: JsonElement): VItem {
            val o = e.asObject("记录")
            val tags = o["tags"]?.takeIf { it !is JsonNull }?.let { t ->
                (t as? JsonArray ?: throw VaultDecodeException("tags 不是数组")).map {
                    val p = it as? JsonPrimitive
                    if (p == null || !p.isString) throw VaultDecodeException("tags 里有非字符串")
                    p.content
                }
            }
            return VItem(
                id = o.reqString("id"), typeId = o.reqString("typeId"), title = o.reqString("title"),
                icon = o.optString("icon"), favorite = o.optBool("favorite"), tags = tags,
                blocks = o.reqArray("blocks").map { VBlock.from(it) },
                attachments = o.reqArray("attachments").map { VAtt.from(it) },
                createdAt = o.reqDouble("createdAt"), updatedAt = o.reqDouble("updatedAt"), revision = o.reqDouble("revision"),
                deleted = o.optBool("deleted"),
                extra = o.filterKeys { it !in KNOWN_KEYS },
            )
        }

        fun parse(text: String): VItem = from(VaultJson.parseToJsonElement(text))
    }
}

/**
 * 一条记录里的一个账号，**两种存法的统一视图**：画布版 acct 节点的 `data.user / pwd / seed / url / note`，
 * 或旧 account 块的 `username / password / url / otp`。AutoFill、列表副文、体检都只看这一个形状。
 */
data class VAccount(
    /** 节点 id 或块 id。 */
    val id: String,
    /** 节点自己的标题；旧记录的账号块恒为空串。 */
    val title: String,
    val user: String,
    val pwd: String,
    val url: String,
    /** 两步验证密钥（画布版才有）。 */
    val seed: String,
    val note: String,
    /** 旧记录那个「含两步验证」布尔标记；画布版恒为 `seed` 非空。 */
    val otpFlag: Boolean,
) {
    val hasOtp: Boolean get() = seed.isNotEmpty() || otpFlag
}

// MARK: - 画布节点 / 关联线

/**
 * 画布上的一个节点。`data` / `set` 的键按节点种类不同，这里不展开成字段：展开一次就多一处「PC 加了键、手机存一次就丢」。
 * 种类今天五种（text / acct / table / img / file）；不认识的照样保留，界面画成「在电脑上查看」。
 */
data class VNode(
    val id: String,
    val kind: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 0.0,
    val h: Double = 0.0,
    val title: String = "",
    val data: Map<String, JsonElement> = emptyMap(),
    val set: Map<String, JsonElement> = emptyMap(),
    /** 节点上不认识的键，原样保留。 */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** 写回 `nodes[]` 的形状：extra 原样，已知键覆盖。 */
    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra)
        put("id", js(id)); put("kind", js(kind))
        put("x", jn(x)); put("y", jn(y)); put("w", jn(w)); put("h", jn(h))
        put("title", js(title)); put("data", JsonObject(data)); put("set", JsonObject(set))
    })

    /** `set` 里的一个开关；键缺了、或不是布尔，按该种节点的默认值。 */
    fun flag(key: String, default: Boolean): Boolean {
        val p = set[key] as? JsonPrimitive ?: return default
        if (p.isString) return default
        return p.booleanOrNull ?: default
    }

    /**
     * 这个节点里**可以被搜到**的字符串（列表搜索与详情页查找共用）：节点标题；文本正文（`set.masked` 为真不进）；
     * 账号的用户名 / 网址 / 备注（**pwd / seed 永远不进**）；表格的表头与非密文列的格子；图片 / 附件的文件名。
     * ⚠️ 密文永不参与匹配是安全不变量：搜索框是一个可以无限次提问的 oracle。
     */
    fun searchParts(resolve: MentionResolve = { _, _ -> null }): List<String> {
        fun plain(s: String) = VaultMentionRules.plainText(s, resolve)
        val out = mutableListOf(title)
        when (kind) {
            "text" -> if (!flag("masked", false)) out += plain(data["body"].vString)
            "acct" -> { out += data["user"].vString; out += data["url"].vString; out += plain(data["note"].vString) }
            "table" -> {
                val masked = (set["mask"].vArray ?: emptyList()).mapNotNull { it.vNumber }.mapNotNull { VNum.int(it) }.toSet()
                out += data["cols"].vStrings
                for (row in data["rows"].vArray ?: emptyList()) {
                    (row.vArray ?: emptyList()).forEachIndexed { ci, cell -> if (ci !in masked) out += plain(cell.vString) }
                }
            }
            "img", "file" -> for (it in data["items"].vArray ?: emptyList()) out += it.vObject?.get("name").vString
        }
        return out
    }

    companion object {
        val KINDS = listOf("text", "acct", "table", "img", "file")
        private val KNOWN = setOf("id", "kind", "x", "y", "w", "h", "title", "data", "set")

        /** 从 `nodes[]` 里的一项解。**id 与 kind 缺了就是 null**（那不是一个节点），其余键缺了按默认值。 */
        fun from(e: JsonElement): VNode? {
            val o = e as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            return VNode(
                id, kind, o["x"].vNumber ?: 0.0, o["y"].vNumber ?: 0.0, o["w"].vNumber ?: 0.0, o["h"].vNumber ?: 0.0,
                o["title"].vString, o["data"] as? JsonObject ?: emptyMap(), o["set"] as? JsonObject ?: emptyMap(),
                o.filterKeys { it !in KNOWN },
            )
        }

        /** 新建记录时那一个默认账号节点：(40, 40)、280×240，data / set 的键全部写上（设计侧 233 §三）。 */
        fun defaultAcct(id: String) = VNode(
            id, "acct", 40.0, 40.0, 280.0, 240.0, "账号",
            data = mapOf("user" to js(""), "pwd" to js(""), "seed" to js(""), "url" to js(""), "note" to js("")),
            set = mapOf(
                "user" to jb(true), "pwd" to jb(true), "totp" to jb(true), "url" to jb(true),
                "note" to jb(false), "pwdMasked" to jb(true), "copy" to jb(true), "strength" to jb(false),
            ),
        )
    }
}

/** 两个节点之间的关联线（无向）。手机上不画也不改，只是保住。 */
data class VEdge(val id: String, val a: String, val b: String, val label: String, val extra: Map<String, JsonElement>) {
    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra); put("id", js(id)); put("a", js(a)); put("b", js(b)); put("label", js(label))
    })

    companion object {
        private val KNOWN = setOf("id", "a", "b", "label")
        fun from(e: JsonElement): VEdge? {
            val o = e as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            return VEdge(id, o["a"].vString, o["b"].vString, o["label"].vString, o.filterKeys { it !in KNOWN })
        }
    }
}

// MARK: - 一个库的数据 / 整份快照 / 服务端那份 record

data class VData(val types: List<VType>, val items: List<VItem>, val attachments: Map<String, String>) {
    fun toJson(): JsonObject = JsonObject(mapOf(
        "types" to ja(types.map { it.toJson() }),
        "items" to ja(items.map { it.toJson() }),
        "attachments" to JsonObject(attachments.mapValues { js(it.value) }),
    ))

    companion object {
        val EMPTY = VData(emptyList(), emptyList(), emptyMap())
        fun from(e: JsonElement): VData {
            val o = e.asObject("库数据")
            val atts = o.reqObject("attachments").mapValues { (k, v) ->
                val p = v as? JsonPrimitive
                if (p == null || !p.isString) throw VaultDecodeException("附件 $k 不是字符串")
                p.content
            }
            return VData(o.reqArray("types").map { VType.from(it) }, o.reqArray("items").map { VItem.from(it) }, atts)
        }
    }
}

/** 整份快照 `{v, vaults, data:{<vid>: VData}}`。v:1 / v:2 都收（PC 画布版写 v:2）。 */
data class VSnapshot(val v: Int, val vaults: List<VVaultInfo>, val data: Map<String, VData>) {
    fun toJson(): JsonObject = JsonObject(mapOf(
        "v" to JsonPrimitive(v),
        "vaults" to ja(vaults.map { it.toJson() }),
        "data" to JsonObject(data.mapValues { it.value.toJson() }),
    ))

    fun encode(): String = toJson().toString()

    companion object {
        fun from(e: JsonElement): VSnapshot {
            val o = e.asObject("快照")
            return VSnapshot(o.reqInt("v"), o.reqArray("vaults").map { VVaultInfo.from(it) }, o.reqObject("data").mapValues { VData.from(it.value) })
        }

        fun parse(text: String): VSnapshot? = runCatching { from(VaultJson.parseToJsonElement(text)) }.getOrNull()
        fun parse(bytes: ByteArray): VSnapshot? = parse(String(bytes, Charsets.UTF_8))
    }
}

/** 服务端存的那份 blob：`{"v":1,"kdf":"pbkdf2","salt":"<b64>","verifier":"<hex>","enc":"<v1:...>"}`。 */
data class VRecord(val v: Int, val kdf: String?, val salt: String, val verifier: String, val enc: String) {
    /**
     * **键序固定、字符串拼接**（iOS VaultCore.swift:1153）。salt / verifier / enc 都只含 base64 / hex / `:` 字符，
     * 拼接不需要转义 —— 与 iOS 一字不差，PC 也只是 JSON.parse 它。
     */
    fun encode(): String = "{\"v\":1,\"kdf\":\"${kdf ?: "pbkdf2"}\",\"salt\":\"$salt\",\"verifier\":\"$verifier\",\"enc\":\"$enc\"}"

    companion object {
        fun parse(text: String): VRecord? = runCatching {
            val o = VaultJson.parseToJsonElement(text).asObject("record")
            VRecord(o.reqInt("v"), o.optString("kdf"), o.reqString("salt"), o.reqString("verifier"), o.reqString("enc"))
        }.getOrNull()
    }
}

// MARK: - 本机密文缓存（umbra-ios#28）

/**
 * 本机密文缓存 `umbra-vault-cache.json`：`{blob(=VRecord 字符串), rev, trashCount?, pending?}`。
 * **老缓存文件（没有 trashCount / pending）必须照样解得开** —— 解不开的后果是升级后第一次断网，保险箱变成一块砖。
 */
data class VCache(val blob: String, val rev: Int, val trashCount: Int? = null, val pending: Boolean? = null) {
    /** 缓存里那份记录（salt / verifier / enc）。解不开就是 null。 */
    val record: VRecord? get() = VRecord.parse(blob)

    fun encode(): String = JsonObject(buildMap {
        put("blob", js(blob)); put("rev", JsonPrimitive(rev))
        if (trashCount != null) put("trashCount", JsonPrimitive(trashCount))
        if (pending != null) put("pending", jb(pending))
    }).toString()

    companion object {
        fun parse(text: String): VCache? = runCatching {
            val o = VaultJson.parseToJsonElement(text).asObject("缓存")
            val tc = o["trashCount"]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.intOrNull ?: throw VaultDecodeException("trashCount") }
            VCache(o.reqString("blob"), o.reqInt("rev"), tc, o.optBool("pending"))
        }.getOrNull()
    }
}

/** 读本机缓存的结果。**「没有文件」和「有文件但读不出来」必须分开**（审查台账 vault-cache-read-failure-adopts-cloud）。 */
sealed interface VCacheRead {
    data object Missing : VCacheRead
    data object Unreadable : VCacheRead
    data class Ok(val cache: VCache) : VCacheRead

    val cacheOrNull: VCache? get() = (this as? Ok)?.cache
}

// MARK: - 本机建库的内置类型（= PC `defaultTypes()`）

object VaultDefaults {
    val typeSeeds = listOf("登录" to "🔑", "银行卡" to "💳", "证件/文档" to "🪪", "安全笔记" to "📝")

    fun types(ids: List<String>): List<VType> =
        typeSeeds.zip(ids).mapIndexed { i, (seed, id) -> VType(id, seed.first, seed.second, i.toDouble()) }
}
