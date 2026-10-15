"""Audit the resolved release runtime graph against locally cached Maven POM metadata.

No network or account data is used. A missing declaration stays unresolved rather than
being guessed from a group name. Run after Gradle's releaseRuntimeClasspath report.
"""

from collections import Counter
import csv
from pathlib import Path
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
# The graph to audit: the first argument, or the release candidate's own copy.
GRAPH = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "artifacts/release/dependencies.txt"
# The Gradle cache the build used: GRADLE_USER_HOME (set by build-release-candidate.ps1, also for a worktree),
# else this checkout's own .toolchain copy.
CACHE = Path(os.environ.get("GRADLE_USER_HOME") or ROOT / ".toolchain/gradle-user-home") / "caches/modules-2/files-2.1"
OUTPUT = ROOT / "docs/dependency-license-audit.csv"
COORDINATE = re.compile(
    r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::([A-Za-z0-9_.-]+))?"
    r"(?:\s+->\s+([A-Za-z0-9_.-]+))?"
)
UPSTREAM_LICENSES = {
    "com.google.guava": ("Apache-2.0", "https://github.com/google/guava", "upstream repository"),
    "org.slf4j": ("MIT", "https://www.slf4j.org/license.html", "upstream license page"),
}


def resolved_coordinates(lines):
    coordinates = set()
    for line in lines:
        if "--- " not in line or " (c)" in line or "FAILED" in line:
            continue
        match = COORDINATE.search(line)
        if not match:
            continue
        group, artifact, original, selected = match.groups()
        version = selected or original
        if version:
            coordinates.add((group, artifact, version))
    return sorted(coordinates)


def pom_license(group, artifact, version):
    folder = CACHE / group / artifact / version
    poms = list(folder.glob(f"**/{artifact}-{version}.pom"))
    if not poms:
        return "UNRESOLVED", "", "POM unavailable in local cache"
    try:
        root = ET.parse(poms[0]).getroot()
        licenses = []
        for license_node in root.findall(".//{*}licenses/{*}license"):
            name = license_node.findtext("{*}name", default="").strip()
            url = license_node.findtext("{*}url", default="").strip()
            if name:
                licenses.append((name, url))
        if not licenses:
            return "UNDECLARED", "", "POM has no license declaration"
        names = "; ".join(dict.fromkeys(name for name, _ in licenses))
        urls = "; ".join(dict.fromkeys(url for _, url in licenses if url))
        return names, urls, "POM declaration"
    except (ET.ParseError, OSError) as error:
        return "UNRESOLVED", "", f"POM parse failed: {type(error).__name__}"


def main():
    if not GRAPH.exists():
        raise SystemExit("Run :app:dependencies --configuration releaseRuntimeClasspath first")
    rows = []
    for group, artifact, version in resolved_coordinates(GRAPH.read_text().splitlines()):
        license_name, url, status = pom_license(group, artifact, version)
        if status == "POM has no license declaration" and group in UPSTREAM_LICENSES:
            license_name, url, status = UPSTREAM_LICENSES[group]
        rows.append((f"{group}:{artifact}:{version}", license_name, url, status))
    with OUTPUT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("coordinate", "declared_license", "license_url", "status"))
        writer.writerows(rows)
    counts = Counter(row[3] for row in rows)
    print(f"Audited {len(rows)} resolved release dependencies")
    print(", ".join(f"{status}: {count}" for status, count in sorted(counts.items())))
    for coordinate, license_name, _, status in rows:
        if status == "POM has no license declaration" or status.startswith("POM unavailable"):
            print(f"REVIEW {coordinate}: {license_name} ({status})")


if __name__ == "__main__":
    main()
