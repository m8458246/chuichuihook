#!/usr/bin/env python3
"""从 res 目录生成 R.java stub，供本地 javac 类型检查使用（构建仍由 AGP 完成）"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "typecheck", "R.java")
PKG = "com.cycy.chuichui"


def main():
    ids = set()
    strings = set()
    layouts = set()
    drawables = set()

    for dirpath, _, files in os.walk(RES):
        for f in files:
            p = os.path.join(dirpath, f)
            try:
                text = open(p, encoding="utf-8").read()
            except Exception:
                continue
            for m in re.finditer(r'@\+id/(\w+)', text):
                ids.add(m.group(1))
            if dirpath.endswith("values"):
                for m in re.finditer(r'<string\s+name="(\w+)"', text):
                    strings.add(m.group(1))
            if os.path.basename(dirpath) == "layout":
                layouts.add(os.path.splitext(f)[0])
            if os.path.basename(dirpath) == "drawable":
                drawables.add(os.path.splitext(f)[0])

    lines = ["package %s;" % PKG, "", "public final class R {"]

    def emit(cls, names, base):
        lines.append("    public static final class %s {" % cls)
        for i, n in enumerate(sorted(names)):
            lines.append("        public static final int %s = 0x%08x;" % (n, base + i))
        lines.append("    }")

    emit("id", ids, 0x7f010000)
    emit("layout", layouts, 0x7f020000)
    emit("string", strings, 0x7f030000)
    emit("drawable", drawables, 0x7f040000)
    lines.append("}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("R.java: id=%d layout=%d string=%d drawable=%d" %
          (len(ids), len(layouts), len(strings), len(drawables)))


if __name__ == "__main__":
    main()
