#!/usr/bin/env python3
"""Release notes of Spotilol UI, kept in CHANGELOG-UI.md (newest first).

  unreleased CHANGELOG                  the "## Unveröffentlicht" section; exit 1 when missing or empty
  compose SECTION_FILE VERSION SHA      a release body: the section plus the standard footer
  body CHANGELOG VERSION SHA            the body of an already published VERSION
  versions CHANGELOG                    the versions that have a section
  mark CHANGELOG VERSION DATE NOTES     renames the unreleased section holding NOTES to "VERSION – DATE";
                                        exit 2 when no such section exists (nothing to commit)
"""
import re
import sys
from pathlib import Path

UNRELEASED = "Unveröffentlicht"
VERSION = re.compile(r"^(\d+\.\d+\.\d+)\s+–\s+\d{4}-\d{2}-\d{2}$")


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


def unreleased(text):
    return next((body for heading, body in sections(text) if heading == UNRELEASED), "")


def versioned(text, version):
    for heading, body in sections(text):
        match = VERSION.match(heading)
        if match and match.group(1) == version:
            return body
    return None


def compose(section, version, sha):
    return (
        f"{section.strip()}\n\n---\n"
        f"Spotilol UI {version} · Commit `{sha[:7]}` · Paket `com.project.lol.fork.ui`, installiert sich neben Spotilol Lite.\n"
        "Pre-Release: Spotilol Lite meldet es nicht als Update. Automatisch geprüft (Tests, signierter Build); "
        "Gerätetests sind davon getrennt. Prüfsummen in `SHA256SUMS.txt`.\n"
    )


def mark(text, version, date, notes):
    """The text with the unreleased section holding [notes] renamed, or None."""
    wanted = notes.strip()
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.rstrip("\n") != f"## {UNRELEASED}":
            continue
        end = next((j for j in range(i + 1, len(lines)) if lines[j].startswith("## ")), len(lines))
        if "".join(lines[i + 1:end]).strip() == wanted:
            lines[i] = f"## {version} – {date}\n"
            return "".join(lines)
    return None


def main(argv):
    command, args = argv[1], argv[2:]
    if command == "unreleased":
        section = unreleased(Path(args[0]).read_text())
        if not section:
            sys.exit(f"CHANGELOG-UI.md has no '## {UNRELEASED}' section with notes for this release")
        print(section)
    elif command == "compose":
        print(compose(Path(args[0]).read_text(), args[1], args[2]), end="")
    elif command == "body":
        section = versioned(Path(args[0]).read_text(), args[1])
        if section is None:
            sys.exit(f"no section for {args[1]}")
        print(compose(section, args[1], args[2]), end="")
    elif command == "versions":
        for heading, _ in sections(Path(args[0]).read_text()):
            match = VERSION.match(heading)
            if match:
                print(match.group(1))
    elif command == "mark":
        path = Path(args[0])
        result = mark(path.read_text(), args[1], args[2], Path(args[3]).read_text())
        if result is None:
            sys.exit(2)
        path.write_text(result)
    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main(sys.argv)
