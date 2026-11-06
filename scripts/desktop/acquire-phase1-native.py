"""Acquire only the reviewed, hash-pinned native dependencies. Never follow a new latest digest."""
from pathlib import Path
import urllib.request,json,hashlib,zipfile,os
root=Path('.toolchain/desktop-native');root.mkdir(parents=True,exist_ok=True)
items=json.loads(Path('native/riff-audio/dependencies.json').read_text())
for item in items:
    assert len(item['sha256'])==64 and all(c in '0123456789abcdef' for c in item['sha256'])
    path=root/Path(item['url']).name
    if not path.exists():
        print('Downloading pinned dependency',item['name'],item['bytes'],flush=True)
        temporary=path.with_suffix('.download')
        try:
            with urllib.request.urlopen(urllib.request.Request(item['url'],headers={'User-Agent':'Riff-desktop-phase1-build'}),timeout=120) as src,temporary.open('wb') as dst:
                while b:=src.read(1024*1024):dst.write(b)
                dst.flush();os.fsync(dst.fileno())
            with temporary.open('rb') as verified:actual=hashlib.sha256(verified.read()).hexdigest()
            if temporary.stat().st_size!=item['bytes'] or actual!=item['sha256']:raise RuntimeError('Pinned dependency digest/size mismatch; review a new pin explicitly')
            temporary.replace(path)
        finally:
            if temporary.exists():temporary.unlink()
    with path.open('rb') as f:actual=hashlib.sha256(f.read()).hexdigest()
    if actual!=item['sha256']:raise RuntimeError('Cached dependency digest mismatch')
    target=root/item['name']
    marker=target/'.riff-extracted-sha256'
    if not marker.exists():
        target.mkdir(exist_ok=True)
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if not (target/entry.filename).resolve().is_relative_to(target.resolve()):raise RuntimeError('Unsafe archive member')
            archive.extractall(target)
        marker.write_text(actual)
    if marker.read_text()!=actual:raise RuntimeError('Extracted native dependency belongs to a different digest')
    print('Verified',item['name'],actual,flush=True)
(root/'download-manifest.json').write_text(json.dumps(items,indent=2),encoding='utf-8')
