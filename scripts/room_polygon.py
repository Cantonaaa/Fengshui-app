#!/usr/bin/env python3
"""A1.3 户型多边形原型：PLY 点云 → 墙平面裁剪 → 地板/墙面 → 房间多边形。
离线开发用（numpy）。"""
import sys
import numpy as np


def load_ply(path):
    pts = []
    with open(path) as f:
        for line in f:
            if line.startswith("end_header"):
                break
        for line in f:
            p = line.split()
            if len(p) >= 3:
                try:
                    pts.append([float(p[0]), float(p[1]), float(p[2])])
                except ValueError:
                    pass
    return np.array(pts, dtype=np.float64)


def clean(P):
    """去 NaN/Inf、粗过滤：高度 Y∈[-0.6,3.5]，xz∈[-15,15]。"""
    P = P[np.isfinite(P).all(axis=1)]
    m = (P[:, 1] > -0.6) & (P[:, 1] < 3.5) & (np.abs(P[:, 0]) < 15) & (np.abs(P[:, 2]) < 15)
    return P[m]


def ransac_plane(P, iters=120, thr=0.04, min_inliers=200):
    """RANSAC 平面拟合，返回 (normal, d) 使 normal·p + d = 0。"""
    best = None
    n = len(P)
    rng = np.random.default_rng(0)
    for _ in range(iters):
        idx = rng.choice(n, 3, replace=False)
        a, b, c = P[idx]
        v1 = b - a; v2 = c - a
        normal = np.cross(v1, v2)
        ln = np.linalg.norm(normal)
        if ln < 1e-8:
            continue
        normal = normal / ln
        d = -normal @ a
        dist = np.abs(P @ normal + d)
        inliers = dist < thr
        if inliers.sum() > min_inliers:
            score = inliers.sum()
            if best is None or score > best[0]:
                best = (score, normal, d)
    return best


def main(path):
    P = load_ply(path)
    print("原始点数:", len(P))
    C = clean(P)
    print("清洗后:", len(C))

    # 1) 地板平面：法向接近竖直(0,±1,0) 且位置最低
    best_floor = None
    for _ in range(6):
        r = ransac_plane(C, min_inliers=len(C)//30)
        if r is None:
            break
        score, normal, d = r
        if abs(normal[1]) > 0.7:  # 水平
            height = -d / normal[1] if abs(normal[1]) > 1e-6 else 0
            if best_floor is None or height < best_floor[3]:
                best_floor = (score, normal, d, height)
        # 移除这组内点，继续找下一平面
        dist = np.abs(C @ normal + d)
        C = C[dist >= 0.04]
    if best_floor:
        fs, fn, fd, fh = best_floor
        print(f"地板平面: 高={fh:.2f} 内点={fs} normal={np.round(fn,2)}")

    # 2) 墙面：竖直平面(法向水平)，RANSAC 迭代提取多条墙
    walls = []
    Pw = C.copy()
    for _ in range(12):
        r = ransac_plane(Pw, min_inliers=len(C)//50)
        if r is None:
            break
        score, normal, d = r
        if abs(normal[1]) < 0.5:  # 竖直（法向接近水平）
            walls.append((score, normal, d))
        dist = np.abs(Pw @ normal + d)
        Pw = Pw[dist >= 0.04]
    print("检测到墙面数:", len(walls))
    for i, (s, n, d) in enumerate(walls):
        print(f"  墙{i}: 内点={s} 法向={np.round(n,2)} d={d:.2f}")

    # 3) 墙线：墙面 ∩ 地板平面(高度 fh) 的直线（用两点表示）
    lines = []
    if best_floor:
        for s, n, d in walls:
            # 墙面法向 n，地板高度 fh：直线上任一点满足 n·p+d=0, y=fh
            # 方向向量 v = n × (0,1,0)
            v = np.cross(n, np.array([0, 1, 0]))
            if np.linalg.norm(v) < 1e-6:
                continue
            v = v / np.linalg.norm(v)
            # 线上一点：解 n·p+d=0, y=fh 的最小范数解
            p0 = -d * n  # n·p0+d=0 的最小范数解（最近原点）
            p0[1] = fh
            # 修正 p0 使满足 n·p0+d=0（在墙上、y=fh）
            # 重新投影：p0 - ((n·p0+d)/(n·n)) n
            p0 = p0 - ((n @ p0 + d)) * n
            p0[1] = fh
            lines.append((p0, v, s))
            print(f"墙线{i}: 基点={np.round(p0,2)} 方向={np.round(v,2)}")
    print("墙线数:", len(lines))

    # 4) 地板凸包 → 房间足迹多边形（MVP 稳健方案）
    if best_floor:
        fn_, fd_, fh_ = best_floor[1], best_floor[2], best_floor[3]
        floor_pts = C[np.abs(C @ fn_ + fd_) < 0.15]
        print(f"地板带内点数: {len(floor_pts)}")
        # 鲁棒离群剔除：质心 + 中位距离 + 2*MAD
        if len(floor_pts) >= 5:
            xz = floor_pts[:, [0, 2]]
            cen = np.median(xz, axis=0)
            d = np.linalg.norm(xz - cen, axis=1)
            med = np.median(d)
            mad = np.median(np.abs(d - med))
            keep = d < med + 2.5 * mad
            xz = xz[keep]
            print(f"离群剔除后: {keep.sum()} 点")
            # 文本密度图
            xmin, xmax = xz[:, 0].min(), xz[:, 0].max()
            zmin, zmax = xz[:, 1].min(), xz[:, 1].max()
            print(f"地板范围: x[{xmin:.1f},{xmax:.1f}] z[{zmin:.1f},{zmax:.1f}]")
            nx, nz = 40, 20
            grid = np.zeros((nz, nx), dtype=int)
            xi = ((xz[:, 0] - xmin) / (xmax - xmin) * (nx - 1)).astype(int)
            zi = ((xz[:, 1] - zmin) / (zmax - zmin) * (nz - 1)).astype(int)
            for i in range(len(xz)):
                grid[zi[i], xi[i]] += 1
            print("地板点密度分布（z从上到下, x从左到右）:")
            for row in grid:
                print("".join("#" if v > 3 else ("+" if v > 0 else ".") for v in row))
        if len(xz) >= 3:
            hull_pts = convex_hull(xz)
            print("房间足迹多边形顶点 (x,z):")
            for v in hull_pts:
                print(f"  ({v[0]:.2f}, {v[1]:.2f})")
            # 粗略面积（鞋带公式）
            area = 0.5 * abs(sum(hull_pts[i][0]*hull_pts[(i+1)%len(hull_pts)][1]
                               - hull_pts[(i+1)%len(hull_pts)][0]*hull_pts[i][1]
                               for i in range(len(hull_pts))))
            print(f"房间面积 ≈ {area:.1f} m²")


def convex_hull(xz):
    """返回凸包顶点索引（按顺序）。"""
    pts = sorted(set(map(tuple, np.round(xz, 3))))
    pts = [np.array(p) for p in pts]
    if len(pts) <= 1:
        return [0]
    def cross(o, a, b):
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0])
    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/room.ply")
