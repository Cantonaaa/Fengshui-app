#!/usr/bin/env python3
"""生成 docs/规则总览.md —— 通俗版（仅供用户个人了解，不作开发引用）。
开发以 rules/draft/*.json 为准；app 内展示内容保持古典/高深。
按生活主题组织，用大白话说明。"""
import json
import glob

STATUS = {"active": "✅可用", "dormant": "○暂不触发", "hidden": "✗不展示"}
SEV = {"大吉": "🟢大吉", "吉": "🟢吉", "平": "⚪中性", "凶": "🔴凶", "大凶": "🔴大凶"}

# 主题：category → (通俗主题名, 说明)
THEMES = [
    ("minggua", "生辰与命卦", "根据出生年月算出命卦，决定哪些方位对自己好"),
    ("direction", "方位吉凶", "房子各个方位的吉凶（哪边好、哪边差）"),
    ("bed", "床与卧房", "床怎么摆、卧室怎么布置"),
    ("stove", "厨房与灶", "灶/厨房的位置与方向"),
    ("door", "门窗", "门怎么开、门冲什么不好"),
    ("toilet", "卫生间", "厕所位置与避讳"),
    ("study", "书房与文昌", "学习、办公的地方怎么选位"),
    ("space", "空间与气", "房子大小、空旷还是充实、藏风聚气"),
    ("wardrobe", "衣柜与储物", "大柜子怎么放"),
    ("sofa", "沙发", "沙发怎么摆"),
    ("fridge", "冰箱", "冰箱（属水）和灶（属火）的关系"),
    ("plant", "绿植盆栽", "家里植物怎么摆"),
    ("dining", "餐桌", "餐桌别对着门"),
    ("office", "办公室", "老板位、办公区、财务室、前台（源自古代衙门）"),
    ("finance", "财务室", "财务/钱柜的位置"),
    ("front_desk", "前台", "前台接待的朝向"),
    ("parking", "停车/设备区", "停车、设备区位置"),
    ("room", "房间整体", "天井、明堂、屋后等整体讲究"),
    ("waixing", "住宅外形", "房子周围环境（外面的事）"),
    ("theory", "核心理论", "风水的基础道理（生气、藏风聚气）"),
]

def plain_explain(r):
    """用规则自带的白话结论，简化为一句大白话。"""
    s = r["finding"]["summary"]
    # 去掉一些生僻表达，但基本用原文白话（已经比较通俗）
    return s


def main():
    all_rules = []
    for fn in sorted(glob.glob("rules/draft/*.json")):
        if "bagua" in fn:
            continue
        all_rules.extend(json.load(open(fn, encoding="utf-8")))

    lines = []
    lines.append("# 风水规则通俗总览\n")
    lines.append("> ⚠️ **本文档仅供个人了解**，用大白话写的，**不作为开发引用**。")
    lines.append("> 开发/规则判断以 `rules/draft/*.json` 为准；App 内展示给用户的内容保留原文依据、更专业。")
    lines.append("> 状态说明：✅可用=当前能判断 ｜ ○暂不触发=缺检测能力以后再说 ｜ ✗不展示=和其他规则冲突被藏起来了\n")
    lines.append("> 一共 {} 条规则。\n".format(len(all_rules)))

    lines.append("## 先看这个：常用术语大白话\n")
    lines.append("| 术语 | 大白话 |")
    lines.append("|---|---|")
    lines.append("| 命卦/福德宫 | 按出生年份算出的“你的方位属性”，分东四命、西四命两类 |")
    lines.append("| 东四命/西四命 | 命卦分两类：东四命适合东、南、北、东南四个方位；西四命适合西、西南、西北、东北 |")
    lines.append("| 吉方/凶方 | 对你有利的方位 / 对你不利的方位 |")
    lines.append("| 生气/延年/天乙/伏位 | 四个“好星位”（方位吉）|")
    lines.append("| 绝命/五鬼/六煞/祸害 | 四个“坏星位”（方位凶）|")
    lines.append("| 卦位/九宫 | 把房子或房间像九宫格一样分成九个方位格 |")
    lines.append("| 宅卦/坐向 | 房子朝哪个方向坐（如坐北朝南）对应的卦 |")
    lines.append("| 藏风聚气 | 好气要聚在屋里、别被风吹散（风水最核心的道理）|")
    lines.append("| 白虎方/青龙方 | 在屋里往外看，右手边叫白虎（右方）、左手边叫青龙（左方）|")
    lines.append("| 文昌位 | 利于读书、学习、办公的方位 |")
    lines.append("| 明堂/天井 | 屋子前面的空地 / 院子中间的空地 |")
    lines.append("| 灶/厕/床 | 炉灶（厨房）、卫生间、床 |")
    lines.append("| 水火相冲 | 属火的东西（灶）和属水的东西（冰箱/水池）放太近相冲不好 |")
    lines.append("| 有靠 | 背后有墙或实心东西顶着（如床头靠墙、沙发背靠墙）|\n")

    for cat, title, desc in THEMES:
        group = [r for r in all_rules if r.get("category") == cat]
        if not group:
            continue
        lines.append(f"## {title}\n")
        lines.append(f"> {desc}\n")
        for r in group:
            st = r["status"]
            if st == "hidden":
                continue
            sev = SEV.get(r["severity"], r["severity"])
            lines.append(f"- **{r['title']}**　{sev}　（{STATUS[st]}）")
            lines.append(f"  {plain_explain(r)}\n")

    with open("docs/规则总览.md", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"通俗版规则总览生成完成，{len(all_rules)} 条")


if __name__ == "__main__":
    main()
