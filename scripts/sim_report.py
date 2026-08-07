#!/usr/bin/env python3
"""模拟报告：真实 13:14 PLY 房间多边形 + 震命宫位 + 合成物体 + 规则求值 → 两张图（宫位图/报告）。

复刻 App 逻辑：RoomPolygon.buildPolygon（含玻璃鬼影 cullGhost）、MingGua（震命吉凶方）、
ConditionEvaluator（关键事实）、RuleEngine（过滤无条件）。
"""
import json, glob, math, sys, random
from collections import defaultdict
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import Wedge, Polygon as MplPoly, Circle
from matplotlib import font_manager
for _fp in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-DemiLight.ttc"):
    try:
        font_manager.fontManager.addfont(_fp)
    except Exception:
        pass
plt.rcParams["font.sans-serif"] = ["Noto Sans CJK SC", "Noto Sans CJK JP", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

ROOT = "/home/aci/桌面/fengshui-app"

# ---------- 1. RoomPolygon 复刻 ----------
def clean(pts):
    out=[]
    for p in pts:
        x,y,z=p
        if all(math.isfinite(v) for v in p) and -0.6<y<3.5 and abs(x)<15 and abs(z)<15:
            out.append(p)
    return out

def ransac_plane(points, thr, min_inliers, iters=120):
    rng=random.Random(0); n=len(points); best=None
    if n<3: return None
    for _ in range(iters):
        i,j,k=rng.sample(range(n),3)
        a,b,c=points[i],points[j],points[k]
        v1=(b[0]-a[0],b[1]-a[1],b[2]-a[2]); v2=(c[0]-a[0],c[1]-a[1],c[2]-a[2])
        nrm=(v1[1]*v2[2]-v1[2]*v2[1], v1[2]*v2[0]-v1[0]*v2[2], v1[0]*v2[1]-v1[1]*v2[0])
        L=math.sqrt(sum(x*x for x in nrm))
        if L<1e-8: continue
        nrm=(nrm[0]/L,nrm[1]/L,nrm[2]/L)
        d=-(nrm[0]*a[0]+nrm[1]*a[1]+nrm[2]*a[2])
        cnt=sum(1 for p in points if abs(nrm[0]*p[0]+nrm[1]*p[1]+nrm[2]*p[2]+d)<thr)
        if cnt>min_inliers and (best is None or cnt>best[2]): best=(nrm,d,cnt)
    return best

def dist_plane(p, pl):
    nrm,d,_=pl; return abs(nrm[0]*p[0]+nrm[1]*p[1]+nrm[2]*p[2]+d)

def cull_ghost(points, r=0.3, min_n=3):
    if len(points)<10: return points
    grid=defaultdict(list)
    def cell(x,z): return (int(math.floor(x/r)), int(math.floor(z/r)))
    for i,p in enumerate(points): grid[cell(p[0],p[1])].append(i)
    keep=[False]*len(points)
    for i,p in enumerate(points):
        cx,cz=cell(p[0],p[1]); cnt=0
        for dx in (-1,0,1):
            for dz in (-1,0,1):
                for j in grid[(cx+dx,cz+dz)]:
                    if j==i: continue
                    q=points[j]
                    if math.hypot(p[0]-q[0],p[1]-q[1])<=r: cnt+=1
        keep[i]=cnt>=min_n
    parent=list(range(len(points)))
    def find(x):
        while parent[x]!=x: parent[x]=parent[parent[x]]; x=parent[x]
        return x
    def union(a,b):
        ra,rb=find(a),find(b)
        if ra!=rb: parent[ra]=rb
    for i,p in enumerate(points):
        if not keep[i]: continue
        cx,cz=cell(p[0],p[1])
        for dx in (-1,0,1):
            for dz in (-1,0,1):
                for j in grid[(cx+dx,cz+dz)]:
                    if j==i or not keep[j]: continue
                    q=points[j]
                    if math.hypot(p[0]-q[0],p[1]-q[1])<=r: union(i,j)
    comp=defaultdict(int)
    for i in range(len(points)):
        if keep[i]: comp[find(i)]+=1
    if not comp: return points
    mx=max(comp.values())
    if mx<3: return points
    mroot=max(comp, key=comp.get)
    out=[points[i] for i in range(len(points)) if keep[i] and find(i)==mroot]
    return out if len(out)>=3 else points

def convex_hull(pts):
    pts=sorted(pts)
    def cross(o,a,b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lo=[]
    for p in pts:
        while len(lo)>=2 and cross(lo[-2],lo[-1],p)<=0: lo.pop()
        lo.append(p)
    up=[]
    for p in reversed(pts):
        while len(up)>=2 and cross(up[-2],up[-1],p)<=0: up.pop()
        up.append(p)
    return lo[:-1]+up[:-1]

def fit_min_area_rect(pts):
    best_area=float("inf"); best=None
    for deg in range(90):
        a=math.radians(deg); c=math.cos(a); s=math.sin(a)
        xs=[]; zs=[]
        for x,z in pts:
            xr=x*c+z*s; zr=-x*s+z*c
            xs.append(xr); zs.append(zr)
        area=(max(xs)-min(xs))*(max(zs)-min(zs))
        if area<best_area:
            best_area=area
            mx0,mx1=min(xs),max(xs); mz0,mz1=min(zs),max(zs)
            best=[(q[0]*c-q[1]*s, q[0]*s+q[1]*c) for q in
                  ((mx0,mz0),(mx1,mz0),(mx1,mz1),(mx0,mz1))]
    return best

def build_polygon(raw):
    c=clean(raw)
    if len(c)<50: return None
    floor=None; working=list(c)
    for _ in range(4):
        pl=ransac_plane(working,0.04,len(c)//30)
        if pl is None: continue
        nrm,d,_=pl
        if abs(nrm[1])>0.7:
            h=-d/nrm[1]
            if floor is None or h<floor[2]: floor=(nrm,d,h)
        working=[p for p in working if dist_plane(p,pl)>=0.04]
    if floor is None: return None
    fpts=[p for p in c if dist_plane(p,floor)<0.15]
    if len(fpts)<5: return None
    xz=[(p[0],p[2]) for p in fpts]
    cenX=sorted(x for x,z in xz)[len(xz)//2]; cenZ=sorted(z for x,z in xz)[len(xz)//2]
    dists=sorted(math.hypot(x-cenX,z-cenZ) for x,z in xz)
    med=dists[len(dists)//2]
    mad=sorted(abs(x-med) for x in dists)[len(dists)//2]
    keep=[(x,z) for x,z in xz if math.hypot(x-cenX,z-cenZ) < med+2.5*mad]
    if len(keep)<3: return None
    keep2=cull_ghost(keep)
    if len(keep2)<3: return None
    return fit_min_area_rect(keep2)

# ---------- 2. 命卦（震命 2007男）----------
BAGUA = json.load(open(f"{ROOT}/rules/draft/bagua_data.json"))
COMP = ["北","东北","东","东南","南","西南","西","西北"]
IDX = [7,0,2,1,3,4,6,5]
GOOD_STARS = {"生气","延年","天乙","伏位"}
TD = {"坎":"北","艮":"东北","震":"东","巽":"东南","离":"南","坤":"西南","兑":"西","乾":"西北"}
def gua_1990():
    return "坎"  # 1990男
def gua_2007():
    return "震"  # 2007男 (100-7=93%9=3)
def compute_gua(t):
    stars=[BAGUA["youNianOrder"][t][i:i+2] for i in range(0,16,2)]
    base=TD[t]; start=COMP.index(base)
    dirs=[COMP[(start+i)%8] for i in range(8)]
    good=[]; bad=[]
    for i in range(8):
        if stars[IDX[i]] in GOOD_STARS: good.append(dirs[i])
        else: bad.append(dirs[i])
    return good, bad

NORTH = -2.775  # 设备真实值 rad
GUA_T = "震"
GOOD, BAD = compute_gua(GUA_T)

# ---------- 3. 合成物体 + 未识别 ----------
def load_ply(p):
    pts=[]
    with open(p) as f:
        started=False
        for line in f:
            if line.strip()=="end_header": started=True; continue
            if not started: continue
            parts=line.split()
            if len(parts)>=3: pts.append((float(parts[0]),float(parts[1]),float(parts[2])))
    return pts

hull = build_polygon(load_ply("/tmp/opencode/room_1314.ply"))
if hull is None:
    print("房间多边形生成失败，用矩形回退")
    hull=[(-1.5,-1.5),(1.5,-1.5),(1.5,1.5),(-1.5,1.5)]
cx=sum(x for x,z in hull)/len(hull); cz=sum(z for x,z in hull)/len(hull)
print(f"房间多边形: {len(hull)} 顶点, 中心=({cx:.2f},{cz:.2f})")
# 面积（鞋带）
def area(poly):
    s=0
    for i in range(len(poly)):
        a=poly[i]; b=poly[(i+1)%len(poly)]
        s+=a[0]*b[1]-b[0]*a[1]
    return abs(s)/2
print(f"面积: {area(hull):.1f} m²")

def in_poly(p, poly):
    x,z=p; inside=False; j=len(poly)-1
    for i in range(len(poly)):
        a,b=poly[i],poly[j]
        if (a[1]>z)!=(b[1]>z) and x < (b[0]-a[0])*(z-a[1])/(b[1]-a[1]+1e-12)+a[0]:
            inside=not inside
        j=i
    return inside

# 依宫位放置物体：sector 角度(自北顺时针) + northAngle → 世界方向
SECT_ANG = {s:i*45 for i,s in enumerate(COMP)}
def place(sector, r):
    a = math.radians(SECT_ANG[sector] + math.degrees(NORTH))
    x = cx + r*math.sin(a); z = cz + r*math.cos(a)
    if not in_poly((x,z), hull):
        for rr in (0.8,0.6,0.4):
            x=cx+rr*math.sin(a); z=cz+rr*math.cos(a)
            if in_poly((x,z), hull): break
    return (x,z)

R = 1.4
# 床在凶方西(金方)；灶近床；厕在南(火)；盆栽在西(金)；沙发东(吉)；衣柜西南(吉)；冰箱北
bed = place("西", R)
stove = (bed[0]+1.1, bed[1]+0.4)          # 近床 → 床忌近灶
toilet = place("南", R)
plant = place("西", 1.1)                    # 盆栽忌金方
sofa = place("东", R)
wardrobe = place("西南", R)
fridge = place("北", R)
UNK = [place("东南",1.2), place("东北",1.3), place("北",0.9)]

objects = [
    ("bed", bed, 1.8, 2.0), ("stove", stove, 0.7, 0.6), ("toilet", toilet, 0.4, 0.6),
    ("plant", plant, 0.4, 0.4), ("sofa", sofa, 2.0, 0.9), ("wardrobe", wardrobe, 1.6, 0.6),
    ("fridge", fridge, 0.7, 0.7),
]
print("物体位置:")
for t,p,_,_ in objects: print(f"  {t}: ({p[0]:.2f},{p[1]:.2f})")

# ---------- 4. 规则求值复刻 ----------
def sector_of(p):
    ang = math.degrees(math.atan2(p[0]-cx, p[1]-cz)) - math.degrees(NORTH)
    ang = ang % 360
    for i,s in enumerate(COMP):
        if ang < (i*45+22.5):
            return s
    return "北"

def dist(a,b): return math.hypot(a[0]-b[0], a[1]-b[1])
def dist_wall(p):
    return min(dist(p,(a[0],a[1])) for a in hull)
def near(t, o, thr=2.5):
    return any(dist(o[1], q[1])<thr for q in objects if q[0]==t)

OBJ_ELEM = {"stove":"火","toilet":"水","fridge":"水","plant":"木","sofa":"土","wardrobe":"土",
            "bed":"木","dining":"木","desk":"木","study":"木","door":"木","window":"木",
            "front_desk":"金","cashier":"金","finance_room":"金","pillar":"金"}
SEC_ELEM = {"北":"水","东北":"土","东":"木","东南":"木","南":"火","西南":"土","西":"金","西北":"金"}
KE = {"木":"土","土":"水","水":"火","火":"金","金":"木"}

def eval_fact(fact, o):
    t=o[0]; p=o[1]; sec=sector_of(p)
    if fact=="inKillSector": return sec in BAD
    if fact=="inGoodSector": return sec in GOOD
    if fact=="elementClashWithSector":
        e1=OBJ_ELEM.get(t); e2=SEC_ELEM.get(sec)
        return e1 and e2 and (KE.get(e1)==e2 or KE.get(e2)==e1)
    if fact=="inWoodSector": return sec in ("东","东南")
    if fact=="notInWoodSector": return sec in ("西","西北","西南")
    if fact=="inWest": return sec=="西"
    if fact=="inWenChang": return sec=="东南"
    if fact=="nearStove": return near("stove",o)
    if fact=="nearToilet": return near("toilet",o)
    if fact=="nearBedroom": return near("bed",o)
    if fact=="facesDoor": return near("door",o,2.5)
    if fact=="facesStove": return near("stove",o,2.5)
    if fact=="oppositeBed": return near("bed",o)
    if fact=="noBacking": return dist_wall(p) > o[3]/2+0.25
    if fact=="backToWall": return dist_wall(p) < o[3]/2+0.25
    return False

def load_rules():
    rules=[]
    for f in glob.glob(f"{ROOT}/rules/draft/*.json"):
        if f.endswith("bagua_data.json"): continue
        for r in json.load(open(f)):
            if r.get("status")=="active": rules.append(r)
    return rules

def ev_text(r):
    ev = r.get("evidence", [])
    if not ev: return ""
    e = ev[0]
    book = e.get("book",""); ch = e.get("chapter","")
    return f"{book}{('·'+ch) if ch else ''}：{e.get('original','')}"

def evaluate():
    hits=[]
    for r in load_rules():
        c=r.get("condition",{})
        req=c.get("require",{}); sp=c.get("spatial",{})
        if not sp and set(req.keys())<= {"room","birth","direction"}: continue
        if any(t not in ("room","birth","direction") and not any(o[0]==t for o in objects) for t in req): continue
        mm=c.get("match",{})
        if mm.get("mingua") and mm["mingua"]!=("东四命" if GUA_T in "震巽坎离" else "西四命"): continue
        if mm.get("houseTrigram") and mm["houseTrigram"]!=GUA_T: continue
        if not sp:
            hits.append((r["severity"], r["title"], r["finding"].get("remedy",[]), r["ruleId"], ev_text(r)))
            continue
        ok=False
        for t,facts in sp.items():
            for o in objects:
                if o[0]!=t: continue
                if any(eval_fact(f,o) for f in (facts if isinstance(facts,list) else [facts])):
                    ok=True; break
            if ok: break
        if ok:
            hits.append((r["severity"], r["title"], r["finding"].get("remedy",[]), r["ruleId"], ev_text(r)))
    return hits

hits=evaluate()
bad_hits=[h for h in hits if h[0] in ("凶","大凶")]
good_hits=[h for h in hits if h[0] in ("吉","大吉")]
print(f"命中规则: 凶/大凶 {len(bad_hits)}, 吉 {len(good_hits)}")
for s,t,_,_,e in bad_hits: print(f"  [凶] {t}")
for s,t,_,_,e in good_hits: print(f"  [吉] {t}")

# ---------- 5. 图1：宫位图 ----------
fig, ax = plt.subplots(figsize=(7,6), dpi=110)
xmin=min(p[0] for p in hull)-0.3; xmax=max(p[0] for p in hull)+0.3
zmin=min(p[1] for p in hull)-0.3; zmax=max(p[1] for p in hull)+0.3
ccx=(xmin+xmax)/2; ccz=(zmin+zmax)/2
maxR=max(math.hypot(p[0]-ccx,p[1]-ccz) for p in hull)
scale=(min(xmax-xmin,zmax-zmin)/2-0.3)/maxR
def toxy(p):
    dx=p[0]-ccx; dz=p[1]-ccz
    a=math.atan2(dx,dz)-NORTH
    return (ccx+math.sin(a)*scale*maxR, ccz-math.cos(a)*scale*maxR)
# 宫位扇形
for i,s in enumerate(COMP):
    col = "#35b95f" if s in GOOD else ("#e05040" if s in BAD else "#cccccc")
    ax.add_patch(Wedge((ccx,ccz), scale*maxR, (270+i*45-22.5)%360, (270+i*45+22.5)%360,
                       color=col, alpha=0.30, ec="none"))
    # 标签
    aa=math.radians(270+i*45)
    ax.text(ccx+scale*maxR*0.78*math.cos(aa), ccz+scale*maxR*0.78*math.sin(aa), s,
            ha="center", va="center", fontsize=11, fontweight="bold", color="#555")
# 多边形
poly_pts=[toxy(p) for p in hull]
ax.add_patch(MplPoly(poly_pts, closed=True, fill=False, ec="#333", lw=2.5))
ax.fill(*zip(*poly_pts), color="#f4f4f4")
# 物体
for t,p,dimx,dimz in objects:
    x,y=toxy(p)
    col = "#d03020" if sector_of(p) in BAD else ("#208040" if sector_of(p) in GOOD else "#888")
    ax.scatter([x],[y], s=110, c=col, zorder=5)
    ax.text(x, y+0.13, t, ha="center", fontsize=8, color=col, zorder=6)
# 未识别灰点
for p in UNK:
    x,y=toxy(p)
    ax.scatter([x],[y], s=90, c="#aaaaaa", marker="x", zorder=5)
ax.text(toxy(UNK[0])[0], toxy(UNK[0])[1]+0.13, "未识别", ha="center", fontsize=8, color="#888")
# 北向
ax.annotate("北↑", xy=(ccx, ccz+scale*maxR*0.98), ha="center", fontsize=12, fontweight="bold")
ax.set_xlim(xmin-0.2, xmax+0.2); ax.set_ylim(zmin-0.2, zmax+0.2)
ax.set_aspect("equal"); ax.axis("off")
ax.set_title(f"房间宫位图 · 震命(2007男) · 面积{area(hull):.1f}㎡\n绿=吉方 红=凶方 红点=凶位物体 绿点=吉位物体 ×=未识别", fontsize=11)
plt.tight_layout()
plt.savefig("/tmp/opencode/sim_bagua_map.png", dpi=110)
plt.close()
print("已输出 /tmp/opencode/sim_bagua_map.png")

# ---------- 6. 图2：报告（像素坐标 + 行高估算，避免重叠/越界） ----------
DPI = 110
W = 8.5 * DPI
MARGIN_X = 14
TEXT_W = W - 2 * MARGIN_X

def lines_needed(text, fontsize):
    cpl = max(1, int(TEXT_W / fontsize))   # CJK 每字≈fontsize 宽（保守）
    return max(1, math.ceil(len(text) / cpl))

def block_h(lines, fontsize):
    return lines * fontsize * 1.5

def item_h(it):
    kind = it[0]
    if kind == "title": return block_h(1, 16)
    if kind == "text": return block_h(lines_needed(it[1], it[2]), it[2])
    if kind == "header": return block_h(1, it[2])
    if kind == "space": return it[1]
    if kind == "badcard":
        _, sev, title, ev, remedy = it
        h = block_h(lines_needed(f"[{sev}] {title}", 10.5), 10.5)
        if ev: h += block_h(lines_needed("原文：" + ev, 8.5), 8.5)
        if remedy: h += block_h(lines_needed(remedy, 9), 9)
        return h + 16
    if kind == "goodcard":
        _, sev, title, ev = it
        h = block_h(lines_needed(f"[{sev}] {title}", 10), 10)
        if ev: h += block_h(lines_needed("原文：" + ev, 8), 8)
        return h + 12
    return 20

items = []
items.append(("title", "风水分析报告（模拟）", 16))
items.append(("text", f"命卦：{GUA_T}命（东四命）· 吉方：{'/'.join(sorted(GOOD))} · 凶方：{'/'.join(sorted(BAD))} · 北向已校准", 10))
items.append(("text", f"识别物体：{len(objects)}（含未识别 {len(UNK)} 个，不在判断类别内，不计入分析）", 10))
items.append(("space", 8))
items.append(("header", f"🔴 凶兆与整改（{len(bad_hits)}）", 13, "#c02020"))
if not bad_hits:
    items.append(("text", "无凶兆", 10))
for sev, title, remedy, rid, ev in bad_hits:
    remedy_s = "整改：" + "；".join(remedy) if remedy else ""
    items.append(("badcard", sev, title, ev, remedy_s))
items.append(("space", 8))
items.append(("header", f"🟢 吉兆（{len(good_hits)}）", 13, "#1a7a30"))
if not good_hits:
    items.append(("text", "当前无吉兆触发", 10))
for sev, title, remedy, rid, ev in good_hits:
    items.append(("goodcard", sev, title, ev))
items.append(("space", 8))
items.append(("header", "未识别物体", 12, "#333"))
items.append(("text", f"{len(UNK)} 个检测未落入判断类别（如椅、凳等），标记为未识别，不计入风水分析；待模型词表扩展后可识别。", 9))

H = int(14 + sum(item_h(it) for it in items) + 14)
fig, ax = plt.subplots(figsize=(W / DPI, H / DPI), dpi=DPI)
ax.axis("off"); ax.set_xlim(0, W); ax.set_ylim(H, 0)   # y 向下
y = 14

def put(x, text, fontsize, color, bold, lines):
    global y
    ax.text(x, y, text, fontsize=fontsize, va="top", color=color,
            fontweight="bold" if bold else "normal")
    y += fontsize * 1.5 * lines

for it in items:
    kind = it[0]
    if kind == "title":
        put(MARGIN_X, it[1], it[2], "#000", True, 1)
    elif kind == "text":
        put(MARGIN_X, it[1], it[2], "#333", False, lines_needed(it[1], it[2]))
    elif kind == "header":
        put(MARGIN_X, it[1], it[2], it[3], True, 1)
    elif kind == "space":
        y += it[1]
    elif kind == "badcard":
        _, sev, title, ev, remedy = it
        h = item_h(it)
        ax.add_patch(mpatches.FancyBboxPatch((MARGIN_X - 4, y - 2), W - 2 * MARGIN_X + 8, h,
                      boxstyle="round,pad=2", fc="#fdecea", ec="#c02020", lw=1, zorder=0))
        put(MARGIN_X, f"[{sev}] {title}", 10.5, "#c02020", True, lines_needed(f"[{sev}] {title}", 10.5))
        if ev: put(MARGIN_X, "原文：" + ev, 8.5, "#9a5c5c", False, lines_needed("原文：" + ev, 8.5))
        if remedy: put(MARGIN_X, remedy, 9, "#7a3c3c", False, lines_needed(remedy, 9))
        y += 8
    elif kind == "goodcard":
        _, sev, title, ev = it
        h = item_h(it)
        ax.add_patch(mpatches.FancyBboxPatch((MARGIN_X - 4, y - 2), W - 2 * MARGIN_X + 8, h,
                      boxstyle="round,pad=2", fc="#eaf5ea", ec="#1a7a30", lw=1, zorder=0))
        put(MARGIN_X, f"[{sev}] {title}", 10, "#1a7a30", True, lines_needed(f"[{sev}] {title}", 10))
        if ev: put(MARGIN_X, "原文：" + ev, 8, "#5c7a5c", False, lines_needed("原文：" + ev, 8))
        y += 8

plt.savefig("/tmp/opencode/sim_report.png", dpi=DPI)
plt.close()
print("已输出 /tmp/opencode/sim_report.png")
