"""Build the 16×16 weapon art from a local Minecraft 26.3 client JAR (requires Pillow)."""
import argparse
from io import BytesIO
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image


ASSETS = Path(__file__).resolve().parents[1] / "resourcepack/assets/amplified_items"
TEXTURES = ASSETS / "textures/item"

WOOD = [(40, 30, 11), (73, 54, 21), (104, 78, 30), (137, 103, 39)]
SWORD = [
    (8, 37, 32), (14, 63, 54), (21, 99, 85), (30, 138, 119),
    (43, 199, 172), (51, 235, 203), (164, 253, 240),
]
CROSSBOW_WOOD = [
    (31, 18, 6), (42, 26, 9), (54, 32, 11), (59, 36, 13), (72, 43, 16),
    (77, 46, 17), (109, 66, 24), (144, 88, 31), (181, 115, 47),
]
CROSSBOW_STEEL = [(68, 68, 68), (109, 109, 109), (150, 150, 150), (151, 151, 151)]


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def rgb(value):
    return tuple(bytes.fromhex(value))


def colors(source, values):
    return dict(zip(source, (rgb(value) for value in values.split()), strict=True))


def vanilla_sprite(client, name):
    path = f"assets/minecraft/textures/item/{name}.png"
    return Image.open(BytesIO(client.read(path))).convert("RGBA")


def retexture(image, palette):
    recolored = Image.new("RGBA", image.size)
    recolored.putdata([
        (*palette.get(pixel[:3], pixel[:3]), pixel[3]) if pixel[3] else (0, 0, 0, 0)
        for pixel in image.getdata()
    ])
    return recolored


def detail(image, left, top, rows, palette):
    for y, row in enumerate(rows, top):
        for x, pixel in enumerate(row, left):
            if pixel != ".":
                image.putpixel((x, y), (*rgb(palette[pixel]), 255))


def write_sprite(name, image, parent="minecraft:item/handheld"):
    assert image.size == (16, 16) and image.getchannel("A").getextrema() == (0, 255)
    image.save(TEXTURES / f"{name}.png", optimize=True)
    write_json(ASSETS / f"models/item/{name}.json", {
        "parent": parent,
        "textures": {"layer0": f"amplified_items:item/{name}"},
    })


def model_ref(name):
    return {"type": "minecraft:model", "model": f"amplified_items:item/{name}"}


def write_definition(name, model):
    write_json(ASSETS / f"items/{name}.json", {"model": model})


def sword(client, name, blade, handle, accents):
    palette = colors(SWORD, blade) | colors(WOOD, handle)
    image = retexture(vanilla_sprite(client, "diamond_sword"), palette)
    for (x, y), shade in accents.items():
        assert image.getpixel((x, y))[3] == 255
        image.putpixel((x, y), (*rgb(shade), 255))
    write_sprite(name, image)
    write_definition(name, model_ref(name))


def ranged_weapon(client, base, name, sprites, palette):
    references = {}
    for suffix, texture in sprites.items():
        variant = name + suffix
        references[f"minecraft:item/{base}{suffix}"] = f"amplified_items:item/{variant}"
        write_sprite(variant, retexture(vanilla_sprite(client, texture), palette), f"minecraft:item/{base}")

    def remap(node):
        if isinstance(node, dict):
            return {key: remap(value) for key, value in node.items()}
        if isinstance(node, list):
            return [remap(value) for value in node]
        return references.get(node, node)

    vanilla = json.loads(client.read(f"assets/minecraft/items/{base}.json"))
    write_definition(name, remap(vanilla["model"]))


def generate(client):
    TEXTURES.mkdir(parents=True, exist_ok=True)

    staff = retexture(vanilla_sprite(client, "stick"), colors(WOOD, "171324 2d2344 564270 7b639b"))
    detail(staff, 9, 0, [
        ".g.g.g.",
        "gggYggg",
        "gYgQgYg",
        "gYpQpYg",
        ".gpPg.",
        "..gYg..",
        ".gYg...",
    ], {"g": "80531d", "Y": "ffd35c", "Q": "efadff", "p": "a05bdd", "P": "5e2e8e"})
    detail(staff, 5, 9, [".gY", "gYg", "Yg."], {"g": "895b28", "Y": "f4c369"})
    detail(staff, 2, 13, [".Yg", "gY.", "gg."], {"g": "774820", "Y": "e6b451"})
    write_sprite("kings_decree", staff)
    write_definition("kings_decree", model_ref("kings_decree"))

    scythe = retexture(vanilla_sprite(client, "stick"), colors(WOOD, "1b1522 35233e 664775 9868a5"))
    # Extend the curve past the handle to form a hooked blade.
    detail(scythe, 5, 0, [
        "...ooooooo.",
        "..opHppppoo",
        ".opHppssSso",
        "opHpssoosSo",
        "ooosoo..oSo",
        "..oo....oSo",
        "........oHo",
        "........Ho.",
        ".......oo..",
    ], {"o": "261831", "p": "8652a0", "H": "e9b9f9", "s": "51405f", "S": "b187ca"})
    detail(scythe, 4, 10, ["..H", ".po", "po."], {"o": "362447", "p": "814bb0", "H": "d09af1"})
    write_sprite("reapers_edge", scythe)
    write_definition("reapers_edge", model_ref("reapers_edge"))

    sword(client, "nightblade", "110e24 211c43 3b3069 59498c 544785 9484d5 c4baff",
          "171329 34254b 553867 9765ac", {(10, 5): "bc77f6", (9, 6): "e0a6ff", (5, 10): "9b66e9"})
    sword(client, "solarblade", "712410 9a380e b85017 da691b f5a52d ffd358 fff1a1",
          "441d17 823324 b45426 f3932c", {(11, 4): "fff3a7", (10, 5): "ffe16b", (5, 10): "fff2a0"})
    sword(client, "spiders_bane", "19261c 2f4231 4f673f 718e56 7fa35d c7dcb1 e9f0df",
          "242826 444d3d 657350 99a889", {(10, 5): "a2d645", (9, 6): "cbef74", (5, 10): "d1f478"})
    axe = retexture(vanilla_sprite(client, "netherite_axe"), colors(
        [(35, 16, 18), (47, 33, 34), (74, 41, 64), (79, 60, 62), (96, 52, 50), (115, 69, 67),
         (50, 39, 39), (59, 57, 59), (67, 64, 67), (93, 86, 93), (112, 103, 112), (134, 123, 134)],
        "310c19 48212d 591229 812238 792b39 aa4050 591229 9a283e a53045 c54058 ea7381 ffb6ad",
    ))
    write_sprite("bloodfang", axe)
    write_definition("bloodfang", model_ref("bloodfang"))

    trident = retexture(vanilla_sprite(client, "trident"), colors(
        [(22, 71, 57), (46, 94, 80), (64, 121, 105), (88, 157, 142),
         (107, 107, 107), (150, 150, 150), (216, 216, 216), (255, 255, 255)],
        "16334c 1b5470 2e7992 48adb5 267484 42b9bb 81e9df c4fff0",
    ))
    detail(trident, 4, 10, [".g", "g."], {"g": "a7e9db"})
    write_sprite("leviathans_wrath", trident)
    write_definition("leviathans_wrath", model_ref("leviathans_wrath"))

    bow = {"": "bow", **{f"_pulling_{i}": f"bow_pulling_{i}" for i in range(3)}}
    wind = colors(WOOD, "18364b 32718b 72bec9 b7eee8") | colors(
        [(68, 68, 68), (107, 107, 107), (150, 150, 150)], "537084 7b9eab e1f3f0",
    )
    ranged_weapon(client, "bow", "windwalker", bow, wind)

    crossbow = {
        "": "crossbow_standby",
        **{f"_pulling_{i}": f"crossbow_pulling_{i}" for i in range(3)},
        "_arrow": "crossbow_arrow",
        "_firework": "crossbow_firework",
    }
    venom = colors(CROSSBOW_WOOD, "172217 223522 34462f 3c512d 4d652b 476438 638a3e 8fc64e c6f27d")
    venom |= colors(CROSSBOW_STEEL, "3c5142 697c55 9db177 bbdb8c")
    solar = colors(CROSSBOW_WOOD, "38161a 552122 723024 863a26 9b4527 b25124 d47329 fba338 ffd671")
    solar |= colors(CROSSBOW_STEEL, "583d3b 8e6250 b99877 e9c9a1")
    ranged_weapon(client, "crossbow", "venombolt", crossbow, venom)
    ranged_weapon(client, "crossbow", "sunpiercer", crossbow, solar)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client_jar", type=Path, help="path to the Minecraft 26.3 client JAR")
    args = parser.parse_args()
    with ZipFile(args.client_jar) as client:
        generate(client)
