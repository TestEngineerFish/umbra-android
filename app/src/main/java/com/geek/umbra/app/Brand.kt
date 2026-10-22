// 产品名的唯一来源（sam 10-22 拍板）：对外叫「悠蜗」，Umbra 只是内部代号。
//
// 改名只改这一个文件，然后跑 `python3 -I tools/gen_brand.py` 和 `python3 -I tools/gen_strings.py`：
// - gen_brand.py 按这里的值生成 res/values*/brand.xml（app_name、brand_name，以及 manifest 里那种没法带 %s 的整句 label）；
// - gen_strings.py 生成 strings_ios.xml 时把 iOS 文案里的「Umbra」换成对应语言的产品名；
// - app/build.gradle.kts 读这里的 productNameEn 给安装包起文件名。
// 三处都用正则读本文件，所以下面三行的写法（const val 名 = "值"）别改。BrandTest 守着资源与这里一致。
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
}
