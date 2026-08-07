#!/usr/bin/env python3
"""D-2b：验证 17 类 l@960 ONNX。
解码与 app JNI 完全一致：confThr=0.15 → margin<0.15→未识别 → NMS(0.45) → 分档阈值。
书柜双验证：bookshelf 框内/邻近有 book 框才确认（→study），否则丢弃。
用法：cd scripts && python3 validate_model.py [test_imgs_dir]
"""
import glob
import os
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

ONNX = "yolov8l_world_v2_960.onnx"
TARGET = 960
CONF_BASE = 0.15
MARGIN = 0.15
IOU = 0.45
TIER = {"wardrobe": 0.20, "door": 0.25, "window": 0.25, "book": 0.30}  # 其余 0.35
BOOK_VERIFY = True  # 书柜须邻近 book 才确认

VOCAB = [
    "bed", "sofa", "dining table", "refrigerator", "potted plant",
    "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk",
    "water", "shrine", "bookshelf", "safe", "book",
]
# 展示名（中文）
ZH = {"bed":"床","sofa":"沙发","dining table":"餐桌","refrigerator":"冰箱","potted plant":"盆栽",
"wardrobe":"衣柜","door":"门","window":"窗","stove":"灶","pillar":"柱","desk":"书桌","front desk":"前台",
"water":"水缸","shrine":"神位","bookshelf":"柜架","safe":"保险柜","book":"书"}
# 检测类 → 规则类型（未含 bookshelf→study，由双验证后映射）
CANON = {"bookshelf": None, "book": None}  # 特殊处理


def decode(out):
    num_cls = out.shape[1] - 4
    objs = []
    for i in range(out.shape[2]):
        s1, s2 = 0.0, 0.0
        c1 = -1
        for c in range(num_cls):
            s = float(out[0, 4 + c, i])
            if s > s1:
                s2, s1, c1 = s1, s, c
            elif s > s2:
                s2 = s
        if s1 < CONF_BASE:
            continue
        cls = -1 if (s1 - s2) < MARGIN else c1
        if cls < 0:
            continue
        x, y, bw, bh = (float(out[0, k, i]) for k in range(4))
        objs.append({"cls": cls, "name": VOCAB[cls], "score": s1,
                     "box": (x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)})
    # NMS
    objs.sort(key=lambda o: -o["score"])
    kept = []
    for o in objs:
        if any(iou(o["box"], k["box"]) > IOU for k in kept):
            continue
        kept.append(o)
    # 分档阈值
    res = []
    for o in kept:
        thr = TIER.get(o["name"], 0.35)
        if o["score"] >= thr:
            res.append(o)
    return res


def iou(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    ua = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
    return inter / ua


def overlap(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    return (ix2 - ix1) > 0 and (iy2 - iy1) > 0


def verify_bookshelf(objs):
    """双验证：bookshelf 有 book 框重叠才保留(→study)；book 本身丢弃。"""
    books = [o for o in objs if o["name"] == "book"]
    out = []
    for o in objs:
        if o["name"] == "book":
            continue
        if o["name"] == "bookshelf":
            if any(overlap(o["box"], b["box"]) for b in books):
                o["name"] = "study"
                o["zh"] = "书房·柜架"
                out.append(o)
            else:
                out.append({**o, "name": "bookshelf(rejected)", "zh": "柜架(无书→丢弃)"})
        else:
            out.append(o)
    return out


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "test_imgs"
    sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0].name
    print(f"{'类':<12} {'图片':<6} {'命中':<4} 检测结果\n" + "-" * 60)
    for folder in sorted(glob.glob(os.path.join(d, "*/"))):
        cls = os.path.basename(folder.rstrip("/"))
        hits = 0
        details = []
        for img in sorted(glob.glob(os.path.join(folder, "*"))):
            try:
                im = Image.open(img).convert("RGB").resize((TARGET, TARGET))
                x = np.asarray(im, dtype=np.float32) / 255.0
                x = x.transpose(2, 0, 1)[None]
                out = sess.run(None, {inp: x})[0]
                objs = decode(out)
                objs = verify_bookshelf(objs)
            except Exception as e:
                details.append((os.path.basename(img), f"err:{e}"))
                continue
            dets = []
            for o in objs:
                if o["name"] == "study":
                    dets.append(f"书房√{o['score']:.2f}")
                    hits += 1
                elif o["name"].endswith("(rejected)"):
                    dets.append("柜架✗无书")
                elif o["name"] in (cls, ZH.get(o["name"], o["name"])) or ZH.get(o["name"]) == cls:
                    dets.append(f"{ZH.get(o['name'], o['name'])}√{o['score']:.2f}")
                    hits += 1
                else:
                    dets.append(f"{ZH.get(o['name'], o['name'])}:{o['score']:.2f}")
            # 图片名 → 简体映射
            zh = ZH.get(cls, cls)
            mark = "√" if dets and any(("√" in s or "书房√" in s) for s in dets) else " "
            details.append((os.path.basename(img), mark + ",".join(dets) if dets else "-"))
        print(f"[{zh or cls}]")
        for fn, r in details:
            print(f"    {fn:12} {r}")


if __name__ == "__main__":
    main()
