"""Fetch hash-pinned official codec vectors, keeping the complete original package unshipped."""
from pathlib import Path
import hashlib,json,urllib.request
from urllib.parse import urlparse
ROOT=Path(__file__).resolve().parents[2]
manifest=json.loads((ROOT/'native/riff-audio/codec-test-inputs.json').read_text(encoding='utf-8'))
output=ROOT/'.toolchain/desktop-codec-tests'
items=[(output/'aacPlusDecoderCheckPackage_v2.1'/row['name'],row) for row in manifest['files']]
items.append((output/'ct_nero-heaac.mp4',manifest['heV1']))
for target,row in items:
    parsed=urlparse(row['url'])
    assert parsed.scheme=='https' and parsed.hostname=='samples.ffmpeg.org'
    assert output.resolve() in target.resolve().parents
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==row['sha256']:continue
    with urllib.request.urlopen(row['url'],timeout=30) as response:
        assert urlparse(response.url).hostname=='samples.ffmpeg.org'
        content=response.read(row['bytes']+1)
    assert len(content)==row['bytes'] and hashlib.sha256(content).hexdigest()==row['sha256'],row['url']
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_name(target.name+'.download')
    temporary.write_bytes(content);temporary.replace(target)
print(json.dumps({'verifiedFiles':len(items),'testOnly':True,'redistribute':False}))
