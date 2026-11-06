"""Record and check the desktop evidence kept under artifacts/desktop.

artifacts/ is ignored by git, so the receipts, logs and results that back the desktop
status documents exist only on one disk. This script writes a manifest of those files
(relative path, size, SHA-256) that is committed to git, and later checks the files
against it, so lost or changed evidence is noticed.

Usage:
    python scripts/desktop/evidence-manifest.py write [--root DIR] [--manifest FILE]
    python scripts/desktop/evidence-manifest.py check [--root DIR] [--manifest FILE]

The default root is artifacts/desktop in the main checkout, found through git, so the
script works from a git worktree too. Exit status of check: 0 when every recorded file
is present and unchanged, 1 otherwise. Files added since the manifest was written are
listed but do not fail the check.

What is recorded: evidence-type files (results, receipts, logs, reports, screenshots,
recordings) outside copied code, packaged images, test corpora and caches. A soak
runtime-* folder keeps its evidence; its lib, classes and native copies are skipped. Audio,
binaries and databases are never recorded. Paths are relative to the root, so the
manifest holds no user or machine paths.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path, PurePosixPath

MANIFEST_VERSION = 1
EVIDENCE_EXTENSIONS = frozenset({
    ".json", ".jsonl", ".log", ".txt", ".md", ".csv", ".tsv", ".xml", ".html",
    ".png", ".jpg", ".jpeg", ".jfr",
})
# Directory names (any depth) whose contents are copies or inputs, not evidence.
EXCLUDED_DIR_NAMES = frozenset({"corpus", "audio-cache", "cover-art", "nvda-config", "__pycache__"})
# Directory name prefixes for copied packaged images.
EXCLUDED_DIR_PREFIXES = ("negative-image-",)
# Staged soak runtimes (runtime-*) keep their evidence/ folders; only the copied code is skipped.
RUNTIME_DIR_PREFIX = "runtime-"
RUNTIME_COPY_DIRS = frozenset({"lib", "classes", "native"})
EXCLUDED_DIR_SUFFIXES = ("-corpus",)
MAX_FILE_BYTES = 64 * 1024 * 1024


def _excluded_dir(name: str, parent: str) -> bool:
    if parent.startswith(RUNTIME_DIR_PREFIX) and name in RUNTIME_COPY_DIRS:
        return True
    return (name in EXCLUDED_DIR_NAMES or name.startswith(EXCLUDED_DIR_PREFIXES)
            or name.endswith(EXCLUDED_DIR_SUFFIXES))


def main_checkout_root(start: Path) -> Path:
    """The main checkout's root, also when running inside a git worktree."""
    common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            cwd=start, capture_output=True, text=True, check=True).stdout.strip()
    return Path(common).parent


def default_root() -> Path:
    return main_checkout_root(Path(__file__).resolve().parent) / "artifacts" / "desktop"


def default_manifest() -> Path:
    return Path(__file__).resolve().parents[2] / "docs" / "desktop" / "evidence-manifest.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def collect(root: Path) -> dict[str, Path]:
    """Evidence files under root, keyed by their POSIX path relative to root."""
    if not root.is_dir():
        raise FileNotFoundError(f"Evidence root not found: {root}")
    found: dict[str, Path] = {}
    for directory, subdirectories, files in os.walk(root):
        parent = Path(directory).name
        subdirectories[:] = sorted(d for d in subdirectories if not _excluded_dir(d, parent))
        for name in sorted(files):
            path = Path(directory) / name
            if path.suffix.lower() not in EVIDENCE_EXTENSIONS or path.is_symlink():
                continue
            if path.stat().st_size > MAX_FILE_BYTES:
                continue
            relative = PurePosixPath(*path.relative_to(root).parts).as_posix()
            found[relative] = path
    return found


def build_manifest(root: Path) -> dict:
    entries = [{"path": relative, "bytes": path.stat().st_size, "sha256": sha256(path)}
               for relative, path in sorted(collect(root).items())]
    return {
        "version": MANIFEST_VERSION,
        "root": "artifacts/desktop",
        "written": _dt.date.today().isoformat(),
        "rules": {
            "extensions": sorted(EVIDENCE_EXTENSIONS),
            "excludedDirectories": sorted(EXCLUDED_DIR_NAMES),
            "excludedDirectoryPrefixes": list(EXCLUDED_DIR_PREFIXES),
            "excludedDirectorySuffixes": list(EXCLUDED_DIR_SUFFIXES),
            "excludedInsideRuntimeDirectories": sorted(RUNTIME_COPY_DIRS),
            "maxFileBytes": MAX_FILE_BYTES,
        },
        "fileCount": len(entries),
        "totalBytes": sum(entry["bytes"] for entry in entries),
        "files": entries,
    }


def load_manifest(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != MANIFEST_VERSION:
        raise ValueError(f"Unsupported manifest version {data.get('version')!r} in {path}")
    for entry in data["files"]:
        relative = PurePosixPath(entry["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"Manifest path escapes the evidence root: {entry['path']}")
    return data


def check(root: Path, manifest: dict) -> dict[str, list[str]]:
    """Missing, changed and unrecorded files; only missing or changed fail the check."""
    present = collect(root)
    missing, changed = [], []
    for entry in manifest["files"]:
        path = root.joinpath(*PurePosixPath(entry["path"]).parts)
        if not path.is_file():
            missing.append(entry["path"])
        elif path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
            changed.append(entry["path"])
    recorded = {entry["path"] for entry in manifest["files"]}
    return {"missing": missing, "changed": changed, "unrecorded": sorted(set(present) - recorded)}


def write_manifest(root: Path, destination: Path) -> dict:
    data = build_manifest(root)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    temporary.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8", newline="\n")
    os.replace(temporary, destination)
    return data


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("action", choices=("write", "check"))
    parser.add_argument("--root", type=Path, help="evidence directory (default: main checkout's artifacts/desktop)")
    parser.add_argument("--manifest", type=Path, help="manifest file (default: docs/desktop/evidence-manifest.json)")
    arguments = parser.parse_args(argv)
    root = (arguments.root or default_root()).resolve()
    manifest_path = arguments.manifest or default_manifest()

    if arguments.action == "write":
        data = write_manifest(root, manifest_path)
        print(f"Recorded {data['fileCount']} files, {data['totalBytes'] / 1048576:.1f} MB, in {manifest_path}")
        return 0

    result = check(root, load_manifest(manifest_path))
    for kind in ("missing", "changed"):
        for path in result[kind]:
            print(f"{kind.upper()}: {path}")
    if result["unrecorded"]:
        print(f"{len(result['unrecorded'])} newer files are not in the manifest (not a failure); run 'write' to add them.")
    failed = bool(result["missing"] or result["changed"])
    print("FAIL: evidence differs from the manifest" if failed else "OK: every recorded file is present and unchanged")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
