"""Stdlib runner tests; all temporary files stay inside this repo's build/."""
import importlib.util
from pathlib import Path
import sys
import unittest
import tempfile
import hashlib
import zipfile
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / 'tools/run_runtime_checks.py'


class InitialGuardTests(unittest.TestCase):
    def test_eula_must_be_explicit_even_for_restart(self):
        self.assertTrue(RUNNER.is_file(), 'runtime runner has not been implemented')
        spec = importlib.util.spec_from_file_location('runtime_runner', RUNNER)
        assert spec is not None and spec.loader is not None
        runner = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = runner
        spec.loader.exec_module(runner)
        self.runner = runner
        with self.assertRaisesRegex(ValueError, '--accept-eula'):
            runner.validate_options(runner.parser().parse_args(['--restart']))

    def test_marker_and_exit_code_are_both_required(self):
        spec = importlib.util.spec_from_file_location('runtime_runner', RUNNER)
        assert spec is not None and spec.loader is not None
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        self.assertTrue(runner.runtime_succeeded('AMPLIFIED_RUNTIME_OK 125\n', 0))
        self.assertTrue(runner.runtime_succeeded('[12:00:00] [Server thread/INFO]: AMPLIFIED_RUNTIME_OK 125\n', 0))
        for log, code in [('', 0), ('AMPLIFIED_RUNTIME_OK 1\n', 1),
                          ('AMPLIFIED_RUNTIME_OK 1\nAMPLIFIED_RUNTIME_FAILED\n', 0),
                          ('AMPLIFIED_RUNTIME_OKISH\n', 0),
                          ('expect AMPLIFIED_RUNTIME_OK 1\n', 0)]:
            with self.subTest(log=log, code=code):
                self.assertFalse(runner.runtime_succeeded(log, code))


class IsolationTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('runtime_runner', RUNNER)
        assert spec is not None and spec.loader is not None
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        scratch = ROOT / 'build/runner-unit-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def test_paths_cannot_escape_disposable_root(self):
        safe = self.base / 'build/runtime-verification'
        with mock.patch.object(self.runner, 'RUNTIME', safe):
            self.assertEqual(self.runner.guarded_path(safe / 'cache/server.jar'), safe / 'cache/server.jar')
            for bad in [self.base / 'world', safe / '../outside', safe.parent]:
                with self.subTest(path=bad), self.assertRaisesRegex(ValueError, 'outside'):
                    self.runner.guarded_path(bad)
            safe.parent.mkdir(parents=True, exist_ok=True)
            outside = self.base / 'other'
            outside.mkdir()
            try:
                safe.symlink_to(outside, target_is_directory=True)
            except OSError:
                return  # Windows without developer-mode symlink permission.
            with self.assertRaisesRegex(ValueError, 'symlink|junction|outside'):
                self.runner.guarded_path(safe / 'server.jar')

    def test_no_consent_does_not_reach_network_or_java(self):
        with mock.patch.object(self.runner, 'prepare', side_effect=AssertionError('must not prepare')):
            self.assertNotEqual(self.runner.main(['--restart']), 0)

    def test_options_require_both_jars_and_positive_timeout(self):
        for args in [['--accept-eula'], ['--prepare-only', '--timeout', '0'],
                     ['--prepare-only', '--timeout', 'nan']]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.runner.validate_options(self.runner.parser().parse_args(args))

    def test_gradle_server_cache_name_is_reused(self):
        self.assertIn('minecraft-server.jar', self.runner.SERVER_CACHE_ALIASES)

    def test_java_17_is_rejected_even_at_absolute_path(self):
        fake = self.base / 'java.exe'
        fake.write_bytes(b'not executed')
        result = mock.Mock(stdout='openjdk version "17.0.1"')
        with mock.patch.object(self.runner.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'Java 25 required'):
                self.runner.java25(fake)

    def test_prepare_only_does_not_write_eula_even_with_flag(self):
        run = self.base / 'build/runtime-verification/run'
        run.mkdir(parents=True)
        with mock.patch.object(self.runner, 'prepare', return_value=(run, run / 'launch.jar')):
            with mock.patch.object(self.runner, 'java25', return_value=str(self.runner.DEFAULT_JAVA)):
                self.assertEqual(self.runner.main(['--prepare-only', '--accept-eula']), 0)
        self.assertFalse((run / 'eula.txt').exists())

    def test_official_downloads_only(self):
        for url in ['http://maven.fabricmc.net/test.jar', 'https://evil.example/server.jar',
                    'https://maven.fabricmc.net.evil.example/test', 'https://name:pass@maven.fabricmc.net/test']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.runner.official_url(url)

    def test_properties_are_loopback_and_no_remote_management(self):
        props = self.runner.server_properties(32456)
        self.assertIn('server-ip=127.0.0.1\n', props)
        self.assertIn('server-port=32456\n', props)
        self.assertIn('online-mode=false\n', props)
        self.assertIn('enable-rcon=false\n', props)
        self.assertIn('enable-query=false\n', props)
        self.assertIn('level-type=minecraft:flat\n', props)

    def test_checksum_cache_rejects_corrupt_bytes(self):
        good = self.base / 'cached.jar'
        good.write_bytes(b'verified artifact')
        expected = hashlib.sha256(good.read_bytes()).hexdigest()
        target = self.base / 'build/runtime-verification/cache/artifact.jar'
        with mock.patch.object(self.runner, 'RUNTIME', target.parents[1]):
            self.runner.fetch('https://maven.fabricmc.net/example.jar', target, expected, candidates=[good])
            self.assertEqual(target.read_bytes(), good.read_bytes())
            target.write_bytes(b'corrupted')
            self.runner.fetch('https://maven.fabricmc.net/example.jar', target, expected, candidates=[good])
            self.assertEqual(target.read_bytes(), good.read_bytes())
            with mock.patch.object(self.runner.urllib.request, 'urlopen', return_value=mock.MagicMock()) as net:
                net.return_value.__enter__.return_value.geturl.return_value = 'https://maven.fabricmc.net/example.jar'
                net.return_value.__enter__.return_value.read.side_effect = [b'bad data', b'']
                with self.assertRaisesRegex(ValueError, 'Checksum'):
                    self.runner.fetch('https://maven.fabricmc.net/example.jar', target, '0' * 64)

    def test_datapack_requires_root_metadata_and_rejects_traversal(self):
        pack = self.base / 'pack.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('pack.mcmeta', '{"pack":{"description":"test","min_format":99,"max_format":99}}')
            archive.writestr('data/example/function/test.mcfunction', 'say test')
        self.runner.validate_zip(pack, 'datapack')
        with zipfile.ZipFile(pack, 'a') as archive:
            archive.writestr('../bad', 'no')
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            self.runner.validate_zip(pack, 'datapack')

    def test_process_captures_output_and_timeout_dominates_ok_marker(self):
        safe = self.base / 'build/runtime-verification'
        safe.mkdir(parents=True)
        with mock.patch.object(self.runner, 'RUNTIME', safe):
            result = self.runner.run_process([sys.executable, '-c', 'print("AMPLIFIED_RUNTIME_OK 1")'], safe, safe / 'ok.log', 5)
            self.assertTrue(result['ok'])
            self.assertIn('AMPLIFIED_RUNTIME_OK 1', (safe / 'ok.log').read_text())
            result = self.runner.run_process([sys.executable, '-u', '-c', 'import time; print("AMPLIFIED_RUNTIME_OK 1"); time.sleep(10)'], safe, safe / 'timeout.log', 0.1)
            self.assertTrue(result['timed_out'])
            self.assertFalse(result['ok'])


if __name__ == '__main__':
    unittest.main()
