"""Targeted stress scenarios for Riff over adb (no UI-layout dependence except nav_leak).

Usage:  python scripts/stress/scenarios.py [scenario ...]   (no args = all, in order)
Each scenario captures logcat, records crashes/ANRs/native crashes for the app process,
and writes artifacts/stress/scenarios/<name>/{logcat.txt,result.json}.
Every device change a scenario makes is undone in its finally block.
"""
import json, re, subprocess, sys, threading, time
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
ADB = str(PROJECT / '.toolchain/sdk/platform-tools/adb.exe')
PKG = 'com.musicglass.personal'
OUT = PROJECT / 'artifacts/stress/scenarios'


def sh(cmd, timeout=120):
    r = subprocess.run([ADB, 'shell', cmd], capture_output=True, text=True, timeout=timeout,
                       encoding='utf-8', errors='replace')
    return (r.stdout + r.stderr).strip()


def app_pid():
    return sh(f'pidof {PKG}').strip()


def session():
    """State, position (ms) and title of Riff's media session, or None if absent."""
    dump = sh('dumpsys media_session')
    i = dump.find(f'package={PKG}')
    if i < 0:
        return None
    block = dump[i:i + 3000]
    st = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(-?\d+).*?updated=(\d+)', block)
    title = re.search(r'description=(.*)', block)
    if not st:
        return None
    return {'state': st.group(1), 'position': int(st.group(2)), 'updated': int(st.group(3)),
            'title': title.group(1).split(', ')[0].strip() if title else None}


def live_position(s):
    """Extrapolate position to now for PLAYING sessions (dumpsys position is as of `updated`)."""
    if not s:
        return None
    if s['state'] != 'PLAYING':
        return s['position']
    uptime_ms = int(float(sh('cat /proc/uptime').split()[0]) * 1000)
    return s['position'] + max(0, uptime_ms - s['updated'])


def wait_for(pred, timeout, step=1.0):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return None


def launch():
    sh(f'am start -W -n {PKG}/.MainActivity')
    time.sleep(2)


def start_service():
    # Behind the lock screen the activity never connects, so start the session service directly.
    sh(f'am start-service -n {PKG}/.MusicPlaybackService -a androidx.media3.session.MediaSessionService')
    return wait_for(session, 15, 1)


def ensure_playing(timeout=45):
    launch()
    if not session():
        start_service()
    if (s := session()) and s['state'] == 'PLAYING':
        return s
    sh('cmd media_session dispatch play')
    return wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, timeout, 2)


def java_heap_kb():
    m = re.search(r'Java Heap:\s+(\d+)', sh(f'dumpsys meminfo {PKG}'))
    return int(m.group(1)) if m else None


def force_gc():
    pid = app_pid()
    if pid:
        sh(f'run-as {PKG} kill -10 {pid}')  # SIGUSR1: ART forces a GC in debuggable apps
        time.sleep(3)


class Capture:
    """Logcat capture plus crash/ANR accounting for one scenario."""
    def __init__(self, name):
        self.dir = OUT / name
        self.dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([ADB, 'logcat', '-c'], capture_output=True)
        self.file = open(self.dir / 'logcat.txt', 'w', encoding='utf-8', errors='replace')
        self.proc = subprocess.Popen([ADB, 'logcat', '-v', 'threadtime', '-b', 'main,system,crash'],
                                     stdout=self.file, stderr=subprocess.STDOUT)

    def stop(self):
        time.sleep(2)
        self.proc.kill()
        self.file.close()
        log = (self.dir / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
        return {
            'fatal': len(re.findall(r'FATAL EXCEPTION', log)),
            'fatalLines': re.findall(r'.*AndroidRuntime: (?:Process|java|kotlin|android)\S*.*', log)[:6],
            'anr': len(re.findall(rf'ANR in {re.escape(PKG)}', log)),
            'native': len(re.findall(r'Fatal signal', log)),
            'strictMode': len(re.findall(r'StrictMode policy violation|StrictMode: Tried to access', log)),
        }


def run(name, fn):
    print(f'== {name} ({time.strftime("%H:%M:%S")})', flush=True)
    cap = Capture(name)
    pid_before = app_pid()
    result = {'scenario': name, 'started': time.strftime('%H:%M:%S')}
    try:
        result.update(fn() or {})
    except Exception as e:  # scenario harness failure, not necessarily an app bug
        result['harnessError'] = repr(e)
    result.update(cap.stop())
    result['pidBefore'], result['pidAfter'] = pid_before, app_pid()
    checks = result.get('checks', {})
    result['pass'] = (not result.get('harnessError') and result['fatal'] == 0 and result['anr'] == 0
                      and result['native'] == 0 and all(checks.values()))
    (cap.dir / 'result.json').write_text(json.dumps(result, indent=2))
    with open(OUT / 'summary.jsonl', 'a') as f:
        f.write(json.dumps(result) + '\n')
    print(json.dumps({k: result[k] for k in result if k not in ('fatalLines',)}), flush=True)
    return result


# ---------------------------------------------------------------- scenarios

def rapid_skip():
    start = ensure_playing()
    if not start:
        return {'checks': {'startedPlaying': False}}
    titles = set()
    for i in range(150):
        sh('cmd media_session dispatch next')
        time.sleep(0.25)
        if i % 15 == 0 and (s := session()):
            titles.add(s['title'])
    for _ in range(50):
        sh('cmd media_session dispatch previous')
        time.sleep(0.25)
    recovered = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 60, 2)
    return {'distinctTitlesSampled': len(titles), 'final': session(),
            'checks': {'startedPlaying': True, 'appAlive': bool(app_pid()), 'playingAfterStorm': bool(recovered)}}


def play_pause_storm():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    for _ in range(200):
        sh('cmd media_session dispatch play-pause')
        time.sleep(0.1)
    # 200 toggles is even: should end playing. Give it time to settle.
    settled = wait_for(lambda: (s := session()) and s['state'] in ('PLAYING', 'PAUSED') and s, 30, 2)
    sh('cmd media_session dispatch play')
    playing = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 45, 2)
    return {'settled': settled, 'checks': {'appAlive': bool(app_pid()), 'resumesAfterStorm': bool(playing)}}


def _restore_after_kill(paused):
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    time.sleep(8)
    if paused:
        sh('cmd media_session dispatch pause')
        time.sleep(7)  # periodic snapshot runs every 5 s; pause also saves
    before = session()
    before_pos = live_position(before)
    sh('input keyevent KEYCODE_HOME')
    time.sleep(2)
    pid = app_pid()
    sh(f'run-as {PKG} kill -9 {pid}')  # what a low-memory kill looks like to the app
    time.sleep(3)
    killed = not app_pid() or app_pid() != pid
    launch()
    if not wait_for(session, 8, 1):
        start_service()
    after = wait_for(lambda: session(), 30, 2)
    pos_diff = (after['position'] - before_pos) if after and before_pos is not None else None
    tolerance = 1500 if paused else 7000
    return {'before': before, 'after': after, 'positionDiffMs': pos_diff,
            'checks': {'processKilled': killed, 'sessionRestored': bool(after),
                       'sameSong': bool(after and before and after['title'] == before['title']),
                       'positionKept': pos_diff is not None and abs(pos_diff) <= tolerance,
                       'restoredPaused': bool(after and after['state'] in ('PAUSED', 'STOPPED', 'NONE'))}}


def restore_after_kill_paused():
    return _restore_after_kill(paused=True)


def restore_after_kill_playing():
    return _restore_after_kill(paused=False)


def config_churn():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    launch()
    force_gc()
    heap0 = java_heap_kb()
    pos0 = live_position(session())
    t0 = time.time()
    try:
        for i in range(20):
            sh(f'settings put system user_rotation {1 if i % 2 == 0 else 0}')
            time.sleep(1.5)
        for mode in ('no', 'yes') * 3:
            sh(f'cmd uimode night {mode}')
            time.sleep(2)
        for scale in ('1.3', '1.0') * 2:
            sh(f'settings put system font_scale {scale}')
            time.sleep(2.5)
        for d in ('520', 'reset') * 2:
            sh(f'wm density {d}')
            time.sleep(3)
    finally:
        sh('settings put system user_rotation 0')
        sh('cmd uimode night yes')  # baseline captured night mode = yes
        sh('settings put system font_scale 1.0')
        sh('wm density reset')
    time.sleep(3)
    force_gc()
    heap1 = java_heap_kb()
    pos1 = live_position(session())
    advanced = pos1 is not None and pos0 is not None and pos1 - pos0 > (time.time() - t0) * 1000 * 0.5
    return {'javaHeapKbBefore': heap0, 'javaHeapKbAfter': heap1,
            'checks': {'appAlive': bool(app_pid()), 'playbackContinued': advanced}}


def network_flap():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    try:
        for _ in range(8):
            sh('cmd wifi set-wifi-enabled disabled'); sh('svc data disable')
            time.sleep(15)
            sh('cmd wifi set-wifi-enabled enabled'); sh('svc data enable')
            time.sleep(20)
        sh('cmd connectivity airplane-mode enable'); time.sleep(60)
        sh('cmd connectivity airplane-mode disable'); time.sleep(5)
    finally:
        sh('cmd connectivity airplane-mode disable')
        sh('cmd wifi set-wifi-enabled enabled'); sh('svc data enable')
    online = wait_for(lambda: 'Wifi is connected' in sh('cmd wifi status') or None, 60, 3)
    sh('cmd media_session dispatch play')
    playing = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 90, 3)
    if playing:
        p0 = live_position(playing); time.sleep(10); p1 = live_position(session())
    advancing = bool(playing) and p1 is not None and p1 - p0 > 5000
    return {'final': session(), 'checks': {'wifiBack': bool(online), 'appAlive': bool(app_pid()),
                                           'playbackRecovers': advancing}}


def trim_memory_background():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    sh('input keyevent KEYCODE_HOME'); time.sleep(2)
    for level in ('RUNNING_MODERATE', 'RUNNING_LOW', 'RUNNING_CRITICAL', 'UI_HIDDEN', 'BACKGROUND', 'MODERATE', 'COMPLETE'):
        sh(f'am send-trim-memory {PKG} {level}'); time.sleep(2)
    p0 = live_position(session()); time.sleep(10); p1 = live_position(session())
    launch()
    # No foreground check: from 00:48 the phone sat on its secure lock screen, which the run must not unlock.
    return {'checks': {'appAlive': bool(app_pid()), 'playingInBackground': p0 is not None and p1 is not None and p1 - p0 > 5000}}


def background_doze():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    sh('input keyevent KEYCODE_HOME'); time.sleep(2)
    minutes = 10
    try:
        sh(f'dumpsys deviceidle whitelist -{PKG}')
        sh('dumpsys battery unplug')
        sh('dumpsys deviceidle force-idle')
        p0 = live_position(session()); t0 = time.time()
        samples = []
        for _ in range(minutes * 2):
            time.sleep(30)
            s = session(); samples.append(s and s['state'])
        p1 = live_position(session()); dt = (time.time() - t0) * 1000
        idle = sh('dumpsys deviceidle get deep')
    finally:
        sh('dumpsys deviceidle unforce'); sh('dumpsys battery reset')
        sh(f'dumpsys deviceidle whitelist +{PKG}')
    launch()
    return {'deepIdleState': idle, 'states': samples, 'advancedMs': (p1 - p0) if p0 is not None and p1 is not None else None,
            'wallMs': int(dt),
            'checks': {'appAlive': bool(app_pid()), 'kepPlayingThroughDoze': p0 is not None and p1 is not None and p1 - p0 > dt * 0.8}}


def nav_leak():
    launch()
    sh('cmd media_session dispatch pause')
    sh(f'uiautomator dump /data/local/tmp/riff-ui.xml')
    xml = sh('cat /data/local/tmp/riff-ui.xml')
    tabs = {}
    for label in ('Home', 'Podcasts', 'Radio', 'Library'):
        # Bottom navigation labels: take the lowest node with this exact text.
        nodes = re.findall(rf'text="{label}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if nodes:
            x1, y1, x2, y2 = map(int, max(nodes, key=lambda n: int(n[1])))
            tabs[label] = ((x1 + x2) // 2, (y1 + y2) // 2)
    if len(tabs) < 4:
        return {'tabsFound': list(tabs), 'checks': {'foundNavigation': False}}
    force_gc(); heap0 = java_heap_kb()
    order = ['Home', 'Podcasts', 'Radio', 'Library']
    for i in range(120):
        x, y = tabs[order[i % 4]]
        sh(f'input tap {x} {y}')
        time.sleep(0.8)
    sh(f'input tap {tabs["Home"][0]} {tabs["Home"][1]}'); time.sleep(3)
    force_gc(); heap1 = java_heap_kb()
    gfx = sh(f'dumpsys gfxinfo {PKG} | grep -E "Total frames|Janky frames|90th|99th"')
    growth = (heap1 - heap0) if heap0 and heap1 else None
    return {'javaHeapKbBefore': heap0, 'javaHeapKbAfter': heap1, 'growthKb': growth, 'gfx': gfx,
            'checks': {'foundNavigation': True, 'appAlive': bool(app_pid()), 'heapGrowthUnder20MB': growth is not None and growth < 20_000}}


SCENARIOS = {f.__name__: f for f in [rapid_skip, play_pause_storm, restore_after_kill_paused, restore_after_kill_playing,
                                     config_churn, network_flap, trim_memory_background, nav_leak, background_doze]}

if __name__ == '__main__':
    names = sys.argv[1:] or list(SCENARIOS)
    OUT.mkdir(parents=True, exist_ok=True)
    for n in names:
        run(n, SCENARIOS[n])
