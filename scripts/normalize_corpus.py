#!/usr/bin/env python3
"""归一化语料：剥离 OCR 免责声明，合并章节，输出带书卷标记的主语料文件。"""
import re
from pathlib import Path

ROOT = Path("/home/aci/桌面/fengshui-app")
RAW = ROOT / "corpus" / "raw"
OUT = ROOT / "corpus" / "proofed"

OCR_DISCLAIMER = re.compile(r"該資料是通過對對應底本影印本進行.*?協助糾正\n*\n*。*\n*", re.S)


def strip_disclaimer(text: str) -> str:
    text = OCR_DISCLAIMER.sub("", text).strip()
    i = text.find("若有任何意見或建議")
    if i != -1:
        text = text[:i].strip()
    j = text.find("喜歡我們的網站")
    if j != -1:
        text = text[:j].strip()
    return text


def read(name: str) -> str:
    return (RAW / name).read_text(encoding="utf-8").strip()


def merge(title: str, parts: list[tuple[str, str]], out_name: str):
    out = []
    for label, content in parts:
        body = strip_disclaimer(content)
        if body:
            out.append(f"〔{title}·{label}〕\n{body}")
    (OUT / out_name).write_text("\n\n".join(out) + "\n", encoding="utf-8")
    n = sum(len(c) for _, c in parts)
    print(f"[OK] {out_name} 合并完成，原始约{n}字")


def main():
    OUT.mkdir(exist_ok=True)
    merge("葬书", [("内篇", read("zangshu_neipian.txt")),
                   ("外篇", read("zangshu_waipian.txt")),
                   ("杂篇", read("zangshu_zapian.txt"))], "zangshu.txt")
    merge("宅经", [("卷上", read("zhaijing_juanshang.txt")),
                   ("卷下", read("zhaijing_juanxia.txt"))], "zhaijing.txt")
    merge("发微论", [("全文", read("faweilun.txt"))], "faweilun.txt")
    merge("宅法举隅", [("全文", read("zhaifajuyu.txt"))], "zhaifajuyu.txt")
    parts = []
    for c in ("412426", "242309", "413787", "734756", "966144", "975428", "383416"):
        parts.append((f"卷{c}", read(f"yangzhai_shishu_part{c}.txt")))
    merge("阳宅十书", parts, "yangzhai_shishu.txt")
    dq = []
    for c in ("1", "2", "3", "4", "5"):
        dq.append((f"卷{c}", read(f"yangzhai_daquan_{c}.txt")))
    merge("阳宅大全", dq, "yangzhai_daquan.txt")


if __name__ == "__main__":
    main()
