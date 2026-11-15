"""Dedicated royal-knight asset contract; run with python3 -m unittest discover -s tools -p test_knight_assets.py."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "resourcepack/assets/amplified_items"


class KnightAssetsTests(unittest.TestCase):
    def test_item_definition_resolves_dedicated_model_and_texture(self):
        definition = ASSETS / "items/royal_knight.json"
        self.assertTrue(definition.is_file(), "Royal knight item definition has not been generated")
        item = json.loads(definition.read_text())
        self.assertEqual(item["model"], {"type": "minecraft:model", "model": "amplified_items:item/royal_knight"})
        model = json.loads((ASSETS / "models/item/royal_knight.json").read_text())
        self.assertEqual(model["textures"]["knight"], "amplified_items:entity/royal_knight")
        self.assertTrue((ASSETS / "textures/entity/royal_knight.png").is_file())
        self.assertGreater(len(model["elements"]), 20, "Knight must be a sculpted model, not a flat sprite")

    def test_mesh_bounds_uvs_and_equipment(self):
        model = json.loads((ASSETS / "models/item/royal_knight.json").read_text())
        elements = model["elements"]
        for element in elements:
            for low, high in zip(element["from"], element["to"], strict=True):
                self.assertGreater(high, low)
                self.assertGreaterEqual(low, -16)
                self.assertLessEqual(high, 32)
            self.assertEqual(set(element["faces"]), {"north", "south", "east", "west", "up", "down"})
            for face in element["faces"].values():
                self.assertEqual(face["texture"], "#knight")
                self.assertTrue(all(0 <= value <= 16 for value in face["uv"]))
                self.assertLess(face["uv"][0], face["uv"][2])
                self.assertLess(face["uv"][1], face["uv"][3])
        height = (max(e["to"][1] for e in elements) - min(e["from"][1] for e in elements)) / 16 * 1.35
        width = (max(e["to"][0] for e in elements) - min(e["from"][0] for e in elements)) / 16 * 1.35
        self.assertAlmostEqual(height, 2.7)
        self.assertTrue(1.3 <= width <= 1.4, width)
        names = " ".join(e["name"] for e in elements)
        for required in ("crown", "great helm", "tabard", "shield", "sword blade", "greave", "pauldron"):
            self.assertIn(required, names)
        self.assertNotIn("parent", model, "Handheld parent transforms must not shrink the knight")
        self.assertEqual(model["display"]["none"]["scale"], [1, 1, 1])

    def test_texture_is_original_opaque_atlas_with_legible_heraldry_and_visor(self):
        with Image.open(ASSETS / "textures/entity/royal_knight.png") as image:
            self.assertEqual(image.size, (128, 128))
            image = image.convert("RGBA")
            self.assertEqual(image.getchannel("A").getextrema(), (255, 255))
            visor = image.getpixel((6 * 16 + 2, 5))
            heraldry = image.getpixel((7 * 16 + 8, 4))
            assert isinstance(visor, tuple) and isinstance(heraldry, tuple)
            self.assertEqual(visor[:3], (15, 24, 34), "Horizontal visor slit")
            self.assertEqual(heraldry[:3], (255, 218, 111), "Crown heraldry")
            colors = image.getcolors(10000)
            assert colors is not None
            self.assertGreater(len(colors), 60, "Hand-pixelled steel and cloth need distinct shading")

    def test_generator_reproduces_committed_bytes_and_never_overrides_vanilla(self):
        spec = importlib.util.spec_from_file_location("knight_generator", ROOT / "tools/generate_knight_assets.py")
        assert spec is not None and spec.loader is not None
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as folder:
            output = Path(folder) / "assets/amplified_items"
            generator.generate(output)
            expected = {"items/royal_knight.json", "models/item/royal_knight.json", "textures/entity/royal_knight.png"}
            actual = {p.relative_to(output).as_posix() for p in output.rglob("*") if p.is_file()}
            self.assertEqual(actual, expected)
            for name in expected:
                self.assertEqual((output / name).read_bytes(), (ASSETS / name).read_bytes(), name)
            self.assertFalse((Path(folder) / "assets/minecraft").exists())
        self.assertFalse((ROOT / "resourcepack/assets/minecraft/textures/entity/iron_golem").exists())


if __name__ == "__main__":
    unittest.main()
