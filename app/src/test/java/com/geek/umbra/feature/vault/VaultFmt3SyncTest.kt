// 保险箱树形（fmt 3）接进同步那一层的行为用例：快照读写 / 兼容键、混合格式的合并、迁移时机、fmt 2 备份、426 `upgrade_required`。
// 方案正本 doc/保险箱树形-实现方案.md §一第 1 条、§二第 2–6 条、§三第 3 条。
package com.geek.umbra.feature.vault

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VaultFmt3SyncTest {
    private fun canvasItem(id: String, rev: Double, pwd: String) = VItem.from(jo(mapOf(
        "id" to js(id), "typeId" to js(""), "title" to js(id), "blocks" to ja(emptyList()), "attachments" to ja(emptyList()),
        "createdAt" to jn(1.0), "updatedAt" to jn(rev), "revision" to jn(rev), "fmt" to jn(2.0), "edges" to ja(emptyList()),
        "nodes" to ja(listOf(jo(mapOf("id" to js("${id}a"), "kind" to js("acct"), "x" to jn(0.0), "y" to jn(0.0), "title" to js("账号"),
            "data" to jo(mapOf("user" to js("u"), "pwd" to js(pwd))), "set" to jo(emptyMap()))))),
    )))

    private fun fmt2(vararg items: VItem) = VSnapshot(2, emptyList(), mapOf("v" to VData(emptyList(), items.toList(), emptyMap())))

    @Test fun snapshotRoundTripKeepsCompatKeys() {
        val t = VaultMigrate3.migrate(listOf(canvasItem("r1", 3.0, "p").toJson()))
        val d = VData(emptyList(), emptyList(), emptyMap(), 3, t, mapOf("future" to js("留着")))
        val json = d.toJson()
        // 旧版 iOS / Android 的 VData 三个必填键都在，提示记录照 fmt 2 的规矩解得开（解不开 = 旧版「解密失败」）。
        val items = json["items"] as JsonArray
        assertEquals(1, items.size)
        val notice = VItem.from(items[0])
        assertEquals(Fmt3.NOTICE_ID, notice.id)
        assertTrue("旧版把它当画布记录列出来", notice.isCanvas && notice.nodes.size == 1)
        assertTrue(json["types"] is JsonArray && json["attachments"] is JsonObject)
        val back = VData.from(json)
        assertEquals(t, back.tree)
        assertEquals("库这一层不认识的键原样带回", js("留着"), back.extra["future"])
        assertTrue("树形库读进来不显示提示记录", back.items.isEmpty())
        val snap = VSnapshot(3, emptyList(), mapOf("v" to back))
        assertEquals(snap, VSnapshot.parse(snap.encode()))
        assertEquals(3, snap.pushFmt)
        assertNull("全是 fmt 2 时 PUT 不带 fmt", fmt2(canvasItem("r1", 1.0, "p")).pushFmt)
    }

    @Test fun futureFormatIsReadOnly() {
        val t = VaultMigrate3.migrate(listOf(canvasItem("r1", 3.0, "p").toJson()))
        assertTrue(VSnapshot(4, emptyList(), emptyMap()).fromFuture)
        val fmt4 = JsonObject(Fmt3.dataJson(t) + ("fmt" to jn(4.0)))
        val d = VData.from(fmt4)
        assertEquals(4, d.fmt)
        assertNotNull("fmt 4 留着 fmt 3 的兼容键时照样读得出树", d.tree)
        assertTrue(VSnapshot(3, emptyList(), mapOf("v" to d)).fromFuture)
        assertFalse(VSnapshot(3, emptyList(), mapOf("v" to VData.from(Fmt3.dataJson(t)))).fromFuture)
    }

    /** 云端已是 fmt 3、本机还是 fmt 2：合并时本机那份按确定性迁移迁成树再逐节点比，结果是树形、快照 v = 3。 */
    @Test fun mergeMixedFormats() {
        val local = fmt2(canvasItem("r1", 2.0, "本机"))
        val remoteTree = VaultMigrate3.migrate(listOf(canvasItem("r1", 2.0, "本机").toJson()))
            .let { t -> t.withNode(t.node("r1a")!!.copy(rev = 1.0, updatedAt = 9.0, data = mapOf("user" to js("u"), "pwd" to js("云端")))) }
        val remote = VSnapshot(3, emptyList(), mapOf("v" to VData(emptyList(), emptyList(), emptyMap(), 3, remoteTree)))
        for ((a, b) in listOf(local to remote, remote to local)) {
            val m = VaultSyncRules.merge(a, b)
            assertEquals(3, m.v)
            val t = m.data["v"]!!.tree!!
            assertEquals("云端迁移后改过的那份赢（本机迁出来的内容和迁移前一样，不出副本）", js("云端"), t.node("r1a")!!.data["pwd"])
            assertEquals(listOf("r1", "r1a"), t.nodes.map { it.id })
        }
        // 两边都还是 fmt 2：照旧按记录合并，不迁。
        assertNull(VaultSyncRules.merge(local, fmt2(canvasItem("r2", 1.0, "x"))).data["v"]!!.tree)
    }

    @Test fun whenToMigrate() {
        assertTrue("没登录的本机库直接迁", VaultSyncRules.shouldMigrate(false, null, null))
        assertFalse("登录着、还不知道云端什么样：先不迁", VaultSyncRules.shouldMigrate(true, null, null))
        assertFalse("开关没翻、云端还是 2：不迁", VaultSyncRules.shouldMigrate(true, 2, 2))
        assertTrue("云端已是 3", VaultSyncRules.shouldMigrate(true, 3, 2))
        assertTrue("开关翻了", VaultSyncRules.shouldMigrate(true, 2, 3))
    }

    @Test fun fmt2BackupKeepsNewestFor90Days() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        val a = VaultSyncRules.Fmt2Backup.name(now - 100 * day)
        val b = VaultSyncRules.Fmt2Backup.name(now - 10 * day)
        val c = VaultSyncRules.Fmt2Backup.name(now - day)
        assertEquals(now - day, VaultSyncRules.Fmt2Backup.timeOf(c))
        val other = listOf("umbra-vault-cache.json", VaultSyncRules.CacheBackup.name(now))
        assertEquals("只留最近一份，别的文件不碰", setOf(a, b), VaultSyncRules.Fmt2Backup.prune(listOf(a, c, b) + other, now).toSet())
        assertEquals("最近那份也过了 90 天就清", listOf(a), VaultSyncRules.Fmt2Backup.prune(listOf(a), now))
        assertFalse("两组备份互不清理", VaultSyncRules.CacheBackup.isBackup(c))
    }

    /** 426 `upgrade_required`：不重试、不 force，落到「要升级」那一档（不给［重试］）。 */
    @Test fun upgradeRequiredStopsRetrying() = runBlocking {
        var sends = 0
        val out = VaultSyncRules.run(
            sameUnlock = { true }, encode = { "blob" },
            send = { _, _ -> sends++; VaultPutReply.UpgradeRequired },
            merge = { _, _ -> VaultMergeResult.Merged },
        )
        assertEquals(VaultPushOutcome.UpgradeRequired, out)
        assertEquals(1, sends)
        assertEquals(VaultSyncRules.ProblemUpdate.UpgradeRequired, VaultSyncRules.problemAfter(out, false, null))
    }

    /**
     * 方案〇第 4 条的坑：旧 syncRev 等于云端版本号时，迁移完照常 have_rev 去拉会得到「没有新的」，本机迁出来的旧数据就会盖掉云端的树。
     * Store 不能在 JVM 上起（要 AppGraph），这里钉住源码里那两处：迁移后 syncRev 归零、拉取中途迁了就整份重拉。
     */
    @Test fun migrationResetsSyncRev() {
        val src = listOf(File("src/main/java/com/geek/umbra/feature/vault/VaultStore.kt"), File("app/src/main/java/com/geek/umbra/feature/vault/VaultStore.kt"))
            .first { it.exists() }.readText()
        val body = src.substringAfter("private fun migrateIfDue(): Boolean {").substringBefore("private fun backupFmt2Cache")
        assertTrue("迁移后 syncRev 归零", "syncRev = 0" in body)
        assertTrue("先备份再写", body.indexOf("backupFmt2Cache()") < body.indexOf("encodeForPush()"))
        assertTrue("自检没过就不写", "selfCheck(" in body && "check(bad.isEmpty())" in body)
        assertTrue("拉取中途迁了就整份重拉", "migrateIfDue()) return pullMerge()" in src)
        assertTrue("PUT 带 fmt", "put(\"fmt\", fmt)" in src)
    }
}
