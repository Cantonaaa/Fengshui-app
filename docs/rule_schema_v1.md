# 规则卡 Schema v1

规则存于 rules/draft/*.json（候选），评审后移入 rules/reviewed/。

```json
{
  "ruleId": "yszs_zhu_01",            // 书码_主题_序号
  "title": "灶宜居左、忌在白虎方",      // 白话标题
  "severity": "凶",                    // 大吉|吉|平|凶|大凶
  "schools": ["八宅", "形法"],         // 流派标签
  "category": "stove",                 // 主题：bed|door|stove|mirror|toilet|room|direction|space
  "status": "active",                  // active|dormant
  "condition": {                       // 引擎可评估的触发条件（声明式）
    "require": { "stove": 1 },
    "spatial": { "stove": ["onLeftOfRoom"] }   // 可测事实
  },
  "finding": {
    "summary": "灶应安于房屋左侧，不宜安在右方（白虎方），否则主凶。",
    "remedy": ["将灶移至房屋左侧位置", "无法移动时以屏风/隔断化解"]
  },
  "evidence": [
    {
      "book": "阳宅十书",
      "chapter": "卷三·论宅内形第八",
      "original": "灶必须居左位不宜安在白虎方阳宅若還依此法定須子孫熾吉昌",
      "modern": "灶必须安在左边位置，不宜安在右边白虎方。阳宅若按此法布置，必定子孙昌盛、家业兴旺。",
      "reliability": "直接明确"
    }
  ]
}
```

字段说明：
- `severity`: 吉凶判定，须可追溯原文；无法追溯则 reliability 标"推演引申"
- `condition`: 引擎事实表达式。require=房间需存在该物体；spatial=空间关系事实
- `status`: active=当前 app 可检测/可呈现；dormant=缺检测能力，先入库等激活；**hidden=被冲突裁决隐藏，任何界面不呈现**
- `scenario`: 适用场景（可选，默认 residential）`["residential"]` / `["office"]` / `["both"]`——场景适配层据此过滤规则
- `conflict`: 冲突裁决信息（可选）
  - `groupId`: 冲突组标识（同主题矛盾归一组）
  - `resolveTo`: 该组被保留（较早出处）的 ruleId
  - `hiddenRuleIds`: 被隐藏（后出）的 ruleId 列表
  - 呈现策略：报告只评估 active 规则；hidden 规则完全不出现在用户界面（见 docs/conflict_registry.md）
- `evidence.original`: 必须为校对后语料 corpus/proofed/ 的精确子串（程序校验，不通过拒收）
- `evidence.reliability`: "直接明确"=原文明说；"推演引申"=由理论演绎
- 同理论多书并列：evidence 数组按成书年代升序排列，首条为主引用

书码：zss=葬书, zj=宅经, fwl=发微论, yszs=阳宅十书, zfj=宅法举隅
