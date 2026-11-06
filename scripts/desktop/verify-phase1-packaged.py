"""Run the actual packaged player and worker; artifacts are isolated below the workspace."""
from pathlib import Path
import subprocess,json,time,os,signal,ctypes,sqlite3,sys
root=Path.cwd().resolve();image=root/'desktop/build/compose/binaries/main/app/Riff Desktop Preview'
state=root/'artifacts/desktop/phase1/final-journey';corpus=root/'artifacts/desktop/phase1/corpus'
assert state.resolve().is_relative_to(root)
state.mkdir(parents=True,exist_ok=True)
def command(mode):
    print('START',mode,flush=True)
    result=subprocess.run([str(image/'Riff Desktop Preview.exe'),'--phase1-acceptance',str(state),str(corpus),mode],timeout=600)
    if result.returncode:
        error=state/'acceptance-failure.txt'
        raise RuntimeError(error.read_text() if error.exists() else f'Launcher exit {result.returncode}')
    receipt=state/f'{mode}-acceptance.json'
    assert receipt.is_file(),f'Missing {mode} receipt'
    print('PASS',mode,receipt.read_text(),flush=True)
def kill_owned(pid):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.restype=ctypes.c_void_p
    kernel.QueryFullProcessImageNameW.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_wchar_p,ctypes.POINTER(ctypes.c_ulong)]
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    handle=kernel.OpenProcess(0x1000,False,int(pid))
    if not handle:return
    try:
        text=ctypes.create_unicode_buffer(32768);length=ctypes.c_ulong(len(text))
        assert kernel.QueryFullProcessImageNameW(handle,0,text,ctypes.byref(length))
        assert Path(text.value).resolve().parent==image.resolve(), 'Refusing to terminate an unrelated process'
    finally:kernel.CloseHandle(handle)
    os.kill(int(pid),signal.SIGTERM)
if not (state/'local-acceptance.json').exists():command('local')
command('restore');command('worker-crash');command('dsp-session')
print('START crash-hold',flush=True)
process=subprocess.Popen([str(image/'Riff Desktop Preview.exe'),'--phase1-acceptance',str(state),str(corpus),'crash-hold'])
marker=state/'crash-ready.json'
try:
    deadline=time.monotonic()+45
    while not marker.exists():
        assert time.monotonic()<deadline,'Crash checkpoint was not acknowledged'
        assert process.poll() is None,'Crash acceptance process exited early'
        time.sleep(.1)
    proof=json.loads(marker.read_text())
    kill_owned(proof['processId']);kill_owned(proof['workerId'])
    process.wait(timeout=15)
    connection=sqlite3.connect(state/'session.sqlite')
    assert connection.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    checkpoint=json.loads(connection.execute('select payload from session_checkpoint').fetchone()[0]);connection.close()
    assert checkpoint['position']==2345 and abs(checkpoint['volume']-.23)<.00001
    assert [entry['entry'] for entry in checkpoint['queue']]==proof['queue']
    command('restore')
    original=state/'crash-checkpoint-proof.json'
    marker.replace(original)
    print('PASS process-kill SQLite integrity + exact queue/position/volume',flush=True)
finally:
    if process.poll() is None:process.kill();process.wait()
if '--remote' in sys.argv:command('remote');command('restore')
print('RIFF_PACKAGED_PHASE1_JOURNEY_PASS',flush=True)
