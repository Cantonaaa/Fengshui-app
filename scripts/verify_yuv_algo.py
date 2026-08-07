#!/usr/bin/env python3
"""验证 nativeDetectYuv 的 YUV→RGB+双线性缩放算法与参考实现一致。

镜像 C++ yuvToMat（I420 平面 + rowStride/pixelStride + BT.601），与 PIL 双线性缩放对比。
"""
import numpy as np
from PIL import Image

W, H = 480, 720
TARGET = 640


def rgb_to_yuv(rgb):
    r = rgb[..., 0].astype(np.float32)
    g = rgb[..., 1].astype(np.float32)
    b = rgb[..., 2].astype(np.float32)
    y = np.clip(0.299 * r + 0.587 * g + 0.114 * b, 0, 255).astype(np.uint8)
    uf = -0.168736 * r - 0.331264 * g + 0.5 * b + 128
    vf = 0.5 * r - 0.418688 * g - 0.081312 * b + 128
    # float 下采样，避免 uint8 溢出
    us = (uf[0::2, 0::2] + uf[0::2, 1::2] + uf[1::2, 0::2] + uf[1::2, 1::2]) / 4
    vs = (vf[0::2, 0::2] + vf[0::2, 1::2] + vf[1::2, 0::2] + vf[1::2, 1::2]) / 4
    return y, np.clip(us, 0, 255).astype(np.uint8), np.clip(vs, 0, 255).astype(np.uint8)


def yuv_to_mat(y, u, v, w, h, yRowStride, uvRowStride, uvPixelStride):
    """镜像 C++ yuvToMat（双线性权重与 C++ 完全一致）。"""
    out = np.zeros((TARGET, TARGET, 3), dtype=np.uint8)

    def yuv2rgb(yy, uu, vv):
        yy = int(yy); uu = int(uu); vv = int(vv)
        rr = int(yy + 1.402 * (vv - 128))
        gg = int(yy - 0.344 * (uu - 128) - 0.714 * (vv - 128))
        bb = int(yy + 1.772 * (uu - 128))
        return (max(0, min(255, rr)), max(0, min(255, gg)), max(0, min(255, bb)))

    def uv_at(xx, yy, p):
        return p[(yy // 2) * uvRowStride + (xx // 2) * uvPixelStride]

    for ty in range(TARGET):
        syf = ty * h / TARGET
        sy0 = int(syf)
        sy1 = sy0 + 1 if sy0 + 1 < h else sy0
        fy = syf - sy0
        for tx in range(TARGET):
            sxf = tx * w / TARGET
            sx0 = int(sxf)
            sx1 = sx0 + 1 if sx0 + 1 < w else sx0
            fx = sxf - sx0

            r0, g0, b0 = yuv2rgb(y[sy0 * yRowStride + sx0], uv_at(sx0, sy0, u), uv_at(sx0, sy0, v))
            r1, g1, b1 = yuv2rgb(y[sy0 * yRowStride + sx1], uv_at(sx1, sy0, u), uv_at(sx1, sy0, v))
            r2, g2, b2 = yuv2rgb(y[sy1 * yRowStride + sx0], uv_at(sx0, sy1, u), uv_at(sx0, sy1, v))
            r3, g3, b3 = yuv2rgb(y[sy1 * yRowStride + sx1], uv_at(sx1, sy1, u), uv_at(sx1, sy1, v))

            r = (r0 * (1 - fx) + r1 * fx) * (1 - fy) + (r2 * (1 - fx) + r3 * fx) * fy
            g = (g0 * (1 - fx) + g1 * fx) * (1 - fy) + (g2 * (1 - fx) + g3 * fx) * fy
            b = (b0 * (1 - fx) + b1 * fx) * (1 - fy) + (b2 * (1 - fx) + b3 * fx) * fy
            out[ty, tx] = (int(r + 0.5), int(g + 0.5), int(b + 0.5))
    return out


# 合成渐变图
xx = np.linspace(0, 1, W)[None, :]
yy = np.linspace(0, 1, H)[:, None]
r = np.broadcast_to((xx * 255), (H, W)).astype(np.uint8)
g = np.broadcast_to((yy * 255), (H, W)).astype(np.uint8)
b = np.broadcast_to(((1 - xx) * 255), (H, W)).astype(np.uint8)
rgb = np.stack([r, g, b], axis=-1)

y, u, v = rgb_to_yuv(rgb)
mine = yuv_to_mat(y.reshape(-1), u.reshape(-1), v.reshape(-1), W, H, W, W // 2, 1)

ref = np.asarray(Image.fromarray(rgb).resize((TARGET, TARGET), Image.BILINEAR))
d = mine.astype(np.int16) - ref.astype(np.int16)
print("my YUV vs PIL-ref: max abs diff =", int(np.abs(d).max()), " mean = %.2f" % np.abs(d).mean())

for name, ch in [("纯红", [255, 0, 0]), ("纯绿", [0, 255, 0]), ("纯蓝", [0, 0, 255])]:
    solid = np.zeros((H, W, 3), dtype=np.uint8)
    for c in range(3):
        solid[..., c] = ch[c]
    y, u, v = rgb_to_yuv(solid)
    m = yuv_to_mat(y.reshape(-1), u.reshape(-1), v.reshape(-1), W, H, W, W // 2, 1)
    print("%s 还原: R=%d G=%d B=%d (期望 %s)" % (name, m[100, 100, 0], m[100, 100, 1], m[100, 100, 2], ch))
