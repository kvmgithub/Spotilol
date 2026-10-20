"""Checks for ui_release_notes.py; run with python3 .github/scripts/test_ui_release_notes.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ui_release_notes as notes  # noqa: E402

DOC = """# Title

Intro text.

## Unveröffentlicht

### Neu
- next thing

## 0.2.1010 – 2026-10-20

- merged

## 0.1.1004 – 2026-10-19

- first
"""

assert notes.unreleased(DOC) == "### Neu\n- next thing"
assert notes.unreleased(DOC.replace("## Unveröffentlicht\n\n### Neu\n- next thing\n\n", "")) == ""
assert notes.versioned(DOC, "0.2.1010") == "- merged"
assert notes.versioned(DOC, "0.1.1004") == "- first"
assert notes.versioned(DOC, "0.1.9999") is None
assert [h for h, _ in notes.sections(DOC)][1:] == ["0.2.1010 – 2026-10-20", "0.1.1004 – 2026-10-19"]

marked = notes.mark(DOC, "0.2.1011", "2026-10-21", "### Neu\n- next thing\n")
assert marked is not None and "## 0.2.1011 – 2026-10-21\n\n### Neu" in marked and "Unveröffentlicht" not in marked
assert notes.versioned(marked, "0.2.1011") == "### Neu\n- next thing"
# A newer unreleased section pushed meanwhile is never renamed under the old notes.
assert notes.mark(DOC, "0.2.1011", "2026-10-21", "### Neu\n- other thing") is None

body = notes.compose("- merged", "0.2.1010", "18106341b4f4b5b1")
assert body.startswith("- merged\n\n---\nSpotilol UI 0.2.1010 · Commit `1810634`")

print("release notes checks passed")
