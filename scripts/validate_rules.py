#!/usr/bin/env python3
"""校验规则卡：
1. evidence.original 必须为对应书引文库的精确子串（防伪引用）
2. ruleId 全局唯一
3. 必填字段齐全
"""
import json
import sys
from pathlib import Path

ROOT = Path("/home/aci/桌面/fengshui-app")
QUOTES = ROOT / "corpus" / "proofed"
RULES = ROOT / "rules"

BOOK_QUOTES = {
    "阳宅十书": "quotes_yangzhai_shishu.txt",
    "葬书": "quotes_zangshu_faweilun.txt",
    "宅经": "quotes_zhaijing.txt",
    "发微论": "quotes_zangshu_faweilun.txt",
    "宅法举隅": "quotes_zhaifajuyu.txt",
}

REQUIRED = ["ruleId", "title", "severity", "schools", "category",
            "status", "condition", "finding", "evidence"]
SEVERITIES = {"大吉", "吉", "平", "凶", "大凶"}
STATUSES = {"active", "dormant", "hidden"}


def load_quotes():
    data = {}
    for book, fn in BOOK_QUOTES.items():
        p = QUOTES / fn
        if p.exists():
            data[book] = p.read_text(encoding="utf-8")
    return data


def main():
    quotes = load_quotes()
    rule_files = sorted((RULES / "draft").glob("*.json"))
    if not rule_files:
        print("无规则文件")
        return 1
    seen_ids = {}
    errors = []
    total = 0
    for rf in rule_files:
        if rf.name.startswith("bagua_"):
            continue
        rules = json.loads(rf.read_text(encoding="utf-8"))
        if not isinstance(rules, list):
            continue
        for r in rules:
            total += 1
            rid = r.get("ruleId")
            if rid in seen_ids:
                errors.append(f"{rf.name}: ruleId 重复 {rid} (also in {seen_ids[rid]})")
            seen_ids[rid] = rf.name
            for k in REQUIRED:
                if k not in r:
                    errors.append(f"{rid}: 缺少字段 {k}")
            if r.get("severity") not in SEVERITIES:
                errors.append(f"{rid}: severity 非法 {r.get('severity')}")
            if r.get("status") not in STATUSES:
                errors.append(f"{rid}: status 非法 {r.get('status')}")
            if not r.get("condition", {}).get("require"):
                errors.append(f"{rid}: condition.require 为空")
            if not r.get("finding", {}).get("summary"):
                errors.append(f"{rid}: finding.summary 为空")
            for ev in r.get("evidence", []):
                book = ev.get("book")
                original = ev.get("original")
                corpus = quotes.get(book, "")
                if not corpus:
                    errors.append(f"{rid}: 无引文库 for {book}")
                elif original not in corpus:
                    errors.append(f"{rid}: original 不是 {book} 引文库子串 -> {original[:30]}...")
                if "chapter" not in ev or not ev["chapter"]:
                    errors.append(f"{rid}: evidence.chapter 缺失")
                if "reliability" not in ev:
                    errors.append(f"{rid}: evidence.reliability 缺失")
                elif ev["reliability"] not in {"直接明确", "推演引申"}:
                    errors.append(f"{rid}: evidence.reliability 非法")
    # 冲突自检：同一 groupId 下 active 规则必须唯一且等于 resolveTo；hidden 规则必须列出在 hiddenRuleIds 中
    groups = {}
    decl = []
    for rf in rule_files:
        if rf.name.startswith("bagua_"):
            continue
        try:
            rules = json.loads(rf.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(rules, list):
            continue
        for r in rules:
            c = r.get("conflict")
            if not c:
                continue
            rid = r["ruleId"]
            g = c["groupId"]
            decl.append(rid)
            groups.setdefault(g, {"active": [], "hidden": [], "resolveTo": c.get("resolveTo"),
                                  "hiddenRuleIds": c.get("hiddenRuleIds", [])})
            if r["status"] == "active":
                groups[g]["active"].append(rid)
            if r["status"] == "hidden":
                groups[g]["hidden"].append(rid)
    for g, info in groups.items():
        if len(info["active"]) != 1:
            errors.append(f"冲突组 {g}: active 规则数应为1，实际 {info['active']}")
        if info["resolveTo"] not in info["active"]:
            errors.append(f"冲突组 {g}: resolveTo={info['resolveTo']} 不在 active {info['active']} 中")
        for hid in info["hidden"]:
            if hid not in info["hiddenRuleIds"]:
                errors.append(f"冲突组 {g}: hidden 规则 {hid} 未在 hiddenRuleIds 中列出")
    print(f"共 {total} 条规则")
    if errors:
        print(f"发现 {len(errors)} 个问题:")
        for e in errors:
            print(" -", e)
        return 1
    print("全部校验通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
