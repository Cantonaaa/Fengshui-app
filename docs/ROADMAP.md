# 项目交接与待办规划（ROADMAP）

> 本文件供**无背景的后续会话**接手。读完本文即可继续开发，无需回溯历史对话。
> 最后更新：2026-08-06

## 1. 项目概述

**产品**：Android 手机 app——拍照/录像扫描单房间生成 3D 模型 → 识别物体种类与摆放 → 结合生辰八字（命卦）与方向标注 → 用古籍蒸馏的风水规则输出**带原文依据**的指导与整改方案。

**核心定位**：端上为主、全离线、隐私（生辰仅存本地并加密）。

**技术栈**：Kotlin + Jetpack Compose + ARCore（方案A：平面检测+特征点云，不依赖Depth）+ SceneView(arsceneview 2.2.0) + MediaPipe/YOLO（规划中）+ 声明式规则引擎（未实现）。

## 2. 当前完成进度（关键）

| 模块 | 进度 | 说明 |
|---|---|---|
| **知识库** | ✅ 完成 | 6 书语料 17.6万字 + 74 条规则（active 58/dormant 15/hidden 1），全部过原文子串校验 |
| **冲突登记** | ✅ 完成 | CONFLICT-001 已裁决；后出矛盾规则标 hidden 完全不呈现 |
| **设计定稿** | ✅ 完成 | 单房间模型 + 整改求解器 + 朝向四级方案（见 docs/app_design.md）|
| **环境/构建** | ✅ 完成 | JDK21 + Android SDK35 + Gradle8.9 + 阿里云镜像，APK 可产出 |
| **A1.1 AR 会话** | ✅ **真机验证通过** | 平面检测真机工作正常（1→7），修复了权限启动崩溃 |
| **A1.2 点云采集** | ✅ 真机验证通过 | 1.6万点，玻璃窗鬼影点19%（验证裁剪需求）|
| **A1.3 户型多边形** | ✅ 真机验证通过 | Kotlin端上实现（地板RANSAC+鲁棒足迹+凸包），真机 2.4×4m 房间实测 8.7-11m²（误差±10-15%）；Python原型 scripts/room_polygon.py 一致性验证 |
| A1.4 物体识别+3D定位 | ✅ 真机验证通过 | YOLO-World(NCNN) 13类检测 + 选帧异步（HandlerThread 无卡顿）+ YUV直送JNI + 框底边→地板投影定位（坐标已出）；MAD地面高度抗离群 |
| K3 视觉模型 | ✅ 真机验证通过 | YOLO-World v2 固定词表 13类 → NCNN；**Einsum 注意力 arm64 崩溃 → 数学等价替换 1x1/块对角卷积**（重导出零 Einsum，相对误差 2.9e-7）；fp32 4线程 |
| 命卦计算 | ✅ 完成(未真机) | 生辰→三元命卦→东四/西四命→四吉凶方（大游年，坎/离/震对照标准通过）；JVM 测试 |
| 北向校准 | ✅ 完成(未真机) | 磁力计罗盘引导 + AR相机位姿一键校北（符号约定待真机验证）|
| 规则引擎接入 | ✅ 完成(未真机) | 6规则卡 assets + :solver 依赖 + 事实层(卦位/相冲/墙邻/密度) + 命卦感知吉凶方 + 过滤无条件通则；JVM 测试 |
| 整改求解器接入 | ✅ 完成(未真机) | L1 移动方案接入分析流，报告展示（动作/剩余/权衡/阻塞）|
| 物体尺寸估计 | ✅ 完成(未真机) | bbox×焦距×距离→占地宽钳制；进深用类型默认 |
| 玻璃鬼影点裁剪 | ✅ 完成(未真机) | RoomPolygon 密度滤波+最大连通分量（合成数据测试通过；真机玻璃房待验收）|
| 产品交互（onboarding/报告页）| ✅ MVP | 生辰输入页 + 报告页（命中规则+原文+整改）；正式 onboarding 待做 |

## 3. 目录结构

```
/home/aci/桌面/fengshui-app/
├── corpus/
│   ├── raw/         # 6书抓取原文（OCR未校对）
│   └── proofed/     # 归一化语料 + quotes_*.txt 引文库（校验基准）
├── rules/draft/     # 74条规则卡 JSON + bagua_data.json（游年数据）
├── scripts/
│   ├── ctext_fetch.py      # 从ctext抓书（章节ID参数）
│   ├── normalize_corpus.py # 归一化/合并
│   └── validate_rules.py   # 强制原文子串校验 + 冲突自检（python3 scripts/validate_rules.py）
├── docs/
│   ├── app_design.md       # ★ 设计定稿（先读）
│   ├── dependency_matrix.md# 识别范围 v3
│   ├── conflict_registry.md
│   ├── rule_schema_v1.md   # 规则卡字段规范
│   ├── corpus_manifest.md
│   └── ROADMAP.md          # 本文件
└── app/                    # Android 工程（Gradle）
```

## 4. 环境与构建（本机）

- **版本管理**：已同步 GitHub `https://github.com/Cantonaaa/Fengshui-app`（main 分支）。
  - **推送方法**（本机已配置，可直接 `git add/commit/push`）：
    - `export GIT_EXEC_PATH=/home/aci/.local/libexec/git-core`（每次 shell 需导出，已写入 ~/.bashrc）
    - github.com 主站 IP 被限 → 已全局配置 `url."https://140.82.112.3/".insteadOf "https://github.com/"` + Host 头 + 该 IP 范围 sslVerify=false
    - 凭据：`~/.git-credentials`（chmod 600，x-access-token）
    - 注：仓库远程显示为 https://140.82.112.3/...（即 github.com 的可用 IP）
- **环境变量**：见 `~/.bashrc`（JAVA_HOME=/home/aci/devtools/jdk-21.0.12+8、ANDROID_HOME=/home/aci/Android/Sdk、gradle 8.9）
- **镜像**（maven.google.com 被墙，必用）：工程 `settings.gradle.kts` 已配阿里云 google 镜像 + mavenCentral；wrapper 指向腾讯 gradle
- **构建**：
  ```
  cd /home/aci/桌面/fengshui-app/app
  export JAVA_HOME=$HOME/devtools/jdk-21.0.12+8
  export ANDROID_HOME=$HOME/Android/Sdk
  export PATH=$JAVA_HOME/bin:$PATH
  ./gradlew :app:assembleDebug
  # 产物: app/app/build/outputs/apk/debug/app-debug.apk
  ```
- **注意**：后台任务用 `( setsid ./gradlew ... </dev/null >log 2>&1 & )` 脱离，避免被工具超时清理；不要用 `pgrep -f assembleDebug` 判断（会自匹配）。

## 5. 真机联调（无线 adb）

手机 **Redmi Note 12 Turbo**（HyperOS 3.0.5.0 / Android 15），已侧载 ARCore v1.55。
- **复连步骤**（无线调试若被关闭，需重新开）：
  1. 手机：设置→开发者选项→无线调试→开启；点"使用配对码配对设备"记**配对端口+配对码**
  2. 本机：`adb pair <手机IP:配对端口> <配对码>` → `adb connect <手机IP:连接端口>` → `adb devices`
  - 之前连接信息参考：`192.168.52.114:40329`（连接端口，可能变化）；手机在 192.168.52.x，本机 192.168.181.x，路由互通
- **已验证**：`com.google.ar.core` v1.55 已装；app 可 adb install
- **日志**：`adb logcat -d | grep A1Scan`

## 6. 关键设计决策（勿重新讨论）

1. 单房间分析模型（非整宅多房间）
2. 3D 建模用方案A（ARCore 平面检测+特征点云，不依赖 Depth）；3DGS 二期
3. 方向基准=磁北（罗盘惯例）；命卦路为主（四吉凶方），坐向可选（宅卦游年）
4. 识别清单 11 类+办公 desk 等（K2 v3）；镜不引入（缺八宅明镜/阳宅三要文本，已搁置）
5. 朝向：扫描帧自动定长轴 + 墙邻先验 + 异常时一次点选；未知不判定
6. 整改求解器：硬约束（消凶+无回归）> 软目标（构造吉）；L1移动/L2遮挡/L3改造；灶视为固定；无解给次优+权衡
7. 冲突处理：保留较早出处，hidden 完全不呈现
8. 办公场景：宅法举隅·衙署/学宫/店铺直接映射（非纯推演）

## 7. 下一步待办（优先级排序）

> 说明：A1.2/A1.3/A1.4/K3/命卦/北向/规则引擎/整改求解器/尺寸估计/玻璃鬼影 均已实现并通过 JVM 测试（app 18 + solver 15 全绿）。**下一里程碑 = 真机全链路联调 + 玻璃房单列验收**。

| # | 任务 | 验收标准 |
|---|---|---|
| 1 | **真机全链路联调**：生辰→命卦显示 → 校北（罗盘引导）→ 扫描（异步无卡顿+YUV）→ 完成并分析 → 报告（命中规则+原文+整改方案）| 完整闭环可演示；**北向符号约定**、**尺寸估计合理性**逐项核对 |
| 2 | **玻璃房单列验收**：RoomPolygon 密度+最大连通分量裁剪在真实玻璃窗房间效果 | 窗洞/墙线不歪、窗外幻影簇被裁、面积误差 ≤±15% |
| 3 | **产品交互完善**：正式 onboarding（生辰向导、本地加密存储）、场景/房间类型选择、报告可视化（宫位图）| 非技术用户可用 |
| 4 | **朝向四级完善**：扫描帧自动定长轴 + 墙邻先验（当前仅一次点选磁北）| 无人工干预可判定朝向；未知不判定 |
| 5 | **检测质量**：score 饱和/重复框（NMS/置信度标定）、fp16 提速（A/B 对比）、半开放办公室误报评估 | 端上帧率实测、误报可控 |
| 6 | **规则覆盖面**：未实现 spatial 事实（facesPillar/underBeam/visibleFromDoor 等）+ 房间级规则（windy/draftThrough 需传感器）| 相关规则可触发 |
| 7 | **隐私**：生辰本地加密存储（当前明文 SharedPreferences）| 加密后不可明文读取 |
| 8 | **二期**：多房间/整宅、3DGS、GroundingDINO 蒸馏专类模型 | 超出 MVP 范围 |

## 8. 未决问题 / 风险

- **环境/工具挂起**：构建曾卡在 mediapipe AAR 下载（慢非断），已通过；重新构建若慢，耐心等待或换版本
- **pip**：PEP 668 需 `--break-system-packages`（已用）
- **《阳宅三要》错文件**（内容实为阴宅文）已弃用，勿再引用
- **待补文本已搁置**：《阳宅集成》《八宅明镜》《阳宅三要》、现代办公资料
- **引文质量**：阳宅十书/阳宅大全为 OCR 初稿，规则引用段落已人工校对进 quotes_*.txt；新增引用必须过 `validate_rules.py`
- **手机无 GMS 账号**：已用侧载解决 ARCore；重新连接需重新开启无线调试
- **真机协作依赖**：A 系列里程碑需用户配合真机扫描测试（当前阶段暂停真机调试，先构建 APK）
- **K3 待验证**：端上运行时倾向 NCNN（官方 TFLite 未完成）；MediaPipe/TFLite 依赖现零使用，待 NCNN 真机验证后移除

## 9. 接手第一步建议

1. 读 `docs/app_design.md` + 本文
2. 跑 `python3 scripts/validate_rules.py` 确认知识库完好
3. 构建一次确认环境可用
4. 从待办 #1（A1.2 点云）开始，按"本机构建→无线adb装真机→日志验证"闭环推进
