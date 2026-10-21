package com.geek.umbra.feature.convert

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用电脑转换」纯判据的行为用例 —— 逐条照抄 iOS `Tools/swifttests/ConvertRulesTests.swift`（批次 269 §四 / 271 / 272）。
 * 写成「一个计数器 + 最后一起断言」：条数能和 iOS 对上，一处不过也能看到其余全部。
 * 文案模板用 Android 的写法（`%1$s` 而不是 `%1$@`），其余输入输出不变。
 */
class ConvertRulesTest {
    private val failures = mutableListOf<String>()
    private var checked = 0

    private fun <T> expect(got: T, want: T, what: String) {
        checked++
        if (got != want) failures += "✗ $what：得到 $got，应当是 $want"
    }

    @Test fun all() {
        val R = ConvertRules
        // 上限与超时
        expect(R.MAX_BYTES, 32 * 1024 * 1024, "上限同服务端 /files/upload 的 32 MiB")
        expect(R.MAX_MB, 32, "文案里那个 32 由上限算出来")
        expect(R.ASK_TIMEOUT_SECONDS, 60, "没回话的秒数 = 服务端同步派活的 60 秒，不是稿上占位的 15")
        expect(R.ASK_REQUEST_TIMEOUT_MS > R.ASK_TIMEOUT_SECONDS * 1000L, true, "请求超时比服务端那 60 秒长，让 502 先到")

        // 选中哪一格（批次 272）
        expect(R.preselect("pdf", listOf("jpg", "pdf")), "pdf", "带来的格式这台还能转就留着")
        expect(R.preselect("docx", listOf("pdf", "jpg")), "pdf", "这次转不了它就落到第一格")
        expect(R.preselect(null, listOf("png")), "png", "没带格式照常第一格")
        expect(R.preselect("pdf", emptyList()), null, "一格都没有就不选")

        // 名字
        expect(R.ext("合同.DOCX"), "docx", "扩展名转小写")
        expect(R.ext("无扩展名"), "", "没有扩展名")
        expect(R.badge("合同.docx"), "DOCX", "方章写大写扩展名")
        expect(R.badge("a.markdown"), "MARK", "方章最多 4 个字符")
        expect(R.badge("README"), "FILE", "没扩展名写 FILE")
        expect(R.localName("合同.pdf", "pdf"), "合同.pdf", "正常名字原样")
        expect(R.localName("../../etc/合同.pdf", "pdf"), "合同.pdf", "路径只取最后一段")
        expect(R.localName("a\\b\\c.jpg", "jpg"), "c.jpg", "反斜杠也当分隔")
        expect(R.localName("", "mp4"), "converted.mp4", "空名字给兜底")
        expect(R.localName(".hidden", "pdf"), "converted.pdf", "点开头的不要")

        // 成品类别 → 结果页的钮
        expect(R.kind("IMG_0412.jpg"), ConvertKind.Image, "jpg 是图片（多一颗存到相册）")
        expect(R.kind("a.HEIC"), ConvertKind.Image, "HEIC 是图片，大小写不论")
        expect(R.kind("clip.mov"), ConvertKind.Video, "mov 是视频")
        expect(R.kind("合同.pdf"), ConvertKind.Document, "pdf 是文档")
        expect(R.kind("x.mp3"), ConvertKind.Document, "音频存不进相册，按文档给钮")

        // 大小
        expect(R.sizeText(2_516_582), "2.4 MB", "MB 一位小数")
        expect(R.sizeText(188_743_680), "180 MB", "100 MB 以上不带小数")
        expect(R.sizeText(839_680), "820 KB", "不到 1 MB 写 KB")
        expect(R.sizeText(10), "1 KB", "太小也写 1 KB")

        // 格子排序
        expect(R.ordered(listOf("txt", "rtf", "html", "pdf", "mp3", "docx")) { it }, listOf("pdf", "docx", "mp3", "html", "rtf", "txt"), "PDF、DOCX、MP3 排前，其余按字母")

        // 第二段百分比
        expect(R.convertFraction(null), null, "不知道进度 → 不定进度条")
        expect(R.convertFraction(0.0), null, "取文件那一刻的 0 不画成 0%")
        expect(R.convertFraction(0.5), 0.5, "0.5 → 一半")
        expect(R.convertFraction(0.9), 1.0, "0.9 → 转换那一截到头了")
        expect(R.convertFraction(0.95), null, "电脑在传回成品 → 不定进度条")
        expect(R.percent(0.999), 99, "百分比向下取整：没到不写 100")

        // 时间
        val a = R.parseDate("2026-10-19T08:00:00.123456+00:00")
        val b = R.parseDate("2026-10-19 08:00:00")
        expect(a != null, true, "Python isoformat 6 位小数秒认得出")
        expect(a, b, "SQLite 那种无时区的按 UTC，和带 +00:00 的是同一刻")
        expect(R.parseDate("昨天"), null, "认不出 → null")
        expect(R.parseDate(null), null, "没给 → null")

        // 本机记着的作业
        val now = 1_000_000_000_000L
        val r1 = ConvertJobRecord("convjob-1", "mac", "书房 Mac", "合同.docx", "pdf", now - 1000)
        val old = ConvertJobRecord("convjob-0", "mac", "书房 Mac", "旧.docx", "pdf", now - R.RECORD_KEEP_MS - 1)
        var list = R.remember(r1, listOf(old), now)
        expect(list.map { it.id }, listOf("convjob-1"), "超过 24 小时的记录扔掉（成品已被清理）")
        list = R.remember(r1, list, now)
        expect(list.size, 1, "同一件记两次只留一份")

        val done = ConvertJobBrief("convjob-1", "done", "mac", "合同.docx", "合同.pdf", now)
        expect(R.banner(list, listOf(done), "mac"), ConvertBanner("convjob-1", "合同.pdf", true), "转好了的写成品名")
        expect(R.banner(list, listOf(done), "win"), null, "别的电脑的详情页不挂")
        val running = ConvertJobBrief("convjob-1", "running", "mac", "合同.docx", null, now)
        expect(R.banner(list, listOf(running), "mac"), null, "正在转的不挂")
        val other = ConvertJobBrief("convjob-9", "done", "mac", "别人.docx", "别人.pdf", now)
        expect(R.banner(list, listOf(other), "mac"), null, "不是这台手机发起的不挂")
        val failed = ConvertJobBrief("convjob-1", "failed", "mac", "合同.docx", null, now)
        expect(R.banner(list, listOf(failed), "mac"), ConvertBanner("convjob-1", "合同.docx", false), "没转成的写原文件名")
        val seen = R.markSeen("convjob-1", list)
        expect(R.banner(seen, listOf(done), "mac"), null, "看过的不再挂")
        val r2 = ConvertJobRecord("convjob-2", "mac", "书房 Mac", "图.heic", "jpg", now)
        val newer = ConvertJobBrief("convjob-2", "done", "mac", "图.heic", "图.jpg", now + 5)
        expect(R.banner(list + r2, listOf(done, newer), "mac")?.jobId, "convjob-2", "两件都没看过挂最新的")

        // 推送（Android 今天收不到，判据先备着）
        expect(R.pushJob(mapOf("convert_job" to "convjob-ab12", "aps" to mapOf("thread-id" to "convert"))), "convjob-ab12", "根上的 convert_job")
        expect(R.pushJob(mapOf("message_id" to 3)), null, "执勤那条不认")
        expect(R.pushJob(mapOf("convert_job" to "../x")), null, "要拼进路径的 id 不收斜杠和点")
        expect(R.pushJob(mapOf("convert_job" to "")), null, "空的不收")
        expect(R.pushJob(mapOf("convert_job" to 12)), null, "不是字符串不收")

        // 进度屏保留时长那句里的成品名（批次 271）
        expect(R.expectedResultName("合同.docx", "PDF"), "合同.pdf", "去掉原扩展名 + 小写目标扩展名")
        expect(R.expectedResultName("a.b.heic", "jpg"), "a.b.jpg", "只去最后一段扩展名")
        expect(R.expectedResultName("README", "pdf"), "README.pdf", "没扩展名直接接")
        expect(R.expectedResultName("", "pdf"), null, "名字空的拼不出")
        expect(R.expectedResultName("合同.docx", ""), null, "目标空的拼不出")

        // 「X 上转不了」的原因改写（批次 271）
        val intel = "%1\$s 上的 %2\$s 是 Intel 版，用不了"
        expect(R.blockedReason("Pandoc 是 Intel 版", "书房 Mac", intel, "缺工具"), "书房 Mac 上的 Pandoc 是 Intel 版，用不了", "Intel 版改写成带电脑名的整句")
        expect(R.blockedReason("LibreOffice Writer 是 Intel 版", "书房 Mac", intel, "缺工具"), "书房 Mac 上的 LibreOffice Writer 是 Intel 版，用不了", "工具名带空格也认")
        expect(R.blockedReason("没装 ffmpeg", "书房 Mac", intel, "缺工具"), "没装 ffmpeg", "其它原因原样")
        expect(R.blockedReason("Pandoc 是 Intel 版，太旧", "书房 Mac", intel, "缺工具"), "Pandoc 是 Intel 版，太旧", "不是整句匹配的不改")
        expect(R.blockedReason(null, "书房 Mac", intel, "缺工具"), "缺工具", "没原因给兜底")
        expect(R.blockedReason("  ", "书房 Mac", intel, "缺工具"), "缺工具", "空白原因给兜底")
        expect(R.blockedReason("Pandoc 是 Intel 版", "", intel, "缺工具"), "Pandoc 是 Intel 版", "电脑名空的不拼半截话")

        // 输入
        expect(ConvertInput.File(ConvertSource("a.png", byteArrayOf(1, 2))).bytes, 2, "读进来的按字节数")
        expect(ConvertInput.TooLarge("大.mov", 99).name, "大.mov", "太大的只记名字和大小")

        println("ConvertRules：$checked 条，${failures.size} 条不过")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
