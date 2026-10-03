#!/usr/bin/env python3
"""Standalone resource pack (Minecraft 1.21.11+) with the WHOLE Reforged Frost armor
as one 3D model on the helmet item. No MythicArmors / bbmodel needed at runtime."""
import base64, io, json, math, os, sys, zipfile
import numpy as np
from PIL import Image

SRC = sys.argv[1]
OUT = sys.argv[2]
NS, NAME = "mythicarmor", "reforged_frost_armor"
OX, OY, OZ = 8.0, -15.4, 8.0          # bbmodel -> item-model space (keeps everything inside -16..32)
d = json.load(open(SRC))
dec = lambda t: Image.open(io.BytesIO(base64.b64decode(t["source"].split(",", 1)[1]))).convert("RGBA")
atlas, emis = dec(d["textures"][0]), dec(d["textures"][1])
ea = np.array(emis)[:, :, 3] > 0

FLIPU = {"x": ("north", "south", "up", "down"), "z": ("east", "west")}
FLIPV = {"y": ("north", "south", "east", "west"), "z": ("up", "down")}
SWAP = {"x": ("east", "west"), "y": ("up", "down"), "z": ("north", "south")}

def norm(e):
    """Return (from, to, faces) with positive size; mirrored cubes get swapped faces/UVs."""
    f, t = list(map(float, e["from"])), list(map(float, e["to"]))
    faces = {k: dict(v) for k, v in e["faces"].items() if v and "uv" in v}
    for i, ax in enumerate("xyz"):
        if t[i] < f[i]:
            f[i], t[i] = t[i], f[i]
            for n in FLIPU.get(ax, ()):
                if n in faces: u = faces[n]["uv"]; faces[n]["uv"] = [u[2], u[1], u[0], u[3]]
            for n in FLIPV.get(ax, ()):
                if n in faces: u = faces[n]["uv"]; faces[n]["uv"] = [u[0], u[3], u[2], u[1]]
            a, b = SWAP[ax]
            fa, fb = faces.pop(a, None), faces.pop(b, None)
            if fb: faces[a] = fb
            if fa: faces[b] = fa
    inf = e.get("inflate", 0) or 0
    f = [x - inf for x in f]; t = [x + inf for x in t]
    return f, t, faces

elements, prev = [], []
for e in d["elements"]:
    if e.get("visibility", True) is False or e.get("export", True) is False:
        continue
    f, t, faces = norm(e)
    r = [float(x) for x in e.get("rotation", [0, 0, 0])]
    o = [float(x) for x in e.get("origin", [0, 0, 0])]
    el = {"from": [f[0] + OX, f[1] + OY, f[2] + OZ], "to": [t[0] + OX, t[1] + OY, t[2] + OZ], "faces": {}}
    if any(abs(a) > 1e-6 for a in r):
        el["rotation"] = {"x": r[0], "y": r[1], "z": r[2], "origin": [o[0] + OX, o[1] + OY, o[2] + OZ]}
    for n, fc in faces.items():
        x1, y1, x2, y2 = fc["uv"]
        if x1 == x2 or y1 == y2:
            continue
        out = {"uv": [x1 / 16, y1 / 16, x2 / 16, y2 / 16], "texture": "#0"}
        if fc.get("rotation"): out["rotation"] = int(fc["rotation"])
        # glow: faces that are mostly covered by the emissive texture are rendered fullbright
        xa, xb, ya, yb = int(min(x1, x2)), int(math.ceil(max(x1, x2))), int(min(y1, y2)), int(math.ceil(max(y1, y2)))
        if ea[ya:yb, xa:xb].mean() >= 0.5:
            out["light_emission"] = 15
        el["faces"][n] = out
    if el["faces"]:
        elements.append(el)
        prev.append((f, t, faces, r, o))

model = {
    "format_version": "1.21.11",
    "textures": {"0": f"{NS}:item/{NAME}", "particle": f"{NS}:item/{NAME}"},
    "elements": elements,
    "display": {
        # 1.6 * 0.625 = 1.0 -> 1 model unit = 1 bbmodel pixel; shift so the model's head sits on the player's head
        "head": {"scale": [1.6, 1.6, 1.6], "translation": [0, -7.2, 0]},
        "gui": {"rotation": [20, 200, 0], "translation": [0, 0, 0], "scale": [0.33, 0.33, 0.33]},
        "ground": {"scale": [0.3, 0.3, 0.3]},
        "fixed": {"rotation": [0, 180, 0], "scale": [0.33, 0.33, 0.33]},
        "thirdperson_righthand": {"scale": [0.25, 0.25, 0.25]},
        "thirdperson_lefthand": {"scale": [0.25, 0.25, 0.25]},
        "firstperson_righthand": {"scale": [0.25, 0.25, 0.25]},
        "firstperson_lefthand": {"scale": [0.25, 0.25, 0.25]},
    },
}

def png(im):
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()
js = lambda o: json.dumps(o, indent=1).encode()
blank = Image.new("RGBA", (128, 64), (0, 0, 0, 0))   # body layers are invisible: the 3D model carries the whole look
eq = lambda layer: js({"layers": {layer: [{"texture": f"{NS}:{NAME}"}]}})
files = {
    "pack.mcmeta": js({"pack": {"description": "Reforged Frost (full 3D armor)", "pack_format": 75,
                                "min_format": 75, "max_format": 9999,
                                "supported_formats": {"min_inclusive": 75, "max_inclusive": 9999}}}),
    f"assets/{NS}/items/{NAME}_head_piece.json": js({"model": {"type": "minecraft:model", "model": f"{NS}:item/{NAME}_helmet"}}),
    f"assets/{NS}/models/item/{NAME}_helmet.json": json.dumps(model, separators=(",", ":")).encode(),
    f"assets/{NS}/textures/item/{NAME}.png": png(atlas),
    f"assets/{NS}/equipment/{NAME}_chest_piece.json": eq("humanoid"),
    f"assets/{NS}/equipment/{NAME}_feet_piece.json": eq("humanoid"),
    f"assets/{NS}/equipment/{NAME}_legs_piece.json": eq("humanoid_leggings"),
    f"assets/{NS}/textures/entity/equipment/humanoid/{NAME}.png": png(blank),
    f"assets/{NS}/textures/entity/equipment/humanoid_leggings/{NAME}.png": png(blank),
}
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for k, v in files.items(): z.writestr(k, v)
ymin = min(e["from"][1] for e in elements); ymax = max(e["to"][1] for e in elements)
print("elements", len(elements), "y-range(unrotated)", ymin, ymax, os.path.getsize(OUT) // 1024, "KB")

# ---------------- software preview (orthographic, from the front) ----------------
def rot(r):
    ax, ay, az = map(math.radians, r)
    Rx = np.array([[1, 0, 0], [0, math.cos(ax), -math.sin(ax)], [0, math.sin(ax), math.cos(ax)]])
    Ry = np.array([[math.cos(ay), 0, math.sin(ay)], [0, 1, 0], [-math.sin(ay), 0, math.cos(ay)]])
    Rz = np.array([[math.cos(az), -math.sin(az), 0], [math.sin(az), math.cos(az), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx
tex = np.array(atlas).astype(float); em = np.array(emis).astype(float)
SC, W, H = 14, 560, 800
for view in ("front", "back"):
    img = np.zeros((H, W, 4)); zb = np.full((H, W), 1e9)
    for f, t, faces, r, o in prev:
        x1, y1, z1 = f; x2, y2, z2 = t; M = rot(r); o = np.array(o)
        quads = {"down": [(x1,y1,z2),(x1,y1,z1),(x2,y1,z1),(x2,y1,z2)], "up": [(x1,y2,z1),(x1,y2,z2),(x2,y2,z2),(x2,y2,z1)],
                 "north": [(x2,y2,z1),(x2,y1,z1),(x1,y1,z1),(x1,y2,z1)], "south": [(x1,y2,z2),(x1,y1,z2),(x2,y1,z2),(x2,y2,z2)],
                 "west": [(x1,y2,z1),(x1,y1,z1),(x1,y1,z2),(x1,y2,z2)], "east": [(x2,y2,z2),(x2,y1,z2),(x2,y1,z1),(x2,y2,z1)]}
        for n, fc in faces.items():
            if n not in quads: continue
            u1, v1, u2, v2 = fc["uv"]
            if u1 == u2 or v1 == v2: continue
            uvs = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
            k = (int(fc.get("rotation", 0)) // 90) % 4
            uvs = [uvs[(i + k) % 4] for i in range(4)]
            P = [(np.array(p) - o) @ M.T + o for p in quads[n]]
            nS, nT = 40, 40
            for a in np.linspace(0, 1, nS):
                for b in np.linspace(0, 1, nT):
                    # bilinear: a along TL->TR, b along TL->BL
                    TL, BL, BR, TR = P
                    p = TL * (1 - a) * (1 - b) + TR * a * (1 - b) + BL * (1 - a) * b + BR * a * b
                    uTL, uBL, uBR, uTR = uvs
                    uu = uTL[0]*(1-a)*(1-b)+uTR[0]*a*(1-b)+uBL[0]*(1-a)*b+uBR[0]*a*b
                    vv = uTL[1]*(1-a)*(1-b)+uTR[1]*a*(1-b)+uBL[1]*(1-a)*b+uBR[1]*a*b
                    px, py = int(min(max(uu, 0), 255.99)), int(min(max(vv, 0), 255.99))
                    c = tex[py, px]
                    if c[3] < 10: continue
                    X, Y, Z = p
                    zz = Z if view == "front" else -Z
                    sx = int(W / 2 + (-X if view == "front" else X) * SC); sy = int(H - 40 - Y * SC)
                    for dx in range(2):
                        for dy in range(2):
                            xx, yy = sx + dx, sy + dy
                            if 0 <= xx < W and 0 <= yy < H and zz < zb[yy, xx]:
                                zb[yy, xx] = zz; img[yy, xx] = c
    out = Image.fromarray(img.astype("uint8"), "RGBA")
    bg = Image.new("RGBA", (W, H), (40, 34, 58, 255)); bg.alpha_composite(out)
    bg.save(os.path.join(os.path.dirname(OUT), f"preview_{view}.png"))
