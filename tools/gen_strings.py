#!/usr/bin/env python3
"""把 iOS 的 Localizable.xcstrings 转成 Android 的 strings.xml（values/ 中文默认，values-en/ 英文）。

为什么生成：两端共用一批 key（点分 key `chat.thread.empty` → 资源名 `chat_thread_empty`），
iOS 改了文案重跑一次即可同步，不会各改各的。
- 点分 key：`.` `-` 换成 `_`；
- 中文字面 key（iOS 里 key 本身就是中文）：资源名 `s_<sha1 前 8 位>`，映射表写到 docs/reference/strings-map.tsv，
  移植时按中文原文查资源名；
- 格式符：%@ → %s，%lld/%ld → %d；一句里有多个参数时一律补成位置参数（%1$s），否则 lint 报错；
- 只有中文没有英文的条目只写进 values/，英文模式下回落中文（与 iOS 行为一致）；
- 产品名（英文表用 displayNameEn，10-23 起即英文名 productNameEn）：iOS 文案里的占位符 {brand}（iOS 品牌收口 5fe6fcc 起，取字入口统一替换）和残留的整词「Umbra」
  都换成 app/Brand.kt 的产品名，中文表用 productName、英文表用 displayNameEn；换完按空格规则去掉名字与中文之间的空格。生成时就换掉，不映射成 %s：
  Android 资源里一句话没法引用另一条资源，映射成 %s 就得让每个调用处都补一个参数。
  key 不动（中文字面 key 的资源名仍按 iOS 原文算），所以 Kotlin 里的 R.string 引用不受改名影响。
用法：python3 -I tools/gen_strings.py   （UmbraAndroid 根目录，要求 ../UmbraiOS 并排；在 worktree 里跑时用 UMBRA_IOS=<iOS 仓路径> 指定）
"""
import os, hashlib, json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
# worktree 在 ../UmbraAndroid-wt/<名>/ 下，并排推不出 iOS 仓，所以留一个环境变量口子
ios = pathlib.Path(os.environ.get("UMBRA_IOS") or root.parent / "UmbraiOS")
src = ios / "UmbraiOS/Resources/Localizable.xcstrings"
if not src.exists():
    sys.exit(f"找不到 {src}")
data = json.loads(src.read_text())["strings"]

# 产品名只在 Brand.kt 一处（tools/gen_brand.py 的 load()）。-I 模式下脚本目录不在 sys.path，按路径加载。
import importlib.util
sys.dont_write_bytecode = True  # 别在 tools/ 下留 __pycache__
_spec = importlib.util.spec_from_file_location("gen_brand", root / "tools/gen_brand.py")
_gb = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_gb)
BRAND = _gb.load()
# 整词匹配：UmbraPC、X-Umbra-Session 这类标识符里的不算（前后是字母或连字符就不换）。
CODENAME = re.compile(r"(?<![A-Za-z\-])" + re.escape(BRAND["codename"]) + r"(?![A-Za-z\-])")

# 中文字符与中文标点（「」、。在 CJK 符号区，，：；（）！？在全角区）。「·」「›」不在里面：分隔符带的空格照留。
CJK = r"[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]"

def brand(s: str, lang: str) -> str:
    # 英文表读 displayNameEn（10-23 起 = productNameEn）。
    name = BRAND["productName"] if lang == "zh" else BRAND["displayNameEn"]
    # 残留的整词代号：iOS 以中文原文作 key、没有译文的那批旧条目里还写着 Umbra。
    s = CODENAME.sub(name, s.replace("{brand}", name))
    # 空格规则：产品名挨着汉字或中文标点时两边不留空格（「用 Umbra 账号」这种旧句子换名后会留下两个多余的空格）。
    n = re.escape(name)
    s = re.sub(rf"(?<={CJK}) +(?={n})", "", s)
    return re.sub(rf"(?<={n}) +(?={CJK})", "", s)

FMT = re.compile(r"%(\d+\$)?(@|lld|ld|d|lf|f|%)")

def conv_fmt(s: str) -> str:
    specs = [m for m in FMT.finditer(s) if m.group(2) != "%"]
    positional = len(specs) > 1
    idx = 0
    def rep(m):
        nonlocal idx
        pos, kind = m.group(1), m.group(2)
        if kind == "%":
            return "%%"
        idx += 1
        t = {"@": "s", "lld": "d", "ld": "d", "d": "d", "lf": "f", "f": "f"}[kind]
        if pos:
            return f"%{pos}{t}"
        return f"%{idx}${t}" if positional else f"%{t}"
    return FMT.sub(rep, s)

def esc(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s

def name_of(key: str) -> str:
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_.\-]*", key):
        n = re.sub(r"[.\-]", "_", key)
        return n if not n[0].isdigit() else "s_" + n
    return "s_" + hashlib.sha1(key.encode()).hexdigest()[:8]

def val(entry, lang):
    loc = entry.get("localizations", {}).get(lang, {})
    return loc.get("stringUnit", {}).get("value")

zh, en, mapping, seen = [], [], [], {}
for key in sorted(data):
    e = data[key]
    n = name_of(key)
    if n in seen and seen[n] != key:
        n = n + "_" + hashlib.sha1(key.encode()).hexdigest()[:4]
    seen[n] = key
    z = val(e, "zh-Hans") or key
    has_fmt = bool([m for m in FMT.finditer(z) if m.group(2) != "%"])
    attr = "" if has_fmt else ' formatted="false"' if "%" in z else ""
    zh.append(f'    <string name="{n}"{attr}>{esc(conv_fmt(brand(z, "zh")))}</string>')
    v = val(e, "en")
    if v:
        en.append(f'    <string name="{n}"{attr}>{esc(conv_fmt(brand(v, "en")))}</string>')
    if n.startswith("s_"):
        mapping.append(f"{n}\t{key}")

head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 自动生成，请勿手改：tools/gen_strings.py ← UmbraiOS Localizable.xcstrings -->\n<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation,UnusedResources,Typos">\n'
for d, rows in (("values", zh), ("values-en", en)):
    p = root / f"app/src/main/res/{d}/strings_ios.xml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(head + "\n".join(rows) + "\n</resources>\n")
(root / "docs/reference/strings-map.tsv").write_text("资源名\t中文原文（iOS key）\n" + "\n".join(mapping) + "\n")
print(f"zh {len(zh)} 条，en {len(en)} 条，中文字面 key 映射 {len(mapping)} 条")
