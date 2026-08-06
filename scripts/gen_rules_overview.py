#!/usr/bin/env python3
"""生成 docs/规则总览.md：把 rules/draft/*.json 转成可读中文文档。"""
import json
import glob
from collections import OrderedDict

FILES = [
    ("yszs_batch1.json", "阳宅十书（明·王君荣）", "批1"),
    ("zfj_batch2.json", "宅法举隅（清·锡山）", "批2"),
    ("zj_batch2b.json", "宅经（托名黄帝，唐）", "批2"),
    ("zss_fwl_batch3.json", "葬书（晋·郭璞）+ 发微论（宋）", "批3"),
    ("furniture_batch4.json", "家具批（宅法举隅/阳宅十书推演）", "批4"),
    ("office_batch5.json", "办公批（宅法举隅·衙署/学宫/店铺）", "批5"),
]

STATUS = {"active": "✅ active", "dormant": "○ dormant", "hidden": "✗ hidden"}
SEV = {"大吉": "🟢大吉", "吉": "🟢吉", "平": "⚪平", "凶": "🔴凶", "大凶": "🔴大凶"}


def main():
    lines = []
    lines.append("# 风水规则总览（74 条）\n")
    lines.append("> 生成自 rules/draft/*.json | 全部通过原文子串校验\n")
    lines.append("> 状态：✅active=当前可触发 ｜ ○dormant=缺检测能力待激活 ｜ ✗hidden=冲突裁决隐藏不呈现\n")
    lines.append("> 引用年代序：葬书(晋) → 宅经(唐) → 发微论(宋) → 阳宅十书(明) → 宅法举隅(清)\n")

    for fn, title, batch in FILES:
        rules = json.load(open(f"rules/draft/{fn}", encoding="utf-8"))
        lines.append(f"\n## {batch}｜{title}（{len(rules)} 条）\n")
        for r in rules:
            rid = r["ruleId"]
            lines.append(f"### {rid}｜{r['title']}")
            lines.append(f"- 吉凶：{SEV.get(r['severity'], r['severity'])}　状态：{STATUS[r['status']]}")
            sc = r.get("scenario", ["residential"])
            lines.append(f"- 场景：{'/'.join('住宅' if s == 'residential' else ('办公' if s == 'office' else '住宅+办公') for s in sc)}　流派：{'/'.join(r['schools'])}")
            lines.append(f"- 结论：{r['finding']['summary']}")
            if r["finding"].get("remedy"):
                lines.append("- 化解/整改建议：")
                for rd in r["finding"]["remedy"]:
                    lines.append(f"  - {rd}")
            lines.append("- 原文依据：")
            for ev in r["evidence"]:
                rel = "直接明确" if ev["reliability"] == "直接明确" else "推演引申"
                lines.append(f"  - 《{ev['book']}》·{ev['chapter']}（{rel}）")
                lines.append(f"    - 原文：{ev['original']}")
                lines.append(f"    - 白话：{ev['modern']}")
            lines.append("")

    with open("docs/规则总览.md", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    n = sum(len(json.load(open(f"rules/draft/{fn}", encoding="utf-8"))) for fn, _, _ in FILES)
    print(f"生成 docs/规则总览.md 完成，共 {n} 条")


if __name__ == "__main__":
    main()
