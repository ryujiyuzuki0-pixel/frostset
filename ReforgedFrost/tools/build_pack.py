#!/usr/bin/env python3
"""
Builds the Reforged Frost resource pack (Minecraft 1.21.11+) from the bundled .bbmodel.

The model is split per body part (head, torso, dust, arms, hips, legs, boots). Each part becomes its
own item model whose pivot sits at the model centre, so the plugin can show every part with an
ItemDisplay and animate it (limb swing, floating dust). Each piece (helmet/chest/legs/boots) also gets
an inventory icon model. The vanilla armor layers are blank so nothing is drawn twice.

Run:  python3 tools/build_pack.py   (needs Pillow)
Out:  src/main/resources/pack.zip
"""
import base64, io, json, math, os, zipfile
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "src/main/resources/models/reforged_frost_armor.bbmodel")
OUT = os.path.join(ROOT, "src/main/resources/pack.zip")
NS, NAME = "mythicarmor", "reforged_frost_armor"

d = json.load(open(MODEL))
dec = lambda t: Image.open(io.BytesIO(base64.b64decode(t["source"].split(",", 1)[1]))).convert("RGBA")
atlas = dec(d["textures"][0])
emis = dec(d["textures"][1])
ea = emis.getchannel("A").load()


def emissive(x1, y1, x2, y2):
    """True when most of the face is covered by the emissive texture (rendered fullbright)."""
    xa, xb = int(min(x1, x2)), int(math.ceil(max(x1, x2)))
    ya, yb = int(min(y1, y2)), int(math.ceil(max(y1, y2)))
    tot = hit = 0
    for y in range(ya, min(yb, emis.size[1])):
        for x in range(xa, min(xb, emis.size[0])):
            tot += 1
            hit += ea[x, y] > 0
    return tot > 0 and hit / tot >= 0.5


# ---------------- part layout: part id -> (bbmodel group, pivot in bbmodel px) ----------------
# pivot = the joint the part rotates around; it becomes the centre of the part's item model
PARTS = {
    "head":   ("helmet",     (0, 24, 0)),
    "torso":  ("torso",      (0, 24, 0)),
    "dust":   (None,         (0, 20, 0)),   # floating dust cubes, split out of the torso group
    "arm_r":  ("right_arm",  (5, 22, 0)),
    "arm_l":  ("left_arm",   (-5, 22, 0)),
    "hips":   ("hips",       (0, 12, 0)),
    "leg_r":  ("right_leg",  (2, 12, 0)),
    "leg_l":  ("left_leg",   (-2, 12, 0)),
    "boot_r": ("right_boot", (2, 12, 0)),
    "boot_l": ("left_boot",  (-2, 12, 0)),
}
PIECES = {
    "head_piece":  ["head"],
    "chest_piece": ["torso", "dust", "arm_r", "arm_l"],
    "legs_piece":  ["hips", "leg_r", "leg_l"],
    "feet_piece":  ["boot_r", "boot_l"],
}

# ---------------- element helpers ----------------
FLIPU = {"x": ("north", "south", "up", "down"), "z": ("east", "west")}
FLIPV = {"y": ("north", "south", "east", "west"), "z": ("up", "down")}
SWAP = {"x": ("east", "west"), "y": ("up", "down"), "z": ("north", "south")}


def norm(e):
    """(from, to, faces) with positive size; cubes stored mirrored get swapped faces/UVs."""
    f, t = list(map(float, e["from"])), list(map(float, e["to"]))
    faces = {k: dict(v) for k, v in e["faces"].items() if v and "uv" in v}
    for i, ax in enumerate("xyz"):
        if t[i] < f[i]:
            f[i], t[i] = t[i], f[i]
            for n in FLIPU.get(ax, ()):
                if n in faces:
                    u = faces[n]["uv"]; faces[n]["uv"] = [u[2], u[1], u[0], u[3]]
            for n in FLIPV.get(ax, ()):
                if n in faces:
                    u = faces[n]["uv"]; faces[n]["uv"] = [u[0], u[3], u[2], u[1]]
            a, b = SWAP[ax]
            fa, fb = faces.pop(a, None), faces.pop(b, None)
            if fb: faces[a] = fb
            if fa: faces[b] = fa
    inf = e.get("inflate", 0) or 0
    return [x - inf for x in f], [x + inf for x in t], faces


def convert(e, off):
    """bbmodel cube -> item-model element shifted by off (3-vector); None if nothing visible."""
    f, t, faces = norm(e)
    r = [float(x) for x in e.get("rotation", [0, 0, 0])]
    o = [float(x) for x in e.get("origin", [0, 0, 0])]
    el = {"from": [f[i] + off[i] for i in range(3)], "to": [t[i] + off[i] for i in range(3)], "faces": {}}
    if any(abs(a) > 1e-6 for a in r):
        el["rotation"] = {"x": r[0], "y": r[1], "z": r[2], "origin": [o[i] + off[i] for i in range(3)]}
    for n, fc in faces.items():
        x1, y1, x2, y2 = fc["uv"]
        if x1 == x2 or y1 == y2:
            continue
        out = {"uv": [x1 / 16, y1 / 16, x2 / 16, y2 / 16], "texture": "#0"}
        if fc.get("rotation"):
            out["rotation"] = int(fc["rotation"])
        if emissive(x1, y1, x2, y2):
            out["light_emission"] = 15
        el["faces"][n] = out
    return el if el["faces"] else None


# ---------------- assign every cube to a part via the outliner ----------------
els = {e["uuid"]: e for e in d["elements"]}
groups = {g["uuid"]: g["name"] for g in d["groups"]}
by_part = {p: [] for p in PARTS}
group_to_part = {g: p for p, (g, _) in PARTS.items() if g}


def walk(nodes, top):
    for n in nodes:
        if isinstance(n, dict):
            walk(n["children"], top or groups[n["uuid"]])
        else:
            e = els[n]
            if e.get("visibility", True) is False or e.get("export", True) is False:
                continue
            part = "dust" if e["name"].startswith("dust") else group_to_part.get(top)
            if part:
                by_part[part].append(e)


walk(d["outliner"], None)


def extents(elements):
    lo, hi = [1e9] * 3, [-1e9] * 3
    for el in elements:
        for i in range(3):
            lo[i] = min(lo[i], el["from"][i]); hi[i] = max(hi[i], el["to"][i])
    return lo, hi


TEX = {"0": f"{NS}:item/{NAME}", "particle": f"{NS}:item/{NAME}"}
part_models, icon_models = {}, {}
for pid, (_, pv) in PARTS.items():
    off = [8 - pv[i] for i in range(3)]
    elements = [x for x in (convert(e, off) for e in by_part[pid]) if x]
    lo, hi = extents(elements) if elements else ([8] * 3, [8] * 3)
    flag = "  <-- outside -16..32!" if min(lo) < -16 or max(hi) > 32 else ""
    print(f"{pid:7s} {len(elements):3d} elements  range {[round(v) for v in lo]} .. {[round(v) for v in hi]}{flag}")
    part_models[pid] = {"textures": TEX, "elements": elements}

for piece, pids in PIECES.items():
    raw = [e for p in pids for e in by_part[p]]
    tmp = [x for x in (convert(e, (0, 0, 0)) for e in raw) if x]
    lo, hi = extents(tmp)
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    off = [8 - centre[i] for i in range(3)]
    elements = [x for x in (convert(e, off) for e in raw) if x]
    dim = max(hi[i] - lo[i] for i in range(3))
    s = round(max(0.4, min(1.5, 14 / dim)), 3)
    icon_models[piece] = {
        "textures": TEX,
        "elements": elements,
        "display": {
            "gui": {"rotation": [20, 200, 0], "scale": [s, s, s]},
            "ground": {"scale": [s * 0.5] * 3},
            "fixed": {"rotation": [0, 180, 0], "scale": [s, s, s]},
            "thirdperson_righthand": {"scale": [s * 0.5] * 3},
            "thirdperson_lefthand": {"scale": [s * 0.5] * 3},
            "firstperson_righthand": {"scale": [s * 0.5] * 3},
            "firstperson_lefthand": {"scale": [s * 0.5] * 3},
        },
    }


def png(im):
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


js = lambda o: json.dumps(o, indent=1).encode()
mini = lambda o: json.dumps(o, separators=(",", ":")).encode()
blank = Image.new("RGBA", (64, 32), (0, 0, 0, 0))   # vanilla armor layers stay invisible; ItemDisplays draw the armor
item_def = lambda model: js({"model": {"type": "minecraft:model", "model": model}})
equip = lambda layer: js({"layers": {layer: [{"texture": f"{NS}:{NAME}"}]}})

files = {
    "pack.mcmeta": js({"pack": {"description": "Reforged Frost", "pack_format": 75,
                                "min_format": 75, "max_format": 9999,
                                "supported_formats": {"min_inclusive": 75, "max_inclusive": 9999}}}),
    f"assets/{NS}/textures/item/{NAME}.png": png(atlas),
    f"assets/{NS}/textures/entity/equipment/humanoid/{NAME}.png": png(blank),
    f"assets/{NS}/textures/entity/equipment/humanoid_leggings/{NAME}.png": png(blank),
}
for pid, m in part_models.items():
    files[f"assets/{NS}/models/item/{NAME}_part_{pid}.json"] = mini(m)
    files[f"assets/{NS}/items/{NAME}_part_{pid}.json"] = item_def(f"{NS}:item/{NAME}_part_{pid}")
for piece, m in icon_models.items():
    files[f"assets/{NS}/models/item/{NAME}_{piece}_icon.json"] = mini(m)
    files[f"assets/{NS}/items/{NAME}_{piece}.json"] = item_def(f"{NS}:item/{NAME}_{piece}_icon")
    files[f"assets/{NS}/equipment/{NAME}_{piece}.json"] = equip("humanoid_leggings" if piece == "legs_piece" else "humanoid")

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for k, v in files.items():
        z.writestr(k, v)
print("->", OUT, os.path.getsize(OUT) // 1024, "KB,", len(files), "files")
