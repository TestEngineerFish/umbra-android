// 产品名的唯一来源（sam 10-22 拍板）：对外叫「悠蜗」，Umbra 只是内部代号。
//
// 改名只改这一个文件，然后跑 `python3 -I tools/gen_brand.py` 和 `python3 -I tools/gen_strings.py`：
// - gen_brand.py 按这里的值生成 res/values*/brand.xml（app_name、brand_name，以及 manifest 里那种没法带 %s 的整句 label）；
// - gen_strings.py 生成 strings_ios.xml 时把 iOS 文案里的「Umbra」换成对应语言的产品名；
// - app/build.gradle.kts 读这里的 productNameEn 给安装包起文件名。
// 三处都用正则读本文件，所以 productName / productNameEn / codename 的写法（const val 名 = "值"）别改。BrandTest 守着资源与这里一致。
//
// 不改的（用户看不见、改了会断数据或断对端）：applicationId / 包名 / 命名空间 / 类名、Prefs 与 DataStore 键、
// 文件目录名、通知渠道 ID、请求头 X-Umbra-*、剪贴板 label —— 清单见开发文档第五节「品牌名」。
package com.geek.umbra.app

object Brand {
    /** 对外产品名（中文界面、写死中文的文案用它）。 */
    const val productName = "悠蜗"
    /** 英文界面的产品名。英文名还没定，先占位。 */
    const val productNameEn = "Youwo"
    /** 内部代号：包名、类名、请求头、日志里出现的那个名字。不上界面。 */
    const val codename = "Umbra"
    /**
     * 圆角方块里那个字母标（登录页、小组件角标、自动填充面板、小组件说明页）。**还是 U**：换不换是设计侧的事
     * （改名不等于换标），先收成一个常量，定了只改这里。iOS: Brand.swift `monogram`
     */
    const val monogram = "U"
    /**
     * iOS 文案表里代表产品名的占位符（iOS 品牌收口 5fe6fcc 起）。Android 不在运行时替换：
     * tools/gen_strings.py 生成 strings_ios.xml 时就换成了产品名。留着是给 BrandTest 查漏换。
     */
    const val token = "{brand}"

    /** 按界面语言取产品名（不在中文句子里、单独显示的那种：登录页标题、关于、主题作者兜底）。界面里优先用 R.string.brand_name。 */
    fun name(english: Boolean): String = if (english) productNameEn else productName
}
