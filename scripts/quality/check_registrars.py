#!/usr/bin/env python3
"""Checks, in a shrunk release build, that every ML Kit and Firebase component registrar can still be created.

ML Kit finds its parts (language identification, translation) through registrars named in the manifest and
creates each one by reflection through its public no-argument constructor. Q18-072: R8 kept the registrar
classes by name but removed those constructors, no registrar was made, and translation failed in release
builds only. app/proguard-rules.pro keeps them; this check proves it on the build itself.

Usage: check_registrars.py --mapping app/build/outputs/mapping/release/mapping.txt
                           --usage app/build/outputs/mapping/release/usage.txt
Exit 0 when every registrar keeps its constructor, 1 when one does not, 2 when a file cannot be read.
"""
import argparse
import re
import sys

PACKAGES = ("com.google.mlkit.", "com.google.firebase.")
# Classes that carry the word but are not registrars to create.
NOT_REGISTRARS = {"com.google.firebase.components.ComponentRegistrar", "com.google.firebase.components.ComponentRegistrarProcessor"}


def registrars(mapping_text):
    """The registrar classes in the build, by their original names."""
    found = []
    for line in mapping_text.splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match and match[1].startswith(PACKAGES) and match[1].endswith("Registrar") and match[1] not in NOT_REGISTRARS:
            found.append(match[1])
    return found


def removed_constructors(usage_text):
    """Classes whose public no-argument constructor R8 removed, from usage.txt (removed members under each class)."""
    removed, current = set(), None
    for line in usage_text.splitlines():
        if not line.strip():
            continue
        if not line.startswith(" "):
            current = line.rstrip(":").strip()
        elif current is not None and line.strip() == "public void <init>()":
            removed.add(current)
    return removed


def check(mapping_text, usage_text):
    """Returns a list of problems; empty means every registrar can be created."""
    found = registrars(mapping_text)
    if not any(name.startswith("com.google.mlkit.") for name in found):
        return ["no ML Kit registrar is in the release build"]
    removed = removed_constructors(usage_text)
    return ["%s lost its public no-argument constructor" % name for name in found if name in removed]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mapping", required=True)
    parser.add_argument("--usage", required=True)
    args = parser.parse_args(argv)
    try:
        with open(args.mapping, encoding="utf-8") as handle:
            mapping = handle.read()
        with open(args.usage, encoding="utf-8") as handle:
            usage = handle.read()
    except OSError as error:
        print("cannot read: %s" % error)
        return 2
    problems = check(mapping, usage)
    for problem in problems:
        print("FAIL: " + problem)
    if not problems:
        print("PASS: %d registrars keep their constructors" % len(registrars(mapping)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
