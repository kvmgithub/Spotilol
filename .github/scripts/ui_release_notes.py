#!/usr/bin/env python3
"""Versions and release notes of MySpotilol, kept in CHANGELOG-UI.md (Keep a Changelog, newest first).

  latest CHANGELOG               the newest version; fails unless versions only go down the file
  code VERSION                   the Android versionCode of a SemVer version (MAJOR*1e6 + MINOR*1e3 + PATCH)
  body CHANGELOG VERSION SHA     the release text of VERSION: its section plus the standard footer
  versions CHANGELOG             every version that has a section
"""
import re
import sys
from pathlib import Path

VERSION = re.compile(r"^\[(\d+)\.(\d+)\.(\d+)\] - \d{4}-\d{2}-\d{2}$")


def sections(text):
    """(heading, body) for every "## " section, in file order."""
    out, heading, body = [], None, []
    for line in text.splitlines():
        if line.startswith("## "):
            if heading is not None:
                out.append((heading, "\n".join(body).strip()))
            heading, body = line[3:].strip(), []
        elif heading is not None:
            body.append(line)
    if heading is not None:
        out.append((heading, "\n".join(body).strip()))
    return out


def versions(text):
    """(version tuple, body) for every version section, in file order."""
    out = []
    for heading, body in sections(text):
        match = VERSION.match(heading)
        if match:
            out.append((tuple(int(n) for n in match.groups()), body))
        elif heading != "[Unreleased]":
            raise ValueError(f"unexpected heading '## {heading}': use '## [X.Y.Z] - YYYY-MM-DD' or '## [Unreleased]'")
    return out


def name(version):
    return ".".join(map(str, version))


def latest(text):
    found = versions(text)
    if not found:
        raise ValueError("no version section")
    for newer, older in zip(found, found[1:]):
        if newer[0] <= older[0]:
            raise ValueError(f"{name(newer[0])} is listed above {name(older[0])}: newest first, each version higher")
    if not found[0][1]:
        raise ValueError(f"{name(found[0][0])} has no notes")
    return name(found[0][0])


def code(version):
    major, minor, patch = (int(n) for n in version.split("."))
    # ponytail: minor and patch below 1000; the legacy 0.x.10xx builds kept their own codes (1004-1010).
    if minor >= 1000 or patch >= 1000:
        raise ValueError(f"{version}: minor and patch must stay below 1000")
    return major * 1_000_000 + minor * 1_000 + patch


def body(text, version, sha):
    section = next((b for v, b in versions(text) if name(v) == version), None)
    if section is None:
        raise ValueError(f"no section for {version}")
    return (
        f"{section}\n\n---\n"
        f"Commit `{sha[:7]}` · package `com.project.lol.fork.ui`, installs next to Spotilol Lite.\n"
        "Pre-release: Spotilol Lite does not offer it as an update. Checked automatically (tests, signed build); "
        "device testing is separate. Checksums in `SHA256SUMS.txt`.\n"
    )


def main(argv):
    command, args = argv[1], argv[2:]
    try:
        if command == "latest":
            print(latest(Path(args[0]).read_text()))
        elif command == "code":
            print(code(args[0]))
        elif command == "body":
            print(body(Path(args[0]).read_text(), args[1], args[2]), end="")
        elif command == "versions":
            for version, _ in versions(Path(args[0]).read_text()):
                print(name(version))
        else:
            sys.exit(f"unknown command {command}")
    except ValueError as error:
        sys.exit(f"CHANGELOG-UI.md: {error}")


if __name__ == "__main__":
    main(sys.argv)
