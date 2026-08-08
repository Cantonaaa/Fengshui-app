#!/usr/bin/env python3
"""离线验证：房间轮廓"当前算法（地板点足迹）" vs "改进算法（全点密度网格+闭合+轨迹约束）"。
真机数据：pointclouds/room*.ply + room*.track。输出对比指标 + 俯视 PNG。
"""
import glob
import os

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

CELL = 0.2


def load_ply(path):
    pts = []
    for line in open(path):
        if line.startswith("end_header"):
            break
    for line in open(path):
        p = line.split()
        if len(p) >= 3:
            try:
                pts.append([float(p[0]), float(p[1]), float(p[2])])
            except ValueError:
                pass
    return np.array(pts, dtype=np.float64)


def load_track(path):
    tr = []
    for line in open(path):
        p = line.split()
        if len(p) >= 2:
            try:
                tr.append([float(p[0]), float(p[1])])
            except ValueError:
                pass
    return np.array(tr, dtype=np.float64)


def clean(P):
    P = P[np.isfinite(P).all(axis=1)]
    m = (P[:, 1] > -0.6) & (P[:, 1] < 3.5) & (np.abs(P[:, 0]) < 15) & (np.abs(P[:, 2]) < 15)
    return P[m]


def ransac_plane(P, iters=200, thr=0.04, min_inliers=30, seed=0):
    best = None
    n = len(P)
    if n < 3:
        return None
    rng = np.random.default_rng(seed)
    for _ in range(iters):
        idx = rng.choice(n, 3, replace=False)
        a, b, c = P[idx]
        v1, v2 = b - a, c - a
        normal = np.cross(v1, v2)
        ln = np.linalg.norm(normal)
        if ln < 1e-8:
            continue
        normal /= ln
        d = -normal @ a
        cnt = int((np.abs(P @ normal + d) < thr).sum())
        if cnt > min_inliers and (best is None or cnt > best[0]):
            best = (cnt, normal, d)
    return best


def floor_plane(C):
    best = None
    work = C.copy()
    for _ in range(6):
        r = ransac_plane(work)
        if r is None:
            break
        cnt, normal, d = r
        if abs(normal[1]) > 0.7:
            h = -d / normal[1] if abs(normal[1]) > 1e-6 else 0.0
            if best is None or h < best[3]:
                best = (cnt, normal, d, h)
        dist = np.abs(work @ normal + d)
        work = work[dist >= 0.04]
    return best


def largest_component(occ):
    """4-连通最大连通分量。"""
    gh, gw = occ.shape
    label = np.zeros((gh, gw), dtype=np.int32)
    comp = 0
    best = (0, None)
    for r in range(gh):
        for c in range(gw):
            if not occ[r][c] or label[r][c]:
                continue
            comp += 1
            stack = [(r, c)]
            label[r][c] = comp
            size = 0
            while stack:
                rr, cc = stack.pop()
                size += 1
                for dr, dc in ((0, 1), (1, 0), (0, -1), (-1, 0)):
                    nr, nc = rr + dr, cc + dc
                    if 0 <= nr < gh and 0 <= nc < gw and occ[nr][nc] and not label[nr][nc]:
                        label[nr][nc] = comp
                        stack.append((nr, nc))
            if size > best[0]:
                best = (size, comp)
    if best[1] is None:
        return np.zeros_like(occ)
    return label == best[1]


def boundary_from_grid(occ, x0, z0):
    """网格占用（最大分量已取）→ 外轮廓（Moore 追踪 + 去共线）。"""
    gh, gw = occ.shape
    start = None
    for r in range(gh):
        for c in range(gw):
            if occ[r][c]:
                start = (r, c)
                break
        if start:
            break
    if start is None:
        return None
    br, bc = start
    prev_r, prev_c = br, bc - 1
    dirs = [(0, -1), (-1, -1), (-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1)]
    boundary = [(br, bc)]
    guard = 0
    while True:
        guard += 1
        if guard > 2_000_000:
            return None
        try:
            di = dirs.index((prev_r - br, prev_c - bc))
        except ValueError:
            return None
        nxt = None
        for k in range(1, 9):
            dr, dc = dirs[(di + k) & 7]
            nr, nc = br + dr, bc + dc
            if 0 <= nr < gh and 0 <= nc < gw and occ[nr][nc]:
                nxt = (nr, nc)
                break
        if nxt is None:
            return None
        prev_r, prev_c = br, bc
        br, bc = nxt
        if (br, bc) == (start[0], start[1]):
            break
        boundary.append((br, bc))
    if len(boundary) < 4:
        return None
    xz = np.array([[x0 + (c + 0.5) * CELL, z0 + (r + 0.5) * CELL] for (r, c) in boundary])
    return simplify(xz)


def simplify(pts, tol=1e-3):
    out = []
    for p in pts:
        if out and np.linalg.norm(p - out[-1]) < tol:
            continue
        out.append(p)
    while len(out) >= 3:
        changed = False
        n = len(out)
        new = []
        for i in range(n):
            a = out[(i - 1) % n]
            b = out[i]
            c = out[(i + 1) % n]
            cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
            if abs(cross) < 1e-6:
                changed = True
                continue
            new.append(b)
        if not changed or len(new) < 3:
            break
        out = new
    return np.array(out)


def area(poly):
    if poly is None or len(poly) < 3:
        return 0.0
    s = 0.0
    for i in range(len(poly)):
        a = poly[i]
        b = poly[(i + 1) % len(poly)]
        s += a[0] * b[1] - b[0] * a[1]
    return abs(s) / 2.0


def point_in_poly(p, poly):
    if poly is None or len(poly) < 3:
        return False
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, zi = poly[i]
        xj, zj = poly[j]
        if (zi > p[1]) != (zj > p[1]) and p[0] < (xj - xi) * (p[1] - zi) / (zj - zi) + xi:
            inside = not inside
        j = i
    return inside


def grid_from_xz(xz):
    x0, x1 = xz[:, 0].min(), xz[:, 0].max()
    z0, z1 = xz[:, 1].min(), xz[:, 1].max()
    gw = int(np.ceil((x1 - x0) / CELL)) + 1
    gh = int(np.ceil((z1 - z0) / CELL)) + 1
    if gw > 1024 or gh > 1024:
        return None, None, None
    occ = np.zeros((gh, gw), dtype=bool)
    for p in xz:
        c = int((p[0] - x0) / CELL)
        r = int((p[1] - z0) / CELL)
        if 0 <= r < gh and 0 <= c < gw:
            occ[r][c] = True
    return occ, x0, z0


def current_outline(C, fh):
    """当前算法：地板带 → MAD → 最大分量网格 → 外轮廓。"""
    fpts = C[np.abs(C[:, 1] - fh) < 0.15]
    if len(fpts) < 5:
        return None
    xz = fpts[:, [0, 2]]
    cen = np.median(xz, axis=0)
    d = np.linalg.norm(xz - cen, axis=1)
    med = np.median(d)
    mad = np.median(np.abs(d - med))
    keep = d < med + 2.5 * mad
    xz = xz[keep]
    if len(xz) < 3:
        return None
    occ, x0, z0 = grid_from_xz(xz)
    if occ is None:
        return None
    occ = largest_component(occ)
    return boundary_from_grid(occ, x0, z0)


def dilate_erode(occ):
    gh, gw = occ.shape
    dil = np.zeros_like(occ)
    for r in range(gh):
        for c in range(gw):
            if occ[max(0, r - 1):r + 2, max(0, c - 1):c + 2].any():
                dil[r][c] = True
    ero = np.zeros_like(dil)
    for r in range(gh):
        for c in range(gw):
            if dil[max(0, r - 1):r + 2, max(0, c - 1):c + 2].all():
                ero[r][c] = True
    return ero


def proposed_outline(C, track):
    """改进算法（与 App RoomPolygon.improvedOutline 一致）：
    轨迹包络裁剪(墙距≤1.5m, 上界控鬼影) → 全点 XZ 网格 → 形态学闭合 → 最大分量 → 外轮廓；
    下界：轮廓须含 ≥50% 轨迹，否则退化轨迹包络外扩矩形。
    """
    if len(track) < 3:
        return None
    tx0, tx1 = track[:, 0].min(), track[:, 0].max()
    tz0, tz1 = track[:, 1].min(), track[:, 1].max()
    wm = 1.5
    xz = C[:, [0, 2]]
    m = (xz[:, 0] >= tx0 - wm) & (xz[:, 0] <= tx1 + wm) & (xz[:, 1] >= tz0 - wm) & (xz[:, 1] <= tz1 + wm)
    xz = xz[m]
    if len(xz) < 20:
        return None
    occ, x0, z0 = grid_from_xz(xz)
    if occ is None:
        return None
    occ = dilate_erode(occ)
    occ = largest_component(occ)
    poly = boundary_from_grid(occ, x0, z0)
    inside = sum(1 for t in track if point_in_poly(t, poly)) if poly is not None else 0
    if poly is None or inside < max(3, len(track) * 0.5):
        m2 = 0.4
        return np.array([
            [tx0 - m2, tz0 - m2], [tx1 + m2, tz0 - m2],
            [tx1 + m2, tz1 + m2], [tx0 - m2, tz1 + m2]
        ])
    return poly


def ransac_lines_2d(xz, thr=0.15, min_inl=20, max_lines=6, seed=1):
    """D: RANSAC 主导墙线，返回 [(nx,nz,d,inliers,angle_deg)]。"""
    if len(xz) < 4:
        return []
    rng = np.random.default_rng(seed)
    remaining = np.asarray(xz, float)
    lines = []
    for _ in range(max_lines):
        if len(remaining) < 4:
            break
        best = None
        for _ in range(120):
            idx = rng.choice(len(remaining), 2, replace=False)
            a, b = remaining[idx]
            dx, dz = b - a
            ln = np.hypot(dx, dz)
            if ln < 0.2:
                continue
            nx, nz = -dz / ln, dx / ln
            d = -(nx * a[0] + nz * a[1])
            cnt = int((np.abs(remaining @ np.array([nx, nz]) + d) < thr).sum())
            if cnt >= min_inl and (best is None or cnt > best[3]):
                best = (nx, nz, d, cnt, np.degrees(np.arctan2(dz, dx)))
        if best is None:
            break
        nx, nz, d, cnt, ang = best
        # D 精修：内点最小二乘主成分方向（借鉴 Deep3D）
        dist = np.abs(remaining @ np.array([nx, nz]) + d)
        inl = remaining[dist < thr]
        if len(inl) >= 2:
            cx, cz = inl.mean(0)
            q = inl - inl.mean(0)
            ang2 = 0.5 * np.arctan2(2 * (q[:, 0] * q[:, 1]).sum(), (q[:, 0] ** 2 - q[:, 1] ** 2).sum())
            dx2, dz2 = np.cos(ang2), np.sin(ang2)
            n2x, n2z = -dz2, dx2
            d2 = -(n2x * cx + n2z * cz)
            nx, nz, d, cnt = n2x, n2z, d2, len(inl)
            ang = np.degrees(ang2)
        lines.append((nx, nz, d, cnt, ang))
        dist = np.abs(remaining @ np.array([nx, nz]) + d)
        remaining = remaining[dist >= thr]
    return lines


def dominant_axis(lines, deg_tol=8.0, conf=0.6):
    """B: 主轴（mod 90），置信门控。"""
    if len(lines) < 2:
        return None
    axes = [(l[4] % 180.0) % 90.0 for l in lines]
    tot = sum(l[3] for l in lines)
    if tot <= 0:
        return None
    best_a, best_s = axes[0], 0.0
    for cand in axes:
        score = 0.0
        for i, l in enumerate(lines):
            dd = abs(axes[i] - cand)
            if dd > 90:
                dd = 180 - dd
            if dd <= deg_tol:
                score += l[3]
        if score > best_s:
            best_s, best_a = score, cand
    if best_s < conf * tot:
        return None
    return np.radians(best_a)


def simplify_dp(poly, tol=0.25):
    """C: Douglas-Peucker 简化（闭合多边形）。"""
    poly = list(poly)
    if len(poly) <= 3:
        return np.array(poly)
    i0 = i1 = 0
    md = -1
    for i in range(len(poly)):
        for j in range(i + 1, len(poly)):
            d = np.hypot(poly[i][0] - poly[j][0], poly[i][1] - poly[j][1])
            if d > md:
                md, i0, i1 = d, i, j

    def dseg(p, a, b):
        dx, dz = b - a
        l2 = dx * dx + dz * dz
        if l2 < 1e-9:
            return np.hypot(p[0] - a[0], p[1] - a[1])
        t = np.clip(((p - a) @ (b - a)) / l2, 0, 1)
        q = a + t * (b - a)
        return np.hypot(*(p - q))

    def chain(fr, to):
        md2, idx = -1, -1
        i = (fr + 1) % len(poly)
        while i != to:
            d = dseg(poly[i], poly[fr], poly[to])
            if d > md2:
                md2, idx = d, i
            i = (i + 1) % len(poly)
        if md2 > tol and idx >= 0:
            return chain(fr, idx) + chain(idx, to)[1:]
        return [poly[fr], poly[to]]

    out = chain(i0, i1)[:-1] + chain(i1, i0)
    return np.array(out)


def orthogonalize(poly, axis):
    """B: 旋转到主轴 → 顶点吸附 0.05 → 旋转回；非正交返回 None。"""
    c, s = np.cos(-axis), np.sin(-axis)
    rp = np.array([[p[0] * c - p[1] * s, p[0] * s + p[1] * c] for p in poly])
    n = len(rp)
    near = 0
    for i in range(n):
        a, b = rp[i], rp[(i + 1) % n]
        ang = np.degrees(np.arctan2(b[1] - a[1], b[0] - a[0]))
        aa = ((ang + 90) % 180) - 90
        if abs(aa) < 8 or abs(abs(aa) - 90) < 8:
            near += 1
    if near < 0.7 * n:
        return None
    g = 0.05
    out = np.array([[round(p[0] / g) * g, round(p[1] / g) * g] for p in rp])
    c2, s2 = np.cos(axis), np.sin(axis)
    return np.array([[p[0] * c2 - p[1] * s2, p[0] * s2 + p[1] * c2] for p in out])


def remove_spikes(poly, min_len=0.3):
    """C: 毛刺去除——相邻两边都过短的顶点删掉。"""
    poly = list(poly)
    n = len(poly)
    if n < 4:
        return np.array(poly)
    out = []
    for i in range(n):
        a = poly[(i - 1) % n]; b = poly[i]; c = poly[(i + 1) % n]
        e1 = np.hypot(b[0] - a[0], b[1] - a[1]); e2 = np.hypot(c[0] - b[0], c[1] - b[1])
        if e1 < min_len and e2 < min_len:
            continue
        out.append(b)
    return np.array(out) if len(out) >= 3 else np.array(poly)


def proposed_outline_v2(C, track):
    """改进 v2：grid 基础 + D(墙线 RANSAC) + B(正交化) + C(DP 简化)。墙证据=墙带点+墙段。"""
    # 基础 grid 轮廓（复用 proposed_outline 逻辑，但返回网格轮廓供后处理）
    if len(track) < 3:
        return None
    tx0, tx1 = track[:, 0].min(), track[:, 0].max()
    tz0, tz1 = track[:, 1].min(), track[:, 1].max()
    wm = 1.5
    xz = C[:, [0, 2]]
    m = (xz[:, 0] >= tx0 - wm) & (xz[:, 0] <= tx1 + wm) & (xz[:, 1] >= tz0 - wm) & (xz[:, 1] <= tz1 + wm)
    xz = xz[m]
    if len(xz) < 20:
        return None
    occ, x0, z0 = grid_from_xz(xz)
    if occ is None:
        return None
    occ = dilate_erode(occ)
    occ = largest_component(occ)
    poly = boundary_from_grid(occ, x0, z0)
    if poly is None:
        return None
    # D: 墙线 RANSAC（墙带点模拟 A 的墙证据）
    # 墙带：y 明显高于地板 → 用全部点 xz（墙占主导）
    lines = ransac_lines_2d(xz[: min(len(xz), 15000)])
    axis = dominant_axis(lines)
    if axis is not None:
        orth = orthogonalize(poly, axis)
        if orth is not None:
            poly = orth
    poly = remove_spikes(simplify_dp(poly, 0.25), 0.3)
    inside = sum(1 for t in track if point_in_poly(t, poly))
    if inside < max(3, len(track) * 0.5):
        m2 = 0.4
        return np.array([[tx0 - m2, tz0 - m2], [tx1 + m2, tz0 - m2], [tx1 + m2, tz1 + m2], [tx0 - m2, tz1 + m2]])
    return poly


def main():
    files = sorted(glob.glob("pointclouds/room*.ply"))
    for ply_path in files:
        pid = os.path.basename(ply_path).replace("room", "").replace(".ply", "")
        trk_path = f"pointclouds/room{pid}.track"
        P = load_ply(ply_path)
        track = load_track(trk_path) if os.path.exists(trk_path) else np.empty((0, 2))
        C = clean(P)
        print(f"\n===== room{pid}  清洗后 {len(C)} 点 / 轨迹 {len(track)} 帧 =====")
        if len(C) < 50:
            print("  点数不足")
            continue
        f = floor_plane(C)
        if f is None:
            print("  无地板")
            continue
        fh = f[3]
        cur = current_outline(C, fh)
        prop = proposed_outline(C, track)
        prop2 = proposed_outline_v2(C, track)
        ac, ap, ap2 = area(cur), area(prop), area(prop2)
        print(f"  地板高 {fh:.2f}")
        print(f"  当前(地板点)  面积 {ac:.2f} m²  顶点 {0 if cur is None else len(cur)}")
        print(f"  改进(全点网格)面积 {ap:.2f} m²  顶点 {0 if prop is None else len(prop)}")
        print(f"  改进v2(+D/B/C)面积 {ap2:.2f} m²  顶点 {0 if prop2 is None else len(prop2)}")
        if len(track) > 0:
            in_c = sum(1 for t in track if point_in_poly(t, cur)) if cur is not None else 0
            in_p = sum(1 for t in track if point_in_poly(t, prop)) if prop is not None else 0
            in_p2 = sum(1 for t in track if point_in_poly(t, prop2)) if prop2 is not None else 0
            print(f"  轨迹含于当前 {in_c}/{len(track)}  含于改进 {in_p}/{len(track)}  含于v2 {in_p2}/{len(track)}")
        if prop2 is not None and prop is not None and ap > 0:
            print(f"  v2/改进面积比 {ap2 / ap:.2f}")

        # 可视化
        fig, ax = plt.subplots(figsize=(6, 6))
        ax.scatter(C[:, 0], C[:, 2], s=1, c="lightgray", label="点云")
        if len(track) > 0:
            ax.plot(track[:, 0], track[:, 1], "b-", lw=1.5, label="轨迹")
            ax.scatter(track[:, 0], track[:, 1], s=8, c="b")
        for poly, color, name in ((cur, "r", "当前"), (prop, "g", "改进")):
            if poly is not None:
                pp = np.vstack([poly, poly[0]])
                ax.plot(pp[:, 0], pp[:, 1], color=color, lw=2, label=name)
        ax.set_aspect("equal")
        ax.legend(fontsize=8)
        ax.set_title(f"room{pid}")
        fig.savefig(f"pointclouds/room{pid}_outline.png", dpi=100)
        plt.close(fig)
    print("\n可视化已存 pointclouds/room*_outline.png")


if __name__ == "__main__":
    main()
