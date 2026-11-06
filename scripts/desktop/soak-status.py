"""Report the honest status of a desktop soak run (PhaseThreeSoak's result.json).

A result.json that says RUNNING is believed only while the process that wrote it is still alive
(same PID and same process start time, so a reused PID doesn't count) and its heartbeat is less
than two minutes old. Otherwise the run is STALE: it stopped without writing PASS or FAIL.

Usage: python scripts/desktop/soak-status.py <result.json or evidence directory> [--json]
Exit codes: 0 RUNNING or PASS, 1 STALE or FAIL, 2 unreadable or unknown file.
"""

import argparse
import json
import sys
import time
from pathlib import Path

RUNNING, STALE, PASS, FAIL = "RUNNING", "STALE", "PASS", "FAIL"
HEARTBEAT_LIMIT_MS = 2 * 60 * 1000
# Java's ProcessHandle and Windows' GetProcessTimes read the same creation time; allow for rounding.
START_TOLERANCE_MS = 2000
EXIT_CODES = {RUNNING: 0, PASS: 0, STALE: 1, FAIL: 1}


def process_start_ms(pid):
    """Start time of a live process in epoch milliseconds, or None if it isn't running or can't be read."""
    if sys.platform != "win32":
        return None  # The soak runs only on Windows; elsewhere liveness can't be proved.
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    process_query_limited_information, still_active = 0x1000, 259
    handle = kernel32.OpenProcess(process_query_limited_information, False, int(pid))
    if not handle:
        return None
    try:
        code = wintypes.DWORD()
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value != still_active:
            return None
        created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
        if not kernel32.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                        ctypes.byref(kernel), ctypes.byref(user)):
            return None
        ticks = (created.dwHighDateTime << 32) | created.dwLowDateTime  # 100 ns since 1601
        return ticks // 10_000 - 11_644_473_600_000
    finally:
        kernel32.CloseHandle(handle)


def evaluate(result, now_ms, start_of=process_start_ms):
    """Return (status, reason) for a parsed result.json."""
    if not isinstance(result, dict):
        raise ValueError("result.json is not a JSON object")
    written = result.get("status")
    if written == PASS:
        return PASS, "the run finished and wrote PASS"
    if written == FAIL:
        return FAIL, "the run wrote FAIL: " + str(result.get("error", "no error message"))
    if written != RUNNING:
        raise ValueError(f"unknown status {written!r}")
    fields = ("pid", "processStartMs", "heartbeatEpochMs")
    if any(not isinstance(result.get(name), int) or isinstance(result.get(name), bool) for name in fields):
        return STALE, "says RUNNING but has no pid, processStartMs and heartbeatEpochMs, so it can't prove it is alive"
    age = now_ms - result["heartbeatEpochMs"]
    if age > HEARTBEAT_LIMIT_MS:
        return STALE, f"last heartbeat was {age // 1000} s ago (limit {HEARTBEAT_LIMIT_MS // 1000} s)"
    actual = start_of(result["pid"])
    if actual is None:
        return STALE, f"process {result['pid']} is not running"
    if abs(actual - result["processStartMs"]) > START_TOLERANCE_MS:
        return STALE, f"process {result['pid']} is a different process (the PID was reused)"
    return RUNNING, f"process {result['pid']} is alive and its heartbeat is {max(age, 0) // 1000} s old"


def read_result(path):
    path = Path(path)
    if path.is_dir():
        path = path / "result.json"
    with path.open(encoding="utf-8") as handle:
        return path, json.load(handle)


def main(argv=None, now_ms=None, start_of=process_start_ms):
    parser = argparse.ArgumentParser(description="Report whether a desktop soak is really running.")
    parser.add_argument("result", help="result.json, or the evidence directory that holds it")
    parser.add_argument("--json", action="store_true", help="print a JSON object instead of one line")
    args = parser.parse_args(argv)
    try:
        path, result = read_result(args.result)
        status, reason = evaluate(result, int(time.time() * 1000) if now_ms is None else now_ms, start_of)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps({"status": status, "written": result.get("status"), "reason": reason}))
    else:
        print(f"{status}: {reason} ({path})")
    return EXIT_CODES[status]


if __name__ == "__main__":
    sys.exit(main())
