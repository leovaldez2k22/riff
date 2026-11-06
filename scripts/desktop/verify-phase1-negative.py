from pathlib import Path
import shutil,subprocess,os,json,tempfile,time
root=Path.cwd().resolve();owned=root/'artifacts/desktop/phase1';source=root/'desktop/build/compose/binaries/main/app/Riff Desktop Preview'
clone=Path(tempfile.mkdtemp(prefix='negative-image-',dir=owned)).resolve()
assert clone.is_relative_to(owned.resolve())
shutil.copytree(source,clone,dirs_exist_ok=True)
environment=dict(os.environ);environment['PATH']=str(Path(environment['WINDIR'])/'System32');environment.pop('JAVA_HOME',None);environment.pop('CLASSPATH',None)
report=[]
for label,relative in [('missing-worker','RiffAudioWorker.exe'),('missing-native','app/native/riff_audio.dll')]:
    target=(clone/relative).resolve();disabled=target.with_suffix(target.suffix+'.disabled')
    assert target.is_relative_to(clone) and disabled.is_relative_to(clone)
    state=owned/f'negative-{label}'
    target.rename(disabled)
    try:
        start=time.monotonic()
        result=subprocess.run([str(clone/'Riff Desktop Preview.exe'),'--phase1-acceptance',str(state),str(owned/'corpus'),'local'],env=environment,timeout=90)
        assert result.returncode!=0, f'{label} silently succeeded'
        assert not (state/'local-acceptance.json').exists(),f'{label} selected a fallback'
        failure=(state/'acceptance-failure.txt').read_text()
        report.append({'case':label,'exit':result.returncode,'elapsedSeconds':round(time.monotonic()-start,2),'failedVisibly':True,'noFallbackReceipt':True,'summary':failure.splitlines()[0]})
        print('PASS',label,report[-1],flush=True)
    finally:
        assert disabled.resolve().is_relative_to(clone)
        disabled.rename(target)
(owned/'negative-package-evidence.json').write_text(json.dumps(report,indent=2))
