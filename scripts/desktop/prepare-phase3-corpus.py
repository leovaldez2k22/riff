"""Build unshipped acceptance media from an actual local recording and approved official codec vectors."""
from pathlib import Path
import subprocess,json,hashlib
root=Path.cwd();out=root/'artifacts/desktop/phase3/corpus';out.mkdir(parents=True,exist_ok=True)
sdk=next(p for p in (root/'.toolchain/desktop-native/ffmpeg-8.1-lgpl-shared').iterdir() if p.is_dir())
ff=sdk/'bin/ffmpeg.exe';probe=sdk/'bin/ffprobe.exe';source=root/'artifacts/desktop/phase1/corpus/reference.flac'
variants={
 'alac.m4a':['-c:a','alac','-ar','44100'],
 'vorbis.ogg':['-c:a','libvorbis','-q:a','5','-ar','44100'],
 'opus.opus':['-c:a','libopus','-b:a','128k'],
 'opus.webm':['-c:a','libopus','-b:a','128k'],
 'aac-lc.m4a':['-c:a','aac','-b:a','128k','-ar','32000'],
 'pcm-u8.wav':['-c:a','pcm_u8','-ar','8000','-ac','1'],
 'pcm-s16.wav':['-c:a','pcm_s16le','-ar','44100'],
 'pcm-s24.wav':['-c:a','pcm_s24le','-ar','96000'],
 'pcm-s32.wav':['-c:a','pcm_s32le','-ar','192000'],
 'pcm-f32.wav':['-c:a','pcm_f32le','-ar','48000'],
 'pcm-f64.wav':['-c:a','pcm_f64le','-ar','96000'],
 'pcm-8ch.wav':['-c:a','pcm_s24le','-ar','48000','-ac','8'],
 'flac16.flac':['-c:a','flac','-sample_fmt','s16','-ar','44100'],
 'flac24.flac':['-c:a','flac','-sample_fmt','s32','-bits_per_raw_sample','24','-ar','96000'],
 'mp3.mp3':['-c:a','libmp3lame','-b:a','160k','-ar','44100']}
manifest=[]
inputs=[]
for name,args in variants.items():
 target=out/name
 if not target.exists():subprocess.run([str(ff),'-nostdin','-hide_banner','-loglevel','error','-i',str(source),'-map','0:a:0','-t','4',*args,str(target)],check=True)
 inputs.append((name,target,'actual Windows recording, codec-container test only'))
inputs.extend([('he-v1',root/'.toolchain/desktop-codec-tests/ct_nero-heaac.mp4','official FFmpeg test sample'),('he-v2-mp4',root/'.toolchain/desktop-codec-tests/aacPlusDecoderCheckPackage_v2.1/bitstreams/File2.mp4','complete original package retained outside application; local noncommercial test only'),('he-v2-adts',root/'.toolchain/desktop-codec-tests/aacPlusDecoderCheckPackage_v2.1/bitstreams/File1.aac','complete original package retained outside application; local noncommercial test only')])
for name,path,origin in inputs:
 ref=out/(name+'.f32')
 if not ref.exists():subprocess.run([str(ff),'-nostdin','-hide_banner','-loglevel','error','-i',str(path),'-map','0:a:0','-ar','48000','-ac','2','-c:a','pcm_f32le','-f','f32le',str(ref)],check=True)
 streams=json.loads(subprocess.check_output([str(probe),'-v','error','-select_streams','a:0','-show_entries','stream=codec_name,profile,sample_rate,channels,bits_per_raw_sample','-of','json',str(path)]))['streams'][0]
 manifest.append({'name':name,'source':str(path.relative_to(root)),'reference':str(ref.relative_to(root)),'origin':origin,'format':streams,'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'referenceSha256':hashlib.sha256(ref.read_bytes()).hexdigest(),'frames':ref.stat().st_size//8})
(out/'manifest.json').write_text(json.dumps({'mixRate':48000,'channels':2,'testOnly':True,'redistribute':False,'cases':manifest},indent=2),encoding='utf-8')
print(json.dumps({'cases':len(manifest),'referenceFrames':sum(c['frames'] for c in manifest)}))
