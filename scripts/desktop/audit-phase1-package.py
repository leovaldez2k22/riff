"""Verify packaged production separation and record native PE imports/digests."""
from pathlib import Path
import zipfile,struct,json,hashlib
root=Path.cwd();image=root/'desktop/build/compose/binaries/main/app/Riff Desktop Preview';app=image/'app'
for exe in ('Riff Desktop Preview.exe','RiffAudioWorker.exe'):assert (image/exe).is_file()
for cfg in ('Riff Desktop Preview.cfg','RiffAudioWorker.cfg'):
 text=(app/cfg).read_text();assert '-Driff.native.dir=$APPDIR/native' in text
 assert not '.toolchain' in text
worker=(app/'RiffAudioWorker.cfg').read_text();assert 'app.mainclass=com.musicglass.desktop.AudioWorkerMain' in worker
for jar in app.glob('*.jar'):
 with zipfile.ZipFile(jar) as archive:
  entries=archive.namelist()
  assert not any(name.split('/')[-1].startswith(('FixtureTracks','SyntheticTrackSource','ArtworkDeckFixture','GalleryKt','DesktopAudioBackend','DesktopDatabase')) for name in entries), f'Production fixture/old fallback in {jar.name}'
  assert not any(name.endswith(('.mp3','.flac','.m4a','.webm','.aac','.opus','.ogg','.wav','.f32')) for name in entries),f'Acceptance media bundled in {jar.name}'
expected_license=(root/'licenses/ICU4J-78.3-LICENSE.txt').read_bytes()
assert expected_license.startswith(b'UNICODE LICENSE V3')
assert any(zipfile.ZipFile(jar).read('META-INF/licenses/ICU4J-78.3-LICENSE.txt')==expected_license for jar in app.glob('*.jar') if 'META-INF/licenses/ICU4J-78.3-LICENSE.txt' in zipfile.ZipFile(jar).namelist()), 'ICU license missing or changed in package'

def imports(path):
 data=path.read_bytes();pe=struct.unpack_from('<I',data,0x3c)[0];assert data[pe:pe+4]==b'PE\0\0'
 sections=struct.unpack_from('<H',data,pe+6)[0];optional_size=struct.unpack_from('<H',data,pe+20)[0];optional=pe+24
 assert struct.unpack_from('<H',data,optional)[0]==0x20b,'Not x64 PE32+'
 tables=optional+112;section_start=optional+optional_size
 def rva(value):
  for index in range(sections):
   entry=section_start+40*index;size,address,raw_size,raw=struct.unpack_from('<IIII',data,entry+8)
   if address<=value<address+max(size,raw_size):return raw+value-address
  raise ValueError('PE RVA outside sections')
 directory,size=struct.unpack_from('<II',data,tables+8)
 result=[]
 if directory:
  entry=rva(directory)
  for index in range(1024):
   descriptor=struct.unpack_from('<IIIII',data,entry+20*index)
   if not any(descriptor):break
   position=rva(descriptor[3]);result.append(data[position:data.index(b'\0',position)].decode('ascii'))
 return sorted(result)
report={'workerProtocol':'riff-audio/2.7','native':{}}
for path in (app/'native').glob('*.dll'):
 report['native'][path.name]={'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'imports':imports(path)}
required={'riff_audio.dll','avcodec-62.dll','avformat-62.dll','avutil-60.dll','swresample-6.dll'}
assert set(report['native'])==required
(root/'artifacts/desktop/phase1/package-audit.json').write_text(json.dumps(report,indent=2))
print('PACKAGED_PHASE1_AUDIT_PASS',len(report['native']),'native libraries; no production fixtures, generated audio or development JVM path')
