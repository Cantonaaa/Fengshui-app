#!/usr/bin/env python3
"""K4：yolov8l-worldv2 @ 960 重导（平均嵌入合并，17 输出类）。

与 app 端 YOLOWorldNcnn.vocab 顺序一致的 17 逻辑类；含新增 水缸/神位/柜架/保险柜/书。
柜架(bookshelf) 采用"书双重验证"（app 侧 bookshelf+邻近 book 才映射 study）。
守卫为警告式：cosine >0.95 中止；0.8-0.95 记入 docs/model_risk.md（真机重点验证）。

产物：yolov8l_world_v2_960.onnx（后续 pnnx → ncnn）
用法：cd scripts && python3 k4_export_l960.py
"""
import os
import sys

import torch

MODEL = "yolov8l-worldv2.pt"
ONNX_OUT = "yolov8l_world_v2_960.onnx"
IMGSZ = 960
RISK_DOC = "docs/model_risk.md"

# 逻辑类顺序必须与 app 端 YOLOWorldNcnn.vocab 完全一致（17 类）
LOGICAL = [
    "bed", "sofa", "dining table", "refrigerator", "potted plant",
    "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk",
    "water", "shrine", "bookshelf", "safe", "book",
]

PROMPTS = [
    ["bed", "double bed", "single bed", "bunk bed"],
    ["sofa", "couch"],
    ["dining table", "dinner table"],
    ["refrigerator", "fridge", "freezer", "side-by-side refrigerator"],
    ["potted plant", "houseplant", "plant in pot"],
    ["wardrobe", "closet", "clothes closet", "clothing wardrobe", "walk-in closet"],
    ["door", "double door", "sliding door"],
    ["window", "casement window", "floor-to-ceiling window"],
    ["stove", "gas stove", "cooktop"],
    ["pillar", "column"],
    ["desk", "writing desk", "office desk"],
    ["front desk", "reception desk", "reception"],
    ["water jar", "water dispenser", "jug", "vase", "ceramic pot", "water tank"],
    ["altar", "incense burner", "ancestor tablet", "spirit tablet", "deity altar"],
    ["bookshelf", "bookcase", "books on shelf", "shelf with books", "filing cabinet", "file cabinet"],
    ["safe", "vault", "strongbox", "cash box"],
    ["book", "books", "stack of books", "open book"],
]

assert len(LOGICAL) == len(PROMPTS) == 17

# 相邻类告警对（书柜已由 book 双验证兜底）
WARN_PAIRS = [
    ("wardrobe", "bookshelf"),
    ("safe", "bookshelf"),
    ("book", "bookshelf"),
    ("water", "refrigerator"),
    ("door", "window"),
    ("desk", "front desk"),
]
WARN_THRESHOLD = 0.8
ABORT_THRESHOLD = 0.95


def main():
    print("== K4: yolov8l-worldv2 @ 960 重导（17 类）==")
    from ultralytics import YOLOWorld

    model = YOLOWorld(MODEL)
    print("[1/5] 加载权重 OK")

    all_prompts = [p for grp in PROMPTS for p in grp]
    print(f"[2/5] set_classes({len(all_prompts)} 提示)…（CLIP 文本编码）")
    model.set_classes(all_prompts)
    feats = model.model.txt_feats.squeeze(0)  # [N, 512]

    avg = []
    idx = 0
    for grp in PROMPTS:
        avg.append(feats[idx:idx + len(grp)].mean(dim=0))
        idx += len(grp)
    avg_feats = torch.stack(avg)  # [17, 512]

    print("[3/5] 相邻类告警检查…")
    norm = avg_feats / avg_feats.norm(dim=1, keepdim=True).clamp_min(1e-9)
    warnings = []
    abort = False
    for a, b in WARN_PAIRS:
        cos = float((norm[LOGICAL.index(a)] * norm[LOGICAL.index(b)]).sum())
        tag = "OK"
        if cos >= ABORT_THRESHOLD:
            tag = "!! 中止级"
            abort = True
        elif cos >= WARN_THRESHOLD:
            tag = "警告(真机重点)"
        print(f"    cos({a}, {b}) = {cos:.3f}  {tag}")
        if cos >= WARN_THRESHOLD:
            warnings.append(f"- **{a} ↔ {b}**：cosine {cos:.3f}（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）")
    if abort:
        print("中止：存在近乎重复的类，请调整提示词。")
        sys.exit(2)
    os.makedirs(os.path.dirname(RISK_DOC), exist_ok=True)
    with open(RISK_DOC, "w", encoding="utf-8") as f:
        f.write("# 模型风险记录（17 类 l@960）\n\n")
        f.write("相邻类文本嵌入 cosine ≥ 0.8 的类对，判别依赖视觉特征与 margin 过滤（模糊→未识别）；\n")
        f.write("书柜(bookshelf)已启用「书」双验证：邻近 2m 内检测到 book 才映射 study，否则丢弃。\n\n")
        if warnings:
            f.write("\n".join(warnings) + "\n")
        else:
            f.write("（无超过 0.8 的相邻类对）\n")
    print(f"    告警已写入 {RISK_DOC}")

    model.model.txt_feats = avg_feats.unsqueeze(0)
    model.model.model[-1].nc = 17
    model.model.names = LOGICAL
    print(f"[4/5] 平均嵌入合并完成：txt_feats {tuple(model.model.txt_feats.shape)}，nc=17")

    model.model.eval()
    x = torch.randn(1, 3, IMGSZ, IMGSZ)
    print(f"[5/5] 导出 ONNX @ {IMGSZ}…")
    torch.onnx.export(
        model.model, (x,), ONNX_OUT,
        opset_version=13,
        input_names=["in0"],
        output_names=["out0"],
        do_constant_folding=False,
        dynamo=False,
    )
    print("导出完成：", ONNX_OUT, round(os.path.getsize(ONNX_OUT) / 1048576, 1), "MB")


if __name__ == "__main__":
    main()
