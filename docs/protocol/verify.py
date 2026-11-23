#!/usr/bin/env python3
"""协议 docset 结构验证。

检查（任一不满足即非 0 退出）：
  1. 每个 ### 条目必须有 `状态：`，且取值只能是 CONFIRMED / INFERRED / OPEN；
  2. 必须有非空 `证据：`；
  3. OPEN 条目必须有 `关闭条件：`；
  4. `代码：` 里引用的路径必须真实存在（防止文档腐烂）。

规则出处见 AGENT.md；这份检查会被 tools/verify.sh 调用。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
STATUSES = {"CONFIRMED", "INFERRED", "OPEN"}


def split_items(text):
    return re.split(r"(?m)^### ", text)[1:]


def check_file(path):
    errs = []
    blocks = split_items(path.read_text(encoding="utf-8"))
    if not blocks:
        errs.append("没有任何 ### 条目")
    for block in blocks:
        title = block.splitlines()[0].strip()
        st = re.search(r"(?m)^状态：\s*(\S+)", block)
        ev = re.search(r"(?m)^证据：\s*(\S.*)$", block)
        if not st:
            errs.append(f"{title}：缺 `状态：`")
        elif st.group(1) not in STATUSES:
            errs.append(f"{title}：状态必须是 {'/'.join(sorted(STATUSES))}，实际 `{st.group(1)}`")
        if not ev:
            errs.append(f"{title}：缺 `证据：`")
        if st and st.group(1) == "OPEN" and not re.search(r"(?m)^关闭条件：\s*\S", block):
            errs.append(f"{title}：OPEN 必须写 `关闭条件：`")
        for m in re.finditer(r"(?m)^代码：\s*(.+)$", block):
            for raw in re.split(r"[、,，\s]+", m.group(1).strip()):
                if raw and not (ROOT / raw).exists():
                    errs.append(f"{title}：代码路径不存在 `{raw}`")
    return len(blocks), errs


def main():
    files = sorted(p for p in HERE.glob("*.md") if p.name != "README.md")
    if not files:
        print("FAIL 没有 docset（docs/protocol/*.md）")
        return 1
    total = 0
    bad = 0
    for path in files:
        n, errs = check_file(path)
        total += n
        if errs:
            bad += 1
            print(f"FAIL {path.name}（{n} 条）")
            for e in errs:
                print("   -", e)
        else:
            print(f"PASS {path.name}（{n} 条）")
    print(f"\n协议 docset：{len(files)} 个文件 / {total} 条，FAIL {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
