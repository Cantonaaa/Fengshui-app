# 风水堪舆 (FengShuiApp)

AR 扫描房间 → 识别器物布局 → 结合生辰命卦与磁北朝向 → 用古籍蒸馏的风水规则，输出**带原文依据**的吉凶判读与化解方案。

**全离线 · 本地运行**：生辰与数据仅存本机，不上传；无需联网即可完成「入宅勘察 → 起批」。

---

## 功能

- **入宅勘察**（ARCore 平面检测 + 特征点云）
  - 环绕扫描采集房间几何（户型多边形·最小面积外接矩形）
  - **录像后处理**：关键帧缓存 → 批量检测 → **多帧融合**（类别投票 + 位置/尺寸中值，≥2 帧确认），识别更稳、去瞬时误检
  - 起批过程中**轮播古籍原文**（葬乘生气、藏风聚气等）
- **命卦与罗盘**
  - 生辰支持**公历/农历**填写与换算 → 三元命卦 → 四吉凶方
  - **大罗盘定向**（磁北示数 + 距北指示），每会话须重新定盘
- **规则引擎**（87 条）
  - 覆盖床/灶/厕/门/沙发/衣柜/盆栽/餐桌/书桌等家具的**有效限制**
  - 卦位吉凶、五行相克、墙邻靠背、门冲、危险相邻四层规则
  - 每条规则附**原文出处**（《葬书》《宅经》《阳宅十书》《宅法举隅》等）
- **堪舆批语报告**
  - 房间宫位图（吉方翠/凶方赤 + 器物点位 + 化解迁置箭头）
  - 凶煞与化解（含趋避之法 + 典出）、吉象、化解之法（L1 移动方案）
  - 界面雅言化（文白相间），全中文

## 技术栈

| 层 | 选型 |
|---|---|
| 语言/UI | Kotlin · Jetpack Compose (Material3) |
| AR | ARCore · SceneView (arsceneview 2.2.0)，方案A（平面+点云，不依赖 Depth） |
| 视觉 | YOLO-World v2 固定 13 类词表 → **NCNN**（C++ JNI，YUV_420 直送） |
| 规则/求解 | 纯 Kotlin 模块 `solver`（条件求值 + 整改求解器，JVM 可测） |
| 农历 | cn.6tail:lunar |

## 视觉模型说明

- 词表（13 类）：床/沙发/餐桌/冰箱/盆栽/厕所/衣柜/门/窗/灶/柱/书桌/前台
- **关键修复**：YOLO-World v2 的 Einsum 注意力在 arm64 崩溃 → 数学等价替换为 1×1/块对角卷积（重导出零 Einsum，相对误差 2.9e-7）
- **输入归一化**：模型按 [0,1] 导出，端上 ÷255（否则类别饱和误判）
- **未识别判定**：top1-top2 置信度 margin 过小 → 标记「未识别」（不在判断类别内），不硬塞错误类别

## 目录结构

```
├── app/                    # Android 工程
│   ├── app/src/main/jni/   # NCNN C++ JNI（yoloworld.cpp）
│   └── solver/             # 纯 Kotlin 规则引擎 + 整改求解器
├── rules/draft/            # 87 条规则卡 JSON + bagua_data（大游年数据）
├── corpus/                 # 6 书语料 + 引文库（校验基准）
├── docs/                   # 设计/依赖矩阵/冲突登记/ROADMAP
└── scripts/                # 模型导出、校验、模拟工具
```

## 构建

环境：JDK 21 · Android SDK 35（build-tools 35 / NDK 29）· Gradle 8.9

```bash
cd app
export JAVA_HOME=$HOME/devtools/jdk-21.0.12+8
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleDebug        # 调试 APK
./gradlew :app:assembleRelease      # 签名 Release APK
./gradlew :app:testDebugUnitTest :solver:test   # 测试
```

> 签名：`app/keystore.properties`（gitignored）提供 keystore 路径与口令；缺失时 release 用未签名构建。密钥文件不入库，请自行备份。

## 安装

- 下载 [GitHub Releases](https://github.com/Cantonaaa/Fengshui-app/releases) 的 `app-release.apk` 侧载安装
- 首次使用：**定生辰（公历/农历）→ 起命卦** → 入宅勘察（**大罗盘定向**）→ 环绕一圈 → **勘毕起批**

## 隐私

生辰、扫描数据全部在本地（私有 SharedPreferences / 应用缓存），**无任何网络上传**，无账号体系。

## 状态与 Roadmap

见 [docs/ROADMAP.md](docs/ROADMAP.md)。当前 v1.0.0 已发布；家居房间识别实效、北向符号对照、玻璃房鬼影裁剪等真机验收仍在规划中。

## 免责声明

风水内容源自古典文献，仅供文化研究与娱乐参考，不构成医疗、投资或其他专业建议。
