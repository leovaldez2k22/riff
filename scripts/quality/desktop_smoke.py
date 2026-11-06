"""Packaged preview startup diagnostic; measures process-to-Compose-frame, not GPU presentation."""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import platform
import queue
import re
import subprocess
import threading
import time
import math
import tempfile

class Memory(ctypes.Structure):
    _fields_ = [("cb",wintypes.DWORD),("faults",wintypes.DWORD)] + [(n,ctypes.c_size_t) for n in
        ("peakWorkingSet","workingSet","quotaPeakPaged","quotaPaged","quotaPeakNonPaged","quotaNonPaged","pagefile","peakPagefile","private")]

class ProcessEntry(ctypes.Structure):
    _fields_=[("size",wintypes.DWORD),("usage",wintypes.DWORD),("pid",wintypes.DWORD),
        ("heap",ctypes.c_size_t),("module",wintypes.DWORD),("threads",wintypes.DWORD),
        ("parent",wintypes.DWORD),("priority",wintypes.LONG),("flags",wintypes.DWORD),
        ("exe",wintypes.WCHAR*260)]

def descendants(kernel,root):
    kernel.CreateToolhelp32Snapshot.restype=wintypes.HANDLE
    kernel.Process32FirstW.argtypes=[wintypes.HANDLE,ctypes.POINTER(ProcessEntry)]
    kernel.Process32NextW.argtypes=[wintypes.HANDLE,ctypes.POINTER(ProcessEntry)]
    snap=kernel.CreateToolhelp32Snapshot(2,0)
    parents={}; entry=ProcessEntry(); entry.size=ctypes.sizeof(entry)
    try:
        ok=kernel.Process32FirstW(snap,ctypes.byref(entry))
        while ok:
            parents[entry.pid]=entry.parent
            ok=kernel.Process32NextW(snap,ctypes.byref(entry))
    finally: kernel.CloseHandle(snap)
    found={root}
    while True:
        more={pid for pid,parent in parents.items() if parent in found}
        if more<=found: return found
        found|=more

def positive_runs(value):
    count = int(value)
    if count <= 0:
        raise argparse.ArgumentTypeError("runs must be greater than zero")
    return count


def parse_scales(value):
    scales = value.split(",")
    try:
        if not scales or any(not math.isfinite(float(s)) or float(s) <= 0 for s in scales):
            raise ValueError()
    except ValueError:
        raise argparse.ArgumentTypeError("scales must be nonempty, positive finite numbers")
    return scales


def build_environment(scale, storage_root=None, environ=None):
    env = dict(os.environ if environ is None else environ)
    # Do not let inherited JVM flags undo this diagnostic's storage/scale selection.
    env.pop("_JAVA_OPTIONS", None)
    env.pop("JDK_JAVA_OPTIONS", None)
    options = f"-Dsun.java2d.uiScale={scale}"
    if storage_root is not None:
        home = storage_root / "home"
        local = storage_root / "local"
        roaming = storage_root / "roaming"
        for directory in (home, local, roaming):
            directory.mkdir(parents=True, exist_ok=True)
        options += f' "-Duser.home={home}"'
        env.update(USERPROFILE=str(home), LOCALAPPDATA=str(local), APPDATA=str(roaming))
    env["JAVA_TOOL_OPTIONS"] = options
    return env


def run(exe, scale, isolate_storage=False):
    if isolate_storage:
        with tempfile.TemporaryDirectory(prefix="riff-smoke-isolated-") as owned:
            root = Path(owned)
            result = run_process(exe, scale, root)
            result["isolatedFiles"] = sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())
            return result
    return run_process(exe, scale)


def run_process(exe, scale, storage_root=None):
    env=build_environment(scale, storage_root)
    started_wall=time.time()*1000
    started=time.perf_counter()
    process=subprocess.Popen([str(exe),"--smoke-test"],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                             text=True,env=env,creationflags=subprocess.CREATE_NO_WINDOW)
    lines=[]; frames=queue.Queue()
    def read():
        for line in process.stdout:
            lines.append(line.strip())
            if "RIFF_FIRST_COMPOSE_FRAME" in line:
                wall=re.search(r"wallTimeMs=(\d+)",line)
                frames.put((time.perf_counter(),float(wall.group(1)) if wall else None))
    reader=threading.Thread(target=read,daemon=True); reader.start()
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    psapi=ctypes.WinDLL("psapi",use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes=[wintypes.HANDLE,ctypes.POINTER(Memory),wintypes.DWORD]
    handle=None
    working=private=0
    try:
        while process.poll() is None and time.perf_counter()-started<20:
            tree_working=tree_private=0
            for pid in descendants(kernel,process.pid):
                handle=kernel.OpenProcess(0x410,False,pid)
                try:
                    info=Memory(); info.cb=ctypes.sizeof(info)
                    if handle and psapi.GetProcessMemoryInfo(handle,ctypes.byref(info),info.cb):
                        tree_working+=info.workingSet; tree_private+=info.private
                finally:
                    if handle: kernel.CloseHandle(handle)
                    handle=None
            working=max(working,tree_working); private=max(private,tree_private)
            time.sleep(.05)
        if process.poll() is None: process.terminate(); process.wait(timeout=5)
    finally:
        if handle: kernel.CloseHandle(handle)
    reader.join(timeout=2)
    received=source_wall=None
    if not frames.empty(): received,source_wall=frames.get()
    ready=None if source_wall is None else source_wall-started_wall
    receipt=None if received is None else (received-started)*1000
    return dict(isolatedStorage=storage_root is not None, markerCount=sum("RIFF_FIRST_COMPOSE_FRAME" in line for line in lines),scale=scale,exitCode=process.returncode,totalProcessMs=(time.perf_counter()-started)*1000,composeFrameMs=ready,receivedMarkerMs=receipt,
                peakTreeWorkingSetBytes=working,maxTreePrivateBytes=private,output=lines)

def main():
    p=argparse.ArgumentParser(); p.add_argument("--exe",type=Path,required=True)
    p.add_argument("--runs",type=positive_runs,default=5); p.add_argument("--scales",type=parse_scales,default="1.0")
    p.add_argument("--isolate-storage",action="store_true",help="Use a separate temporary Java home/app-data root for every run")
    p.add_argument("--output",type=Path,required=True); args=p.parse_args()
    results=[run(args.exe.resolve(),scale,args.isolate_storage) for scale in args.scales for _ in range(args.runs)]
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(dict(platform=platform.platform(),cpu=platform.processor(),
        cache="warm file cache; process starts",presentationMeasured=False,results=results),indent=2),encoding="utf-8")
    print(args.output)
    raise SystemExit(0 if all(r["exitCode"]==0 and r["composeFrameMs"] is not None and r["markerCount"]==1 for r in results) else 1)

if __name__=="__main__": main()



