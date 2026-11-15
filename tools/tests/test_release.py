"""Release invariants; all temporary outputs stay under the repository build tree."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class ReleaseChecks(unittest.TestCase):
    def test_crown_cannot_bypass_one_time_altar_through_crafting(self):
        data = ROOT / 'src/main/resources/data/amplified_items'
        self.assertFalse((data / 'recipe/kings_crown.json').exists(),
                         'Cheap crown recipe bypasses expensive one-time altar claim')
        self.assertFalse((data / 'advancement/recipes/kings_crown.json').exists(),
                         'Removed crown recipe must not remain in recipe-unlock rewards')

    def load_packager(self):
        path = ROOT / 'tools/package_release.py'
        self.assertTrue(path.is_file(), 'Release packager must exist')
        spec = importlib.util.spec_from_file_location('package_release', path)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_datapack_has_root_metadata_and_exact_recipe_data(self):
        packager = self.load_packager()
        (ROOT / 'build').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build') as temporary:
            target = Path(temporary) / 'data.zip'
            packager.build_datapack(ROOT, target)
            with zipfile.ZipFile(target) as archive:
                names = set(archive.namelist())
                self.assertIn('pack.mcmeta', names)
                self.assertIn('data/amplified_items/recipe/sunlight.json', names)
                self.assertFalse(any('kings_crown' in name for name in names))
                self.assertFalse(any(name.endswith('.class') for name in names))
                self.assertEqual(json.loads(archive.read('data/amplified_items/recipe/sunlight.json')),
                                 json.loads((ROOT / 'src/main/resources/data/amplified_items/recipe/sunlight.json').read_text()))
                self.assertEqual(json.loads(archive.read('pack.mcmeta'))['pack']['min_format'], [121, 0])

    def test_zip_bytes_are_reproducible(self):
        packager = self.load_packager()
        (ROOT / 'build').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build') as temporary:
            first, second = Path(temporary) / 'first.zip', Path(temporary) / 'second.zip'
            packager.write_zip(first, {'b.txt': b'two', 'a.txt': b'one'})
            packager.write_zip(second, {'a.txt': b'one', 'b.txt': b'two'})
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.namelist(), ['a.txt', 'b.txt'])
                self.assertIsNone(archive.testzip())

    def test_shared_version_is_read_from_gradle_properties(self):
        packager = self.load_packager()
        expected = next(line.split('=', 1)[1].strip() for line in
                        (ROOT / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
        self.assertEqual(packager.project_version(ROOT), expected)


if __name__ == '__main__':
    unittest.main()
