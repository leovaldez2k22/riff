"""Make unshipped compressed acceptance inputs from a real local Windows recording."""
from pathlib import Path
import subprocess,hashlib,json,os
root=Path.cwd();out=root/'artifacts/desktop/phase1/corpus';out.mkdir(parents=True,exist_ok=True)
ff=next(p for p in (root/'.toolchain/desktop-native/ffmpeg-8.1-lgpl-shared').iterdir() if p.is_dir())/'bin/ffmpeg.exe'
source=next(p for p in (Path(os.environ['WINDIR'])/'Media').glob('*.wav') if p.stat().st_size>300000)
commands={'mp3':['-c:a','libmp3lame','-b:a','160k'],'flac':['-c:a','flac'],'m4a':['-c:a','aac','-b:a','128k']}
for extension,codec in commands.items():
    path=out/f'reference.{extension}'
    if not path.exists():subprocess.run([str(ff),'-nostdin','-hide_banner','-loglevel','error','-i',str(source),'-map','0:a:0','-t','10',*codec,str(path)],check=True)
long_file=out/'reference-long.flac'
if not long_file.exists():subprocess.run([str(ff),'-nostdin','-hide_banner','-loglevel','error','-stream_loop','4','-i',str(source),'-map','0:a:0','-c:a','flac',str(long_file)],check=True)
manifest={'origin':'local Windows Media recording; acceptance only, never redistributed','sourceName':source.name,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('reference*') if p.is_file() and p.suffix in {'.mp3','.flac','.m4a'}}}
(out/'corpus-manifest.json').write_text(json.dumps(manifest,indent=2))
keystore=out/'localhost-test.p12'
if not keystore.exists():
    keytool=root/'.toolchain/jdk-21.0.12.1+1/bin/keytool.exe'
    subprocess.run([str(keytool),'-genkeypair','-alias','localhost','-keyalg','RSA','-keysize','2048','-validity','30','-storetype','PKCS12','-keystore',str(keystore),'-storepass','riff-test-only','-keypass','riff-test-only','-dname','CN=localhost','-ext','SAN=ip:127.0.0.1,dns:localhost','-noprompt'],check=True)
print('Prepared actual compressed corpus and isolated loopback-only TLS certificate')
# Phase 2 embedded-cover/tag probe corpus: actual compressed Windows recording, generated test-only image.
import struct,zlib
cover=out/'embedded-cover.png'
if not cover.exists():
    def chunk(kind,payload):return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload)&0xffffffff)
    pixels=b''.join(b'\0'+bytes([160,40,90])*32 for _ in range(32))
    cover.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',32,32,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b''))
tagged=out/'embedded-cover.flac'
if not tagged.exists():subprocess.run([str(ff),'-nostdin','-hide_banner','-loglevel','error','-i',str(out/'reference.flac'),'-i',str(cover),'-map','0:a','-map','1:v','-c','copy','-disposition:v','attached_pic','-metadata','title=Actual compressed recording','-metadata','artist=Windows acceptance corpus','-metadata','album=Phase Two probe',str(tagged)],check=True)
