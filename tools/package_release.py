#!/usr/bin/env python3
"""Package verified build outputs for a LOCAL release; never pushes or publishes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def project_version(root=ROOT):
    return next(line.split('=', 1)[1].strip() for line in
                (root / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                if line.startswith('version='))


def write_zip(target, entries):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in sorted(entries.items()):
            if name.startswith('/') or '\\' in name or '..' in Path(name).parts:
                raise ValueError('Unsafe archive path: ' + name)
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)


def build_datapack(root, target):
    # Recipes are already included in the mod. This optional export is NOT a standalone mod replacement.
    metadata = {'pack': {'description': 'Amplified Items recipes (Fabric mod required for abilities)',
                         'min_format': [121, 0], 'max_format': [121, 0]}}
    entries = {'pack.mcmeta': (json.dumps(metadata, indent=2) + '\n').encode()}
    data = root / 'src/main/resources/data'
    entries.update({p.relative_to(data.parent).as_posix(): p.read_bytes()
                    for p in sorted(data.rglob('*')) if p.is_file()})
    write_zip(target, entries)


def verify_jar(path, version):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError('Corrupt mod JAR')
        metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata['id'] != 'amplified_items' or metadata['version'] != version:
            raise ValueError('Mod JAR metadata/version mismatch')
        names = set(archive.namelist())
        required = {'club/amplifiedsmp/items/AmplifiedItems.class', 'club/amplifiedsmp/items/Summons.class',
                    'club/amplifiedsmp/items/RoyalKnightVisuals.class',
                    'assets/amplified_items/items/royal_knight.json',
                    f'bundled/amplified-items-resources-{version}.zip'}
        if required - names:
            raise ValueError('Missing release contents: ' + ', '.join(sorted(required - names)))
        if any('verification/RuntimeChecks' in name or 'AbilityKeyMixin' in name or '/Rituals' in name for name in names):
            raise ValueError('Test-only or removed legacy code leaked into production JAR')
        if 'data/amplified_items/recipe/kings_crown.json' in names:
            raise ValueError('Crown crafting bypass leaked into production JAR')


def package(root=ROOT):
    version = project_version(root)
    jar = root / f'build/libs/amplified-items-{version}.jar'
    resources = root / f'build/distributions/amplified-items-resources-{version}.zip'
    verify_jar(jar, version)
    with zipfile.ZipFile(resources) as archive:
        if archive.testzip() is not None or 'pack.mcmeta' not in archive.namelist():
            raise ValueError('Invalid resource pack')
    output = root / f'build/release/{version}'
    output.mkdir(parents=True, exist_ok=True)
    # Fail rather than silently include stale files from a prior package operation.
    expected = {jar.name, resources.name, f'amplified-items-datapack-{version}.zip',
                'SHA256SUMS.txt', 'INSTALL.md', 'RELEASE_NOTES.md', f'Amplified-Items-{version}-release.zip'}
    unexpected = {p.name for p in output.iterdir()} - expected
    if unexpected:
        raise ValueError('Unexpected files in release directory: ' + ', '.join(sorted(unexpected)))
    shutil.copy2(jar, output / jar.name)
    shutil.copy2(resources, output / resources.name)
    data = output / f'amplified-items-datapack-{version}.zip'
    build_datapack(root, data)
    for name in ('INSTALL.md', 'RELEASE_NOTES.md'):
        shutil.copy2(root / name, output / name)
    artifacts = [output / jar.name, output / resources.name, data]
    checksum_text = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in artifacts)
    (output / 'SHA256SUMS.txt').write_text(checksum_text, encoding='utf-8', newline='\n')
    # Keep the repository checksum list in sync with the packaged files.
    (root / 'SHA256SUMS.txt').write_text(checksum_text, encoding='utf-8', newline='\n')
    bundle = output / f'Amplified-Items-{version}-release.zip'
    write_zip(bundle, {p.name: p.read_bytes() for p in sorted(output.iterdir()) if p.is_file() and p != bundle})
    for artifact in artifacts:
        print(f'{artifact} ({artifact.stat().st_size} bytes)')
    print('Release bundle:', bundle)
    print(checksum_text, end='')
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    package()
