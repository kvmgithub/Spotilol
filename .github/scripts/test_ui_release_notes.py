"""Checks for ui_release_notes.py; run with python3 .github/scripts/test_ui_release_notes.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ui_release_notes as notes  # noqa: E402

DOC = """# Title

Intro text.

## [Unreleased]

## [0.3.0] - 2026-10-20

### Changed
- renamed

## [0.2.1010] - 2026-10-20

- merged
"""


def fails(fn, *args):
    try:
        fn(*args)
    except ValueError:
        return True
    return False


assert notes.latest(DOC) == "0.3.0"
assert [notes.name(v) for v, _ in notes.versions(DOC)] == ["0.3.0", "0.2.1010"]
assert notes.code("0.3.0") == 3_000 and notes.code("0.3.1") == 3_001 and notes.code("1.0.0") == 1_000_000
assert notes.code("0.3.0") > 1010, "must upgrade the legacy 0.2.1010 build (versionCode 1010)"
assert fails(notes.code, "0.2.1010")
# Versions must go down the file, and the newest one needs notes.
assert fails(notes.latest, DOC.replace("[0.3.0]", "[0.2.999]"))
assert fails(notes.latest, DOC.replace("[0.3.0]", "[0.2.1010]"))
assert fails(notes.latest, DOC.replace("### Changed\n- renamed\n", ""))
assert fails(notes.latest, DOC.replace("## [0.3.0] - 2026-10-20", "## 0.3.0 – 2026-10-20"))
assert fails(notes.latest, "# Title\n\n## [Unreleased]\n\n- soon\n")

body = notes.body(DOC, "0.2.1010", "18106341b4f4b5b1")
assert body.startswith("- merged\n\n---\nCommit `1810634` · package `com.project.lol.fork.ui`")
assert fails(notes.body, DOC, "0.1.0", "abc")

print("release notes checks passed")
