"""Compare public app-signer fingerprints emitted by Android apksigner."""

from pathlib import Path
import re
import sys


SIGNER = re.compile(
    r'(?:Signer #\d+|V[1-4](?:\.\d+)? Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})'
)


def signer_certificates(report):
    return {match.group(1).lower() for line in report.splitlines()
            if (match := SIGNER.fullmatch(line.strip()))}


def verify_identity(previous, current):
    old, new = signer_certificates(previous), signer_certificates(current)
    if not old or not new:
        raise ValueError('Cannot read signing certificate fingerprint from apksigner output')
    if old != new:
        raise ValueError('Signing certificate changed')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: apk_identity.py previous-report current-report')
    try:
        verify_identity(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except ValueError as error:
        raise SystemExit(str(error))
    print('APK signing identity matches pinned release')
