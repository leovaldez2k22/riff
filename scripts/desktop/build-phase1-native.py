from pathlib import Path
import subprocess, shutil, json, hashlib, tarfile
root=Path.cwd();tools=root/'.toolchain/desktop-native'
zig=next((tools/'zig-0.15.2').rglob('zig.exe'))
ff=next(p for p in (tools/'ffmpeg-8.1-lgpl-shared').iterdir() if p.is_dir())
sound=root/'.toolchain/desktop-native/soundtouch-2.4.1'
archive=root/'native/riff-audio/vendor/soundtouch-2.4.1.tar.gz'
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='e07abf20ce8f95850c280132e1f61ad400fc1f4011b7fac698a503de6aab6733','SoundTouch pinned source mismatch'
with tarfile.open(archive,'r:gz') as package:
    for member in package.getmembers():
        parts=Path(member.name).parts
        if len(parts)<2:continue
        relative=Path(*parts[1:])
        if not (relative.parts[0]=='include' or relative.parts[:2]==('source','SoundTouch')):continue
        if member.isdir():continue
        assert member.isfile() and not relative.is_absolute() and '..' not in relative.parts and all(':' not in part for part in relative.parts)
        target=sound/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(package.extractfile(member).read())
out=root/'desktop/build/native';out.mkdir(parents=True,exist_ok=True)
print('Native SDK:',ff,flush=True)
args=[str(zig),'c++','-target','x86_64-windows-gnu','-std=c++20','-O2','-shared','-fms-extensions',str(root/'native/riff-audio/riff_audio.cpp'),'-I'+str(ff/'include'),'-I'+str(root/'.toolchain/jdk-21.0.12.1+1/include'),'-I'+str(root/'.toolchain/jdk-21.0.12.1+1/include/win32'),'-L'+str(ff/'lib'),'-lavformat','-lavcodec','-lswresample','-lavutil','-lole32','-lavrt','-luuid','-lwinmm','-o',str(out/'riff_audio.dll')]
args[args.index('-I'+str(ff/'include')):args.index('-I'+str(ff/'include'))]=['-DSOUNDTOUCH_FLOAT_SAMPLES','-I'+str(sound/'include'),'-I'+str(sound/'source/SoundTouch')]+[str(p) for p in sorted((sound/'source/SoundTouch').glob('*.cpp'))]
subprocess.run(args,check=True)
for dll in (ff/'bin').glob('*.dll'):shutil.copy2(dll,out/dll.name)
print('Native bridge built:',out,flush=True)