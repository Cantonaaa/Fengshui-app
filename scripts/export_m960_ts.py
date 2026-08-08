#!/usr/bin/env python3
"""导出 yolov8m-worldv2 @ 960 的 torchscript（17 类平均嵌入合并），供 pnnx 转换。

与 k4_export_m960.py 同源（同 LOGICAL/PROMPTS/avg_feats），但导出 .pt (torchscript)。
"""
import torch
from ultralytics import YOLOWorld

MODEL = "yolov8m-worldv2.pt"
TS_OUT = "yolov8m-worldv2.torchscript"
IMGSZ = 960

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


def main():
    print("== export m@960 torchscript ==")
    model = YOLOWorld(MODEL)
    all_prompts = [p for grp in PROMPTS for p in grp]
    model.set_classes(all_prompts)
    feats = model.model.txt_feats.squeeze(0)
    avg = []
    idx = 0
    for grp in PROMPTS:
        avg.append(feats[idx:idx + len(grp)].mean(dim=0))
        idx += len(grp)
    avg_feats = torch.stack(avg)
    model.model.txt_feats = avg_feats.unsqueeze(0)
    model.model.model[-1].nc = 17
    model.model.names = LOGICAL
    model.model.eval()
    model.model.clip_model = None  # 推理只用烘焙的 txt_feats，去掉 CLIP 死重

    x = torch.randn(1, 3, IMGSZ, IMGSZ)

    class Wrap(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            return self.inner(x)[0]

    print("tracing (check_trace=False)...")
    mod = torch.jit.trace(Wrap(model.model), (x,), check_trace=False)
    mod.save(TS_OUT)
    print("saved:", TS_OUT, round(__import__("os").path.getsize(TS_OUT) / 1048576, 1), "MB")


if __name__ == "__main__":
    main()
