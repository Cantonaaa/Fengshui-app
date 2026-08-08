#!/usr/bin/env python3
"""m@960 手术：将 C2fAttn 文本引导注意力 + BNContrastiveHead 折叠为等价卷积。

guide(txt_feats) 在推理时是常量，因此：
  C2fAttn.attn: einsum('bmchw,bnmc->bmhwn', embed, guide)  -> 分组 1x1 conv (guide 投影作权重) + max over n
  BNContrastiveHead: einsum('bchw,bkc->bkhw', x, w)        -> 1x1 conv (normalize(w)^T 作权重)

先验证折叠版与原版 forward 输出一致，再导出 torchscript。
用法：cd scripts && python3 surgery_m960.py
"""
import os

import torch
import torch.nn as nn
import torch.nn.functional as F
from ultralytics import YOLOWorld

from export_m960_ts import LOGICAL, PROMPTS

MODEL = "yolov8m-worldv2.pt"
TS_OUT = "yolov8m-worldv2_patchedH.pt"
IMGSZ = 960
VERIFY = True


class FusedAttn(nn.Module):
    """C2fAttn.attn 折叠版：einsum('bmchw,bnmc->bmhwn') -> 分组 1x1 conv + max over n。"""

    def __init__(self, attn, guide):
        super().__init__()
        self.nh = attn.nh
        self.hc = attn.hc
        self.nc = guide.shape[1]
        self.bias = attn.bias
        self.scale = attn.scale
        self.proj_conv = attn.proj_conv
        self.ec = attn.ec
        gp = attn.gl(guide).view(1, self.nc, self.nh, self.hc)  # [1, nc, nh, hc]
        W = torch.zeros(self.nh * self.nc, self.hc, 1, 1)
        for hh in range(self.nh):
            for t in range(self.nc):
                W[hh * self.nc + t, :, 0, 0] = gp[0, t, hh, :]
        self.register_buffer("W", W)

    def forward(self, x, guide):
        b = x.shape[0]
        embed = self.ec(x) if self.ec is not None else x
        logits = F.conv2d(embed, self.W, groups=self.nh)  # [b, nh*nc, h, w]
        logits = logits.view(b, self.nh, self.nc, x.shape[2], x.shape[3])
        aw, _ = logits.max(dim=2)  # [b, nh, h, w]
        aw = aw / (self.hc ** 0.5)
        aw = aw + self.bias[None, :, None, None]
        aw = aw.sigmoid() * self.scale
        y = self.proj_conv(x)  # [b, nh*hc2, h, w]
        b, c2, h, w = y.shape
        y = y.view(b, self.nh, -1, h, w)
        aw5 = aw.view(b, self.nh, 1, h, w)  # 用 view 而非 unsqueeze(dim=2)（pnnx 不支持 4-rank unsqueeze）
        y = y * aw5
        return y.view(b, c2, h, w)


class FusedContrastive(nn.Module):
    """BNContrastiveHead 折叠版：einsum('bchw,bkc->bkhw') -> 1x1 conv。
    scale/bias 直接折叠进 conv 权重与偏置（避免 Parameter.exp() 被 pnnx 写成错误 MemoryData）。"""

    def __init__(self, cv, guide):
        super().__init__()
        self.norm = cv.norm
        w = F.normalize(guide, dim=-1)  # [1, nc, 512]
        W = w[0].unsqueeze(-1).unsqueeze(-1).contiguous()  # [nc, 512, 1, 1]
        scale = float(cv.logit_scale.detach().exp())
        bias = float(cv.bias.detach())
        self.conv = nn.Conv2d(W.shape[1], W.shape[0], 1)
        with torch.no_grad():
            self.conv.weight.copy_(W * scale)
            self.conv.bias.copy_(torch.full((W.shape[0],), bias, dtype=W.dtype))

    def forward(self, x, text):
        x = self.norm(x)
        return self.conv(x)


def build_model():
    model = YOLOWorld(MODEL)
    all_prompts = [p for grp in PROMPTS for p in grp]
    model.set_classes(all_prompts)
    feats = model.model.txt_feats.squeeze(0)
    avg = []
    idx = 0
    for grp in PROMPTS:
        avg.append(feats[idx:idx + len(grp)].mean(dim=0))
        idx += len(grp)
    avg_feats = torch.stack(avg).unsqueeze(0)  # [1, 17, 512]
    model.model.txt_feats = avg_feats
    model.model.model[-1].nc = 17
    model.model.names = LOGICAL
    model.model.eval()
    return model, avg_feats


def fuse(model, guide):
    for m in model.model.model:
        if isinstance(m, nn.Module):
            from ultralytics.nn.modules.block import C2fAttn
            from ultralytics.nn.modules.head import WorldDetect
            if isinstance(m, C2fAttn):
                m.attn = FusedAttn(m.attn, guide)
            elif isinstance(m, WorldDetect):
                for i in range(len(m.cv4)):
                    m.cv4[i] = FusedContrastive(m.cv4[i], guide)


def main():
    print("== surgery m@960 ==")
    model, guide = build_model()
    print("txt_feats:", tuple(guide.shape), "nc=17")

    if VERIFY:
        x = torch.randn(1, 3, IMGSZ, IMGSZ)
        with torch.no_grad():
            ref = model.model(x)[0].clone()
        print("原模型输出:", tuple(ref.shape))
        fuse(model, guide)
        with torch.no_grad():
            fused = model.model(x)[0].clone()
        d = (fused - ref).abs()
        md = float(d.max())
        print("折叠后输出:", tuple(fused.shape))
        print("max abs diff: %.8f  mean: %.10f" % (md, float(d.mean())))
        assert md < 1e-3, f"手术不一致! max diff {md}"
        print("手术数值一致 ✓")
    else:
        fuse(model, guide)

    model.model.clip_model = None
    xx = torch.randn(1, 3, IMGSZ, IMGSZ)

    class Wrap(nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            return self.inner(x)[0]

    print("tracing (check_trace=False)...")
    mod = torch.jit.trace(Wrap(model.model), (xx,), check_trace=False)
    mod.save(TS_OUT)
    print("saved:", TS_OUT, round(os.path.getsize(TS_OUT) / 1048576, 1), "MB")


if __name__ == "__main__":
    main()
