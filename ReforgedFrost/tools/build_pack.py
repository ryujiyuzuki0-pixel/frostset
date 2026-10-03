#!/usr/bin/env python3
"""
Builds a standalone vanilla resource pack (no MythicArmors needed) from the bundled .bbmodel.

  helmet     -> 3D item model rendered on the head (horns, spikes, etc. that are un-rotated cubes)
  chest/legs/boots -> flat vanilla armor layers re-laid-out from the model's base cubes

Run:  python3 tools/build_pack.py   (needs Pillow)
Out:  src/main/resources/pack.zip
"""
import base64, io, json, os, zipfile
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "src/main/resources/models/reforged_frost_armor.bbmodel")
OUT = os.path.join(ROOT, "src/main/resources/pack.zip")
NS = "mythicarmor"          # same ids the plugin's items already use
NAME = "reforged_frost_armor"
S = 2                        # armor texture scale (64x32 * S)

d = json.load(open(MODEL))
atlas = Image.open(io.BytesIO(base64.b64decode(d["textures"][0]["source"].split(",", 1)[1]))).convert("RGBA")
els = [e for e in d["elements"] if e.get("type", "cube") == "cube"]


def size(e):
    return [round(e["to"][i] - e["from"][i], 4) for i in range(3)]


def find(frm, to, infl):
    for e in els:
        if e["from"] == frm and e["to"] == to and abs(e.get("inflate", 0) - infl) < 1e-4:
            return e
    raise SystemExit(f"element not found: {frm} {to} {infl}")


def face_img(face, name):
    x1, y1, x2, y2 = face["uv"]
    img = atlas.crop((int(min(x1, x2)), int(min(y1, y2)), int(max(x1, x2)), int(max(y1, y2))))
    std = name in ("up", "down")  # box-UV stores these reversed on both axes
    if (x1 > x2) != std:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    if (y1 > y2) != std:
        img = img.transpose(Image.FLIP_TOP_BOTTOM)
    rot = face.get("rotation", 0)
    if rot:
        img = img.rotate(-rot, expand=True)
    return img


def box_rects(u, v, w, h, d_):
    return {
        "east": (u, v + d_, d_, h), "north": (u + d_, v + d_, w, h),
        "west": (u + d_ + w, v + d_, d_, h), "south": (u + 2 * d_ + w, v + d_, w, h),
        "up": (u + d_, v, w, d_), "down": (u + d_ + w, v, w, d_),
    }


def paste_cube(tex, e, u, v, w, h, d_, only=None, rows=None):
    """Composite cube e's six faces onto tex using vanilla box layout at (u,v).
    rows=(y0,y1) limits side faces to a vertical slice of the part (in part pixels, 0=top)."""
    layer = Image.new("RGBA", tex.size, (0, 0, 0, 0))
    for name, (rx, ry, rw, rh) in box_rects(u, v, w, h, d_).items():
        if only and name not in only:
            continue
        f = e["faces"].get(name)
        if not f or "uv" not in f:
            continue
        img = face_img(f, name)
        if rows and name in ("east", "north", "west", "south"):
            y0, y1 = rows
            # source slice proportional to rows
            sh = img.size[1]
            cy0, cy1 = round(sh * y0 / h), round(sh * y1 / h)
            img = img.crop((0, cy0, img.size[0], max(cy1, cy0 + 1)))
            ry, rh = ry + y0, y1 - y0
        img = img.resize((rw * S, rh * S), Image.NEAREST)
        layer.paste(img, (rx * S, ry * S))
    tex.alpha_composite(layer)


def new_tex():
    return Image.new("RGBA", (64 * S, 32 * S), (0, 0, 0, 0))


# ---------- humanoid texture: chest (body + arms) and boots (lower legs) ----------
humanoid = new_tex()
for infl in (0.275, 0.575):                                   # chest body, inner then outer
    paste_cube(humanoid, find([-4, 12, -2], [4, 24, 2], infl), 16, 16, 8, 12, 4)
paste_cube(humanoid, find([4, 12, -2], [8, 24, 2], 0.5), 40, 16, 4, 12, 4)          # arm
paste_cube(humanoid, find([4, 11.5, -2], [8, 23.5, 2], 0.255), 40, 16, 4, 12, 4)    # gauntlet
boot = find([0, 0, -2], [4, 4, 2], 0.55)
paste_cube(humanoid, boot, 0, 16, 4, 12, 4, only=("east", "north", "west", "south", "down"), rows=(8, 12))

# ---------- leggings texture: belt + legs ----------
leggings = new_tex()
paste_cube(leggings, find([-4, 12, -2], [4, 24, 2], 0.2555), 16, 16, 8, 12, 4,
           only=("east", "north", "west", "south", "down"), rows=(8, 12))
leg = [e for e in els if e["from"][0] == 0 and e["from"][1] == 0.5 and size(e) == [4, 14, 4]][0]
paste_cube(leggings, leg, 0, 16, 4, 12, 4)

# ---------- helmet 3D item model ----------
OX, OY, OZ = 8, -20, 8   # bbmodel (head centre y=28) -> item model (centre 8,8,8)
helm = []
for e in els:
    if e.get("rotation") and any(abs(a) > 1e-6 for a in e["rotation"]):
        continue
    s = size(e)
    if any(v < 0 for v in s):
        continue
    name = e["name"]
    in_helm = (name.startswith(("horn_", "pillar_", "needle_")) or name == "horn_base_bar"
               or (e["from"] == [-4, 24, -4] and e["to"] == [4, 32, 4]))
    if not in_helm:
        continue
    i = e.get("inflate", 0)
    el = {
        "from": [e["from"][0] - i + OX, e["from"][1] - i + OY, e["from"][2] - i + OZ],
        "to": [e["to"][0] + i + OX, e["to"][1] + i + OY, e["to"][2] + i + OZ],
        "faces": {},
    }
    for fname, f in e["faces"].items():
        if "uv" not in f:
            continue
        x1, y1, x2, y2 = f["uv"]
        if x1 == x2 or y1 == y2:
            continue
        face = {"uv": [x1 / 16, y1 / 16, x2 / 16, y2 / 16], "texture": "#0"}
        if f.get("rotation"):
            face["rotation"] = int(f["rotation"])
        el["faces"][fname] = face
    if el["faces"]:
        helm.append(el)

helm_model = {
    "textures": {"0": f"{NS}:item/{NAME}", "particle": f"{NS}:item/{NAME}"},
    "elements": helm,
    "display": {
        "head": {"scale": [1.6, 1.6, 1.6]},
        "gui": {"rotation": [25, -35, 0], "translation": [0, -3.5, 0], "scale": [0.5, 0.5, 0.5]},
        "ground": {"scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, -2, 0], "scale": [0.5, 0.5, 0.5]},
        "thirdperson_righthand": {"translation": [0, 1, 0], "scale": [0.4, 0.4, 0.4]},
        "thirdperson_lefthand": {"translation": [0, 1, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_righthand": {"translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
    },
}


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()


def js(o):
    return json.dumps(o, indent=1).encode()


eq = lambda layer: js({"layers": {layer: [{"texture": f"{NS}:{NAME}"}]}})
files = {
    "pack.mcmeta": js({"pack": {
        "description": "Reforged Frost",
        "pack_format": 46,
        "min_format": 46, "max_format": 9999,
        "supported_formats": {"min_inclusive": 46, "max_inclusive": 9999},
    }}),
    f"assets/{NS}/items/{NAME}_head_piece.json": js({"model": {"type": "minecraft:model", "model": f"{NS}:item/{NAME}_helmet"}}),
    f"assets/{NS}/models/item/{NAME}_helmet.json": js(helm_model),
    f"assets/{NS}/textures/item/{NAME}.png": png(atlas),
    f"assets/{NS}/equipment/{NAME}_chest_piece.json": eq("humanoid"),
    f"assets/{NS}/equipment/{NAME}_feet_piece.json": eq("humanoid"),
    f"assets/{NS}/equipment/{NAME}_legs_piece.json": eq("humanoid_leggings"),
    f"assets/{NS}/textures/entity/equipment/humanoid/{NAME}.png": png(humanoid),
    f"assets/{NS}/textures/entity/equipment/humanoid_leggings/{NAME}.png": png(leggings),
}
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for k, v in files.items():
        z.writestr(k, v)
print("helmet elements:", len(helm), "->", OUT, os.path.getsize(OUT) // 1024, "KB")

# optional previews: PREVIEW_DIR=/some/dir python3 tools/build_pack.py
prev = os.environ.get("PREVIEW_DIR")
if prev:
    for nm, im in (("humanoid", humanoid), ("leggings", leggings)):
        bg = Image.new("RGBA", im.size, (60, 60, 80, 255)); bg.alpha_composite(im)
        bg.resize((im.size[0] * 5, im.size[1] * 5), Image.NEAREST).save(os.path.join(prev, f"prev_{nm}.png"))
