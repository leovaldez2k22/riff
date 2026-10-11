#!/usr/bin/env python3
"""Checks, in a shrunk release build, the one field Riff replaces by name to keep the solver out of process.

CipherBridgeInstaller sets InnerTubeExtractor.cipherService by reflection. R8 may rename the class and the
field's type, because the code names both with class literals. It must not rename or remove the field, or
the bridge cannot be installed and online playback is refused (it is never solved in the playing process).

Usage: check_solver_seam.py --mapping app/build/outputs/mapping/release/mapping.txt
Exit 0 when the seam is intact, 1 when it is not, 2 when the mapping cannot be read.
"""
import argparse
import re
import sys

OWNER = "com.metrolist.innertubex.extraction.InnerTubeExtractor"
FIELD_TYPE = "com.metrolist.innertubex.extraction.ExtractionCipherService"
FIELD = "cipherService"
SERVICE = "com.musicglass.personal.SolverService"
# R8 merges the bridge into another class; what must survive is its code.
BRIDGE_METHOD = "com.musicglass.personal.CipherBridgeInstaller$Bridge.processFormats("


def check(mapping_text):
    """Returns a list of problems; empty means the seam is intact."""
    classes, members, current = {}, {}, None
    for line in mapping_text.splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            match = re.fullmatch(r"(\S+) -> (\S+):", line)
            current = match[1] if match else None
            if match:
                classes[match[1]] = match[2]
                members[match[1]] = []
        elif current is not None:
            members[current].append(line.strip())
    problems = []
    if OWNER not in classes:
        return ["%s is not in the release build" % OWNER]
    fields = [m for m in members[OWNER] if "(" not in m and re.fullmatch(r"\S+ \S+ -> \S+", m)]
    kept = [m for m in fields if m.split(" ")[1] == FIELD]
    if not kept:
        problems.append("field %s was removed from %s" % (FIELD, OWNER))
    for entry in kept:
        kind, _, _, renamed = entry.split(" ")
        if renamed != FIELD:
            problems.append("field %s was renamed to %s" % (FIELD, renamed))
        if kind != FIELD_TYPE:
            problems.append("field %s has type %s" % (FIELD, kind))
    if FIELD_TYPE not in classes:
        problems.append("%s is not in the release build" % FIELD_TYPE)
    if BRIDGE_METHOD not in mapping_text and "com.musicglass.personal.CipherBridgeInstaller$Bridge ->" not in mapping_text:
        problems.append("the bridge to the solver process is not in the release build")
    # The solver service is started by name from the manifest, so its name must survive.
    if SERVICE not in classes:
        problems.append("%s is not in the release build" % SERVICE)
    elif classes[SERVICE] != SERVICE:
        problems.append("SolverService was renamed to %s" % classes[SERVICE])
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mapping", required=True)
    args = parser.parse_args(argv)
    try:
        with open(args.mapping, encoding="utf-8") as handle:
            text = handle.read()
    except OSError as error:
        print("BLOCKED mapping not readable: %s" % error.strerror)
        return 2
    problems = check(text)
    for problem in problems:
        print("FAIL " + problem)
    if not problems:
        print("PASS %s.%s keeps its name and type in the shrunk release" % (OWNER, FIELD))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
