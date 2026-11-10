#!/usr/bin/env python3
"""JDK 25 + Python 3 fallback build for the unobfuscated Minecraft 26.3 runtime.
Downloads compile dependencies into build/direct-cache, never bundles Minecraft.
No Gradle, native sockets, mappings or third-party Python packages are needed.
"""
import concurrent.futures, hashlib, json, os, pathlib, shutil, subprocess, sys, urllib.request, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = pathlib.Path(os.environ.get('AMPLIFIED_BUILD_CACHE', ROOT/'build/direct-cache')).resolve()
CP = CACHE/'classpath'
VERSION = '1.5.0'
def fetch(url, path, sha1=None, sha256=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    expected = sha256 or sha1
    alg = 'sha256' if sha256 else 'sha1'
    if path.exists() and (not expected or hashlib.new(alg,path.read_bytes()).hexdigest()==expected): return path
    print('Downloading',path.name,flush=True)
    with urllib.request.urlopen(url,timeout=90) as response: data=response.read()
    if expected and hashlib.new(alg,data).hexdigest()!=expected: raise RuntimeError('Checksum mismatch: '+url)
    tmp=path.with_suffix(path.suffix+'.tmp');tmp.write_bytes(data);tmp.replace(path)
    return path

def main():
    java_home = os.environ.get('JAVA_HOME')
    javac = str(pathlib.Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
    java = str(pathlib.Path(java_home)/'bin/java') if java_home else shutil.which('java')
    if not javac or not java: raise RuntimeError('Install JDK 25 and set JAVA_HOME first.')
    version = subprocess.check_output([javac,'-version'],stderr=subprocess.STDOUT,text=True).strip()
    if int(version.split()[1].split('.')[0]) != 25: raise RuntimeError('Use JDK 25. Found '+version)
    CP.mkdir(parents=True,exist_ok=True)
    manifest=json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',CACHE/'manifest.json').read_text())
    record=next(v for v in manifest['versions'] if v['id']=='26.3')
    meta=json.loads(fetch(record['url'],CACHE/'version.json',sha1=record['sha1']).read_text())
    server=fetch(meta['downloads']['server']['url'],CACHE/'server.jar',sha1=meta['downloads']['server']['sha1'])
    with zipfile.ZipFile(server) as bundle:
        for n in bundle.namelist():
            if n.startswith('META-INF/versions/') and n.endswith('.jar'): (CP/'minecraft-26.3.jar').write_bytes(bundle.read(n))
            elif n.startswith('META-INF/libraries/') and n.endswith('.jar'): (CP/pathlib.Path(n).name).write_bytes(bundle.read(n))
    fabric='https://maven.fabricmc.net/'
    loader=fetch(fabric+'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar',CP/'fabric-loader-0.20.1.jar')
    api=fetch(fabric+'net/fabricmc/fabric-api/fabric-api/0.165.2+26.3/fabric-api-0.165.2+26.3.jar',CP/'fabric-api.jar')
    info=json.loads(fetch(fabric+'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.json',CACHE/'loader.json').read_text())
    def library(l):
        group,name,ver=l['name'].split(':')
        return fetch(l['url']+group.replace('.','/')+'/'+name+'/'+ver+'/'+name+'-'+ver+'.jar',CP/(name+'-'+ver+'.jar'),sha256=l.get('sha256'),sha1=l.get('sha1'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: list(pool.map(library,info['libraries']['common']))
    with zipfile.ZipFile(api) as bundle:
        for n in bundle.namelist():
            if n.endswith('.jar'): (CP/pathlib.Path(n).name).write_bytes(bundle.read(n))
    classes=ROOT/'build/direct-classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
    classpath=os.pathsep.join(str(p) for p in sorted(CP.glob('*.jar')))
    def compile_sources(folder,dest,extra=''):
        sources=sorted(folder.rglob('*.java')); dest.mkdir(parents=True,exist_ok=True)
        # javac argfile avoids Windows command-line limits. Paths use forward slashes.
        arguments=['--release','25','-proc:none','-encoding','UTF-8','-classpath',classpath+extra,'-d',str(dest),*[str(p) for p in sources]]
        argfile=ROOT/'build/javac-args.txt'
        argfile.write_text('\n'.join('"'+s.replace('\\','/').replace('"','\\"')+'"' for s in arguments),encoding='utf-8')
        subprocess.run([javac,'@'+str(argfile)],cwd=ROOT,check=True)
    compile_sources(ROOT/'src/main/java',classes)
    testclasses=ROOT/'build/direct-test-classes';shutil.rmtree(testclasses,ignore_errors=True)
    compile_sources(ROOT/'src/test/java',testclasses,os.pathsep+str(classes))
    subprocess.run([java,'-ea','-cp',classpath+os.pathsep+str(classes)+os.pathsep+str(testclasses),'club.amplifiedsmp.items.LogicChecks'],check=True,cwd=ROOT)
    pack=ROOT/f'build/distributions/amplified-items-resources-{VERSION}.zip';pack.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(pack,'w',zipfile.ZIP_DEFLATED) as out:
        for p in sorted((ROOT/'resourcepack').rglob('*')):
            if p.is_file():out.write(p,p.relative_to(ROOT/'resourcepack'))
    target=ROOT/f'build/libs/amplified-items-{VERSION}.jar';target.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as out:
        for p in sorted(classes.rglob('*.class')):out.write(p,p.relative_to(classes))
        for p in sorted((ROOT/'src/main/resources').rglob('*')):
            if p.is_file():out.writestr(str(p.relative_to(ROOT/'src/main/resources')).replace('\\','/'),p.read_bytes().replace(b'${version}',VERSION.encode()))
        out.write(pack,'bundled/'+pack.name)
    # Also produce example configuration from the same Java defaults.
    if '--verification' in sys.argv:
        verify=ROOT/'build/verification-classes';shutil.rmtree(verify,ignore_errors=True)
        compile_sources(ROOT/'verification',verify,os.pathsep+str(classes))
        with zipfile.ZipFile(ROOT/'build/libs/ISOLATED-TESTS-ONLY.jar','w',zipfile.ZIP_DEFLATED) as out:
            for p in verify.rglob('*.class'):out.write(p,p.relative_to(verify))
            out.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'amplified_verification','version':'1.0','environment':'server','entrypoints':{'main':['club.amplifiedsmp.items.verification.RuntimeChecks']},'depends':{'amplified_items':'*'}}))
    print('Built',target)
    print('Resource pack',pack)
    print('Pack SHA-1',hashlib.sha1(pack.read_bytes()).hexdigest())
if __name__=='__main__': main()
