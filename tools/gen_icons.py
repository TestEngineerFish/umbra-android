#!/usr/bin/env python3
"""从 iOS 的 UmbraIconPath.swift 生成 Android 的 UmbraIconPaths.kt。

为什么生成而不是手抄：图标 path 是设计稿里的 Lucide 近似图形，iOS 那份已经归一化成
只含 M/L/C/Z 的绝对坐标。两端读同一份数据，图标才不会各漂各的。
用法：python3 -I tools/gen_icons.py   （在 UmbraAndroid 根目录跑，要求 ../UmbraiOS 并排）
"""
import re, sys, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
src = root.parent / "UmbraiOS/UmbraiOS/DesignSystem/UmbraIconPath.swift"
out = root / "app/src/main/java/com/geek/umbra/designsystem/UmbraIconPaths.kt"
if not src.exists():
    sys.exit(f"找不到 iOS 图标表：{src}")
items = re.findall(r'static let (\w+)\s*=\s*"([^"]+)"', src.read_text())
lines = [
    "// 自动生成，请勿手改 —— 来源 UmbraiOS/UmbraiOS/DesignSystem/UmbraIconPath.swift，",
    "// 生成脚本 tools/gen_icons.py。viewBox 一律 0 0 24 24，只含 M/L/C/Z（个别手补的带 A）。",
    "package com.geek.umbra.designsystem",
    "",
    "/** 图标 path 表。名字取 Lucide 图形名，与 iOS 同名。 */",
    "object UmbraIconPaths {",
]
for name, d in items:
    lines.append(f'    const val {name} = "{d}"')
lines.append("}")
out.write_text("\n".join(lines) + "\n")
print(f"生成 {len(items)} 个图标 → {out.relative_to(root)}")
