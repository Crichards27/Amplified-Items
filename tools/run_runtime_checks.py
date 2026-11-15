#!/usr/bin/env python3
"""Isolated, pinned Fabric dedicated-server checks (Python standard library only).

Build with Gradle verificationJar (or tools/build_direct.py --verification)
first, then pass the resulting main and isolated verification jars. Generated
files, downloads, logs and worlds stay in build/runtime-verification/.
--prepare-only installs the pinned launcher but never boots Minecraft, writes
an EULA or modifies user worlds. Actual checks require --accept-eula each time.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'build/runtime-verification'
MINECRAFT = '26.3'
SERVER_CACHE_ALIASES = ('minecraft-server.jar', f'minecraft-server-{MINECRAFT}.jar')
LOADER = '0.20.1'
INSTALLER = '1.1.2'
FABRIC_API = '0.165.0+26.3'
FABRIC_MAVEN = 'https://maven.fabricmc.net/'
DEFAULT_JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot/bin/java.exe')
MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
ALLOWED_HOSTS = {'piston-meta.mojang.com', 'piston-data.mojang.com',
                 'launcher.mojang.com', 'libraries.minecraft.net',
                 'meta.fabricmc.net', 'maven.fabricmc.net'}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--accept-eula', action='store_true', help='Explicitly accept the Minecraft EULA for this disposable test server')
    result.add_argument('--restart', action='store_true', help='Run again against the same disposable world with amplified.verifyRestart=true')
    result.add_argument('--prepare-only', action='store_true', help='Download and install only; never start Minecraft or accept its EULA')
    result.add_argument('--jar', type=Path, help='Built main mod jar (runner does not compile it)')
    result.add_argument('--verification-jar', type=Path, help='ISOLATED-TESTS-ONLY.jar built against main runtimeClasspath')
    result.add_argument('--datapack', type=Path, help='Optional datapack ZIP installed before first world load')
    result.add_argument('--resourcepack', type=Path, help='Optional resource ZIP for CRC/JSON validation, not client rendering')
    result.add_argument('--java', type=Path, default=DEFAULT_JAVA, help='Absolute Java 25 executable, never PATH')
    result.add_argument('--timeout', type=float, default=240, help='Timeout seconds per server boot (default: 240)')
    return result


def validate_options(args):
    # Consent is checked before filesystem writes, downloads or Java execution.
    if not args.prepare_only and not args.accept_eula:
        raise ValueError('Starting Minecraft requires explicit --accept-eula on every invocation')
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        raise ValueError('--timeout must be a finite positive number')
    if not args.prepare_only and (args.jar is None or args.verification_jar is None):
        raise ValueError('Supply both --jar and --verification-jar; build with verificationJar first')
    if not args.java.is_absolute():
        raise ValueError('--java must name an absolute Java 25 executable, not PATH')
    for label in ('jar', 'verification_jar', 'datapack', 'resourcepack'):
        path = getattr(args, label)
        if path is not None:
            validate_zip(path, label if label in ('datapack', 'resourcepack') else 'jar')
    if not args.prepare_only:
        for path, expected in ((args.jar, 'amplified_items'), (args.verification_jar, 'amplified_verification')):
            with zipfile.ZipFile(path) as archive:
                if json.loads(archive.read('fabric.mod.json'))['id'] != expected:
                    raise ValueError(f'{path} must be the {expected} mod')


def guarded_path(path):
    """Reject writes outside the disposable tree, including redirected parents."""
    path = Path(os.path.abspath(path))
    base = Path(os.path.abspath(RUNTIME))
    if not path.is_relative_to(base):
        raise ValueError(f'Refusing write outside {base}: {path}')
    for candidate in (path, *path.parents):
        if candidate.is_symlink() or (hasattr(candidate, 'is_junction') and candidate.is_junction()):
            raise ValueError(f'Refusing symlink/junction outside isolation: {candidate}')
    if not path.resolve().is_relative_to(base.resolve()):
        raise ValueError(f'Refusing write outside {base}: {path}')
    return path


def write_text(path, text):
    path = guarded_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def digest(path, algorithm='sha256'):
    with Path(path).open('rb') as handle:
        return hashlib.file_digest(handle, algorithm).hexdigest()


def official_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password:
        raise ValueError(f'Refusing nonofficial or non-HTTPS download: {url}')
    return url


def fetch(url, target, expected=None, algorithm='sha256', candidates=()):
    """Reuse only checksum-verified bytes, atomically publish fresh downloads."""
    official_url(url)
    target = guarded_path(target)
    if expected is not None and not re.fullmatch('[0-9a-fA-F]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', expected):
        raise ValueError(f'Invalid {algorithm} metadata for {url}')
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and expected and digest(target, algorithm).lower() == expected.lower():
        return target
    temporary = guarded_path(target.with_name(target.name + '.' + uuid.uuid4().hex + '.part'))
    try:
        for cached in candidates:
            if expected and Path(cached).is_file() and digest(cached, algorithm).lower() == expected.lower():
                shutil.copyfile(cached, temporary)
                temporary.replace(target)
                print('Verified cached', target.name, flush=True)
                return target
        print('Downloading', url, flush=True)
        request = urllib.request.Request(url, headers={'User-Agent': 'AmplifiedItems-IsolatedVerification/1.8.0'})
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
            official_url(response.geturl())
            shutil.copyfileobj(response, output)
        if expected and digest(temporary, algorithm).lower() != expected.lower():
            raise ValueError(f'Checksum mismatch ({algorithm}): {url}')
        temporary.replace(target)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def json_metadata(url, name, expected=None, algorithm='sha256'):
    path = fetch(url, RUNTIME / 'cache' / name, expected, algorithm)
    return json.loads(path.read_text(encoding='utf-8'))


def maven_checksum(url):
    official_url(url)
    with urllib.request.urlopen(url + '.sha256', timeout=45) as response:
        official_url(response.geturl())
        value = response.read().decode('ascii').strip().split()[0]
    if not re.fullmatch(r'[0-9a-fA-F]{64}', value):
        raise ValueError(f'Invalid checksum metadata: {url}')
    return value.lower()


def cached_candidates(filename):
    # Read-only discovery, never write into Gradle or the direct-build cache.
    found = []
    gradle_user = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    for base in (ROOT / 'build/direct-cache', ROOT / '.gradle', gradle_user / 'caches'):
        if base.is_dir():
            found.extend(base.rglob(filename))
    return found


def validate_zip(path, kind):
    if not Path(path).is_file():
        raise ValueError(f'Missing {kind}: {path}')
    with zipfile.ZipFile(path) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f'Corrupt archive member {bad}: {path}')
        names = archive.namelist()
        for name in names:
            part = PurePosixPath(name.replace('\\', '/'))
            if part.is_absolute() or '..' in part.parts or ':' in name:
                raise ValueError(f'Unsafe archive member {name}: {path}')
            if name.endswith('.json') or name == 'pack.mcmeta':
                json.loads(archive.read(name))
        if kind in ('datapack', 'resourcepack') and 'pack.mcmeta' not in names:
            raise ValueError(f'{kind} must have pack.mcmeta at ZIP root: {path}')
        if kind == 'jar' and 'fabric.mod.json' not in names:
            raise ValueError(f'Not a Fabric mod jar: {path}')


def runtime_succeeded(log, returncode, timed_out=False):
    """Do not accept a mere successful process exit or a failed test's later OK."""
    ok = re.search(r'(?:^|:\s)AMPLIFIED_RUNTIME_OK(?:\s+\d+)?\s*$', log, re.MULTILINE)
    failed = re.search(r'\bAMPLIFIED_RUNTIME_FAILED\b', log)
    return bool(ok and not failed and returncode == 0 and not timed_out)


def run_process(command, cwd, log_path, timeout):
    cwd, log_path = guarded_path(cwd), guarded_path(log_path)
    timed_out = False
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    # Direct-to-file output avoids blocking on a full stdout/stderr pipe.
    with log_path.open('wb') as output:
        process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.kill()
            process.wait()
        except BaseException:
            process.kill()
            process.wait()
            raise
    log = log_path.read_text(encoding='utf-8', errors='replace')
    return {'returncode': process.returncode, 'timed_out': timed_out,
            'ok': runtime_succeeded(log, process.returncode, timed_out),
            'log': str(log_path), 'command': [str(item) for item in command]}


def server_properties(port):
    return f'''# Disposable verification server only. Never reuse on a real server.
server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
enable-rcon=false
enable-query=false
broadcast-rcon-to-ops=false
level-name=world
level-type=minecraft:flat
level-seed=0
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=4
max-tick-time=60000
motd=ISOLATED VERIFICATION ONLY
pause-when-empty-seconds=-1
'''


def java25(java):
    if not java.is_absolute() or not java.is_file():
        raise ValueError(f'Install Java 25 or supply --java with its absolute executable: {java}')
    version = subprocess.run([str(java), '-version'], stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, timeout=20, check=True).stdout
    if not re.search(r'version "25(?:[.\-+\"])', version):
        raise ValueError(f'Java 25 required; found {version.strip()}')
    print(version.strip(), flush=True)
    return str(java)


def prepare(java):
    """Resolve official pins, install launcher, verify exact installed libraries."""
    run = guarded_path(RUNTIME / 'runs' / (time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]))
    run.mkdir(parents=True)
    provenance = {'pins': {'minecraft': MINECRAFT, 'loader': LOADER,
                           'installer': INSTALLER, 'fabric_api': FABRIC_API}, 'artifacts': []}

    def artifact(url, target, checksum, algorithm='sha256', aliases=()):
        candidates = []
        for name in (target.name, *aliases):
            candidates.extend(cached_candidates(name))
        cached = fetch(url, RUNTIME / 'cache' / target.name, checksum, algorithm, candidates)
        target = guarded_path(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cached, target)
        provenance['artifacts'].append({'url': url, 'path': str(target.relative_to(run)),
                                        'verified_algorithm': algorithm, 'expected': checksum,
                                        'sha256': digest(target)})
        return target

    manifest = json_metadata(MANIFEST_URL, 'minecraft-manifest.json')
    record = next((v for v in manifest['versions'] if v['id'] == MINECRAFT), None)
    if record is None:
        raise ValueError(f'Official Mojang manifest does not publish pinned Minecraft {MINECRAFT}')
    meta = json_metadata(record['url'], f'minecraft-{MINECRAFT}.json', record['sha1'], 'sha1')
    info = meta['downloads']['server']
    artifact(info['url'], run / 'server.jar', info['sha1'], 'sha1', SERVER_CACHE_ALIASES)
    fabric = json_metadata(f'https://meta.fabricmc.net/v2/versions/loader/{MINECRAFT}/{LOADER}/server/json', f'fabric-{MINECRAFT}-{LOADER}.json')
    if fabric['id'] != f'fabric-loader-{LOADER}-{MINECRAFT}' or fabric['inheritsFrom'] != MINECRAFT:
        raise ValueError('Fabric metadata does not match requested pins')
    installer_url = FABRIC_MAVEN + f'net/fabricmc/fabric-installer/{INSTALLER}/fabric-installer-{INSTALLER}.jar'
    installer = artifact(installer_url, run / f'fabric-installer-{INSTALLER}.jar', maven_checksum(installer_url))
    api_url = FABRIC_MAVEN + f'net/fabricmc/fabric-api/fabric-api/{FABRIC_API}/fabric-api-{FABRIC_API}.jar'
    artifact(api_url, run / 'mods' / f'fabric-api-{FABRIC_API}.jar', maven_checksum(api_url))
    for library in fabric['libraries']:
        group, name, version = library['name'].split(':')
        relative = Path(group.replace('.', '/')) / name / version / f'{name}-{version}.jar'
        url = library['url'].rstrip('/') + '/' + relative.as_posix()
        checksum = library.get('sha256') or maven_checksum(url)
        artifact(url, run / 'libraries' / relative, checksum)
    # Verified installer generates a pinned launch JAR; no auto-updating launcher.
    command = [java, '-Duser.home=' + str(run), '-Djava.io.tmpdir=' + str(run / 'tmp'),
               '-jar', str(installer), 'server', '-mcversion', MINECRAFT,
               '-loader', LOADER, '-dir', str(run), '-noprofile']
    guarded_path(run / 'tmp').mkdir()
    result = run_process(command, run, run / 'installer.log', 180)
    launch = run / 'fabric-server-launch.jar'
    if result['returncode'] != 0 or result['timed_out'] or not launch.is_file():
        raise ValueError(f'Fabric installer failed; see {result["log"]}')
    for item in provenance['artifacts']:
        installed = run / item['path']
        if digest(installed, item['verified_algorithm']) != item['expected']:
            raise ValueError(f'Installer changed verified artifact: {installed}')
    provenance['generated_launcher_sha256'] = digest(launch)
    provenance['installer'] = result
    write_text(run / 'provenance.json', json.dumps(provenance, indent=2) + '\n')
    return run, launch


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        validate_options(args)
        java = java25(args.java)
        guarded_path(RUNTIME)
        run, launch = prepare(java)
        print('Isolated server:', run, flush=True)
        if args.prepare_only:
            print('PREPARED ONLY: Minecraft not started; EULA not accepted.', flush=True)
            return 0
        write_text(run / 'eula.txt', '# Explicit --accept-eula for this disposable server only.\neula=true\n')
        inputs = {}
        for source, filename in ((args.jar, 'amplified-items-under-test.jar'),
                                 (args.verification_jar, 'ISOLATED-TESTS-ONLY.jar')):
            target = guarded_path(run / 'mods' / filename)
            shutil.copyfile(source, target)
            inputs[filename] = {'source': str(source.resolve()), 'sha256': digest(target)}
        if args.datapack:
            target = guarded_path(run / 'world/datapacks/verification-datapack.zip')
            target.parent.mkdir(parents=True)
            shutil.copyfile(args.datapack, target)
            inputs['datapack'] = {'source': str(args.datapack.resolve()), 'sha256': digest(target)}
        if args.resourcepack:
            inputs['resourcepack'] = {'source': str(args.resourcepack.resolve()), 'sha256': digest(args.resourcepack),
                                      'validation': 'ZIP CRC and JSON syntax only, not client rendering'}
        results = []
        for phase in ('first-boot', 'restart') if args.restart else ('first-boot',):
            # Release ephemeral port immediately before Java binds. A competing
            # bind fails the server boot closed, never falls back to LAN.
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            write_text(run / 'server.properties', server_properties(port))
            command = [java, '-Xms256M', '-Xmx1536M', '-Duser.home=' + str(run),
                       '-Djava.io.tmpdir=' + str(run / 'tmp')]
            if phase == 'restart':
                command.append('-Damplified.verifyRestart=true')
            command += ['-jar', str(launch), 'nogui']
            result = run_process(command, run, run / (phase + '.log'), args.timeout)
            result.update({'phase': phase, 'loopback_port': port})
            results.append(result)
            write_text(run / 'results.json', json.dumps({'inputs': inputs, 'runs': results}, indent=2) + '\n')
            print(f'{phase}: {"PASS" if result["ok"] else "FAIL"}; exit={result["returncode"]}; timeout={result["timed_out"]}; log={result["log"]}', flush=True)
            if not result['ok']:
                return 1
        return 0
    except (ValueError, OSError, KeyError, StopIteration, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f'Runtime verification error: {error}', file=sys.stderr, flush=True)
        return 2


if __name__ == '__main__':
    sys.exit(main())
