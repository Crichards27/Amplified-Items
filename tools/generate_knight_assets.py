#!/usr/bin/env python3
"""Generate the knight model, texture atlas and optional preview with Pillow.

Model height is 32 pixels around (8, 8, 8), so a display scale of 1.35
makes a 2.7-block knight. Its north face is forward: vanilla item displays
apply their own 180-degree Y rotation. No minecraft namespace is modified.
Use --preview to render the textured mesh.
"""
import argparse
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "resourcepack/assets/amplified_items"
SIZE = 128
TILE = 16
MATERIALS = {
    "steel": (145, 167, 187), "edge": (211, 225, 235),
    "dark": (52, 66, 83), "gold": (218, 169, 58),
    "purple": (88, 43, 139), "chain": (76, 88, 106),
    "visor": (119, 144, 163), "tabard": (88, 43, 139),
    "shield": (92, 46, 151), "gem": (78, 209, 221),
    "leather": (61, 40, 44),
}


def texture():
    image = Image.new("RGB", (SIZE, SIZE), (30, 36, 47))
    draw = ImageDraw.Draw(image)
    for index, (name, color) in enumerate(MATERIALS.items()):
        ox, oy = (index % 8) * TILE, (index // 8) * TILE
        def rect(box, fill):
            draw.rectangle(tuple(v + (ox if i % 2 == 0 else oy) for i, v in enumerate(box)), fill=fill)
        def pixel(x, y, fill):
            draw.point((ox + x, oy + y), fill=fill)
        for y in range(16):
            for x in range(16):
                # Hammer marks and a left-to-right bevel.
                delta = (10 if x < 3 else -13 if x > 12 else 0) + ((x * 7 + y * 3) % 7 - 3)
                pixel(x, y, tuple(max(0, min(255, c + delta)) for c in color))
        outline = tuple(max(0, c - 45) for c in color)
        bright = tuple(min(255, c + 40) for c in color)
        rect((0, 0, 15, 0), bright)
        rect((0, 1, 0, 14), bright)
        rect((1, 15, 15, 15), outline)
        rect((15, 1, 15, 14), outline)
        if name in {"steel", "dark", "gold"}:
            for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
                pixel(x, y, outline)
                pixel(x, y - 1, bright)
        if name == "chain":
            for y in range(2, 15, 3):
                for x in range(1 + y % 2, 15, 3):
                    pixel(x, y, (160, 175, 183))
                    pixel(x + 1, y + 1, (31, 39, 53))
        if name == "visor":
            rect((1, 4, 14, 6), (15, 24, 34))
            rect((3, 5, 5, 5), (95, 187, 201))
            rect((10, 5, 12, 5), (95, 187, 201))
            rect((7, 2, 8, 13), (219, 174, 65))
            for x in (3, 5, 10, 12):
                rect((x, 10, x, 12), (31, 43, 58))
        if name in {"tabard", "shield"}:
            gold, light = (224, 171, 53), (255, 218, 111)
            rect((1, 0, 2, 15), gold)
            rect((13, 0, 14, 15), gold)
            rect((2, 14, 13, 15), gold)
            # Crown-over-sword heraldry.
            for x in (5, 8, 11):
                rect((x, 3, x, 6), light)
            rect((5, 6, 11, 7), gold)
            rect((7, 8, 8, 12), light)
            rect((5, 9, 10, 9), gold)
        if name == "gem":
            rect((3, 3, 12, 12), (40, 143, 181))
            rect((4, 4, 9, 7), (161, 255, 247))
            rect((9, 9, 12, 12), (31, 93, 142))
    return image


def uv(material):
    i = list(MATERIALS).index(material)
    x, y = (i % 8) * 2, (i // 8) * 2
    return [x, y, x + 2, y + 2]


def mesh():
    elements = []
    def box(name, start, end, material="steel", front=None):
        faces = {face: {"texture": "#knight", "uv": uv(front if face == "north" and front else material)}
                 for face in ("down", "up", "north", "south", "west", "east")}
        elements.append({"name": name, "from": start, "to": end, "faces": faces})
    # Boots and greaves. Feet are at model Y=-8; crown tips end at Y=24.
    for side, x in (("right", 4.1), ("left", 8.7)):
        box(side + " boot", [x, -8, 4.4], [x + 3.2, -5.8, 10.5], "dark")
        box(side + " toe cap", [x - .1, -7.8, 4.1], [x + 3.3, -6.5, 6], "edge")
        box(side + " greave", [x + .1, -5.8, 5.5], [x + 3.1, -.7, 9.7])
        box(side + " knee", [x - .1, -1.5, 4.9], [x + 3.3, .5, 9.7], "edge")
        box(side + " thigh", [x + .2, .5, 5.7], [x + 3, 5, 9.5], "chain")
    box("chainmail hauberk", [3.7, 3.5, 5.3], [12.3, 15, 10.7], "chain")
    box("breastplate", [3.5, 8, 4.8], [12.5, 16, 11.2], "steel", "tabard")
    box("gorget", [5, 15.2, 5.1], [11, 17, 10.9], "edge")
    box("royal tabard front", [5, 1, 4.5], [11, 8.1, 5.1], "purple", "tabard")
    box("royal tabard back", [5, 1, 10.8], [11, 11, 11.3], "purple")
    box("gold waist belt", [3.4, 6.5, 4.6], [12.6, 7.6, 11.3], "gold")
    box("belt clasp", [7, 6.3, 4.25], [9, 7.8, 4.65], "gem")
    for side, x in (("right", .5), ("left", 12.4)):
        box(side + " upper arm", [x + .3, 8, 5.9], [x + 2.8, 14, 10.1], "chain")
        box(side + " pauldron", [x, 12.5, 5.1], [x + 3.1, 16.2, 10.9])
        box(side + " shoulder trim", [x - .1, 12.4, 5], [x + 3.2, 13.2, 11], "gold")
        box(side + " vambrace", [x + .15, 4, 5.8], [x + 2.95, 9, 10.2], "steel")
        box(side + " gauntlet", [x + .1, 2.5, 5.6], [x + 3, 5.1, 10.3], "dark")
    box("great helm", [4.3, 16.5, 4.9], [11.7, 22.5, 11.1], "steel", "visor")
    box("helmet ridge", [7.6, 17, 4.65], [8.4, 22.8, 11.3], "edge")
    box("crown band", [4, 21.6, 4.6], [12, 22.6, 11.4], "gold")
    for x in (4, 7.4, 10.8):
        box("crown front tooth", [x, 22.6, 4.6], [x + 1.2, 24, 5.6], "gold")
        box("crown back tooth", [x, 22.6, 10.4], [x + 1.2, 24, 11.4], "gold")
    box("crown left tooth", [4, 22.6, 7.5], [5, 24, 8.7], "gold")
    box("crown right tooth", [11, 22.6, 7.5], [12, 24, 8.7], "gold")
    box("crown jewel", [7.5, 21.7, 4.25], [8.5, 22.5, 4.6], "gem")
    # Shield hangs from the left forearm, on the north/forward side.
    box("shield upper rim", [11.7, 2.2, 3.5], [16.25, 10.8, 4.7], "gold")
    box("shield face", [12.1, 2.6, 3.25], [15.85, 10.4, 3.6], "purple", "shield")
    box("shield lower taper", [12.3, .7, 3.5], [15.65, 2.2, 4.7], "gold")
    box("shield point", [13.1, -.6, 3.5], [14.85, .7, 4.7], "gold")
    box("shield boss", [13.4, 5.7, 2.95], [14.6, 7.1, 3.3], "gem")
    # Point-down longsword on the opposite side, distinct from the shield.
    box("sword grip", [.8, 2, 4.2], [1.8, 5.7, 5.2], "leather")
    box("sword pommel", [.65, 5.5, 4.05], [1.95, 6.5, 5.35], "gold")
    box("sword crossguard", [-.25, 1.8, 3.9], [2.85, 2.6, 5.5], "gold")
    box("sword blade", [.65, -5.6, 4.25], [1.95, 1.8, 5.15], "edge")
    box("sword fuller", [1.15, -5.5, 4.15], [1.45, 1.7, 4.3], "steel")
    box("sword tip", [.95, -6.7, 4.35], [1.65, -5.6, 5.05], "edge")
    return {"credit": "Amplified Items - original royal knight; generated by tools/generate_knight_assets.py",
            "ambientocclusion": False, "gui_light": "front",
            "textures": {"knight": "amplified_items:entity/royal_knight", "particle": "amplified_items:entity/royal_knight"},
            "display": {"none": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}},
            "elements": elements}


def render_preview(model, atlas, path):
    """Render an orthographic preview from the model's cuboids and UVs."""
    canvas = Image.new("RGB", (1100, 780), (20, 26, 39))
    draw = ImageDraw.Draw(canvas)
    draw.text((35, 24), "ROYAL KNIGHT  |  VANILLA ITEM-DISPLAY MODEL", fill=(233, 221, 181))
    draw.text((35, 47), "Steel plate / crown visor / royal purple & gold / shield + longsword", fill=(151, 167, 191))
    vertices = {
        "north": (3, 2, 1, 0), "south": (6, 7, 4, 5),
        "west": (7, 3, 0, 4), "east": (2, 6, 5, 1),
        "up": (7, 6, 2, 3), "down": (0, 1, 5, 4),
    }
    for origin_x, angle, label in ((285, -.47, "FRONT / THREE-QUARTER"), (815, 2.55, "REAR / THREE-QUARTER")):
        ca, sa = math.cos(angle), math.sin(angle)
        def project(p):
            x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
            rx, rz = x * ca + z * sa, -x * sa + z * ca
            return (origin_x + rx * 17, 420 - (y * .96 + rz * .25) * 17, rz)
        faces = []
        for element in model["elements"]:
            x0, y0, z0 = element["from"]
            x1, y1, z1 = element["to"]
            points = [(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),
                      (x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]
            for face, indices in vertices.items():
                p = [project(points[i]) for i in indices]
                # Far-to-near painter order. All cuboid faces are included.
                faces.append((sum(v[2] for v in p) / 4, face, p, element["faces"][face]["uv"]))
        for _, face, p, coords in sorted(faces, reverse=True, key=lambda f: f[0]):
            # Rasterize a 16x16 atlas tile as affine pixel parallelograms.
            u0,v0,u1,v1 = [round(v * SIZE / 16) for v in coords]
            shade = {"north":1, "south":.83, "east":.73, "west":.88, "up":1.12, "down":.56}[face]
            for v in range(v1-v0):
                for u in range(u1-u0):
                    def at(a,b):
                        return (p[0][0]+(p[1][0]-p[0][0])*a+(p[3][0]-p[0][0])*b,
                                p[0][1]+(p[1][1]-p[0][1])*a+(p[3][1]-p[0][1])*b)
                    a,b = u/(u1-u0), v/(v1-v0)
                    a1,b1 = (u+1)/(u1-u0), (v+1)/(v1-v0)
                    color = tuple(min(255, round(c*shade)) for c in atlas.getpixel((u0+u,v0+v)))
                    draw.polygon([at(a,b),at(a1,b),at(a1,b1),at(a,b1)], fill=color)
        draw.text((origin_x-115, 731), label, fill=(219, 203, 154))
    draw.text((35, 758), "Mesh preview (not an in-game screenshot) - height 2.70 blocks at scale 1.35", fill=(151, 167, 191))
    path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path)


def generate(output=ASSETS, preview=None):
    model, atlas = mesh(), texture()
    files = {"items/royal_knight.json": {"model": {"type": "minecraft:model", "model": "amplified_items:item/royal_knight"}},
             "models/item/royal_knight.json": model}
    for name, data in files.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")
    path = output / "textures/entity/royal_knight.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(path, optimize=False, compress_level=9)
    if preview:
        render_preview(model, atlas, preview)
    print(f"Generated {len(model['elements'])} knight cuboids; {SIZE}x{SIZE} texture; 3 dedicated assets")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ASSETS)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    generate(args.output, args.preview)
