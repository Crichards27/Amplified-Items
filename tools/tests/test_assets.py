"""Prevent dead textures and broken local resource references from returning."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AssetChecks(unittest.TestCase):
    def test_assets_have_no_missing_or_unused_local_files(self):
        path = ROOT / 'tools/audit_assets.py'
        self.assertTrue(path.exists(), 'Asset reference audit must exist')
        spec = importlib.util.spec_from_file_location('audit_assets', path)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        report = module.audit(ROOT / 'resourcepack/assets')
        self.assertEqual(report['missing'], [], 'Resource pack has broken custom references')
        self.assertEqual(report['unused'], [], 'Remove proven-unreferenced resource files')


if __name__ == '__main__':
    unittest.main()
