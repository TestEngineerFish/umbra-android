#!/usr/bin/env python3
"""把 iOS 的 Localizable.xcstrings 转成 Android 的 strings.xml（values/ 中文默认，values-en/ 英文）。

为什么生成：两端共用一批 key（点分 key `chat.thread.empty` → 资源名 `chat_thread_empty`），
iOS 改了文案重跑一次即可同步，不会各改各的。
- 点分 key：`.` `-` 换成 `_`；
- 中文字面 key（iOS 里 key 本身就是中文）：资源名 `s_<sha1 前 8 位>`，映射表写到 docs/reference/strings-map.tsv，
  移植时按中文原文查资源名；
- 格式符：%@ → %s，%lld/%ld → %d；一句里有多个参数时一律补成位置参数（%1$s），否则 lint 报错；
- 只有中文没有英文的条目只写进 values/，英文模式下回落中文（与 iOS 行为一致）。
用法：python3 -I tools/gen_strings.py   （UmbraAndroid 根目录，要求 ../UmbraiOS 并排）
"""
import hashlib, json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
src = root.parent / "UmbraiOS/UmbraiOS/Resources/Localizable.xcstrings"
if not src.exists():
    sys.exit(f"找不到 {src}")
data = json.loads(src.read_text())["strings"]

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
    zh.append(f'    <string name="{n}"{attr}>{esc(conv_fmt(z))}</string>')
    v = val(e, "en")
    if v:
        en.append(f'    <string name="{n}"{attr}>{esc(conv_fmt(v))}</string>')
    if n.startswith("s_"):
        mapping.append(f"{n}\t{key}")

head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 自动生成，请勿手改：tools/gen_strings.py ← UmbraiOS Localizable.xcstrings -->\n<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation,UnusedResources,Typos">\n'
for d, rows in (("values", zh), ("values-en", en)):
    p = root / f"app/src/main/res/{d}/strings_ios.xml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(head + "\n".join(rows) + "\n</resources>\n")
(root / "docs/reference/strings-map.tsv").write_text("资源名\t中文原文（iOS key）\n" + "\n".join(mapping) + "\n")
print(f"zh {len(zh)} 条，en {len(en)} 条，中文字面 key 映射 {len(mapping)} 条")
