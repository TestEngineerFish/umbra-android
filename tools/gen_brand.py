#!/usr/bin/env python3
"""按 app/.../app/Brand.kt 生成 res/values/brand.xml（中文）与 res/values-en/brand.xml（英文）。

为什么生成：Android 资源里一句话没法引用另一条资源，manifest 的 label 也不能带 %s，
所以「启动器名」「系统分享面板里的那一项」这种整句只能在资源里写死 —— 写死的那份由这里按 Brand.kt 出，
产品名就只有 Brand.kt 一处。界面里能带参数的句子不放这里，用 %1$s 引用 brand_name。
用法：python3 -I tools/gen_brand.py   （UmbraAndroid 根目录）；gen_strings.py 也按 load() 读同一份值。
"""
import pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
BRAND_KT = root / "app/src/main/java/com/geek/umbra/app/Brand.kt"

# 整句 label：{name} 换成该语言的产品名。只放 manifest / xml 里引用、没法格式化的那几句。
LABELS = {
    "app_name": ("{name}", "{name}"),
    "brand_name": ("{name}", "{name}"),
    # 系统分享面板里那一项（AndroidManifest ShareActivity 的 label），面板标题也用它。
    "share_insp_title": ("记到 {name} 灵感", "Save to {name}"),
}

def load() -> dict:
    """读 Brand.kt 里的 const val；返回 {productName, productNameEn, codename}。"""
    vals = dict(re.findall(r'const val (\w+) = "([^"]*)"', BRAND_KT.read_text()))
    for k in ("productName", "productNameEn", "codename"):
        if not vals.get(k):
            sys.exit(f"Brand.kt 里缺 {k}")
    return vals

def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'")

if __name__ == "__main__":
    b = load()
    head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 自动生成，请勿手改：tools/gen_brand.py ← app/Brand.kt -->\n<resources>\n'
    for d, i, name in (("values", 0, b["productName"]), ("values-en", 1, b["productNameEn"])):
        rows = [f'    <string name="{k}">{esc(v[i].format(name=name))}</string>' for k, v in LABELS.items()]
        (root / f"app/src/main/res/{d}/brand.xml").write_text(head + "\n".join(rows) + "\n</resources>\n")
    print(f"brand.xml：{b['productName']} / {b['productNameEn']}")
