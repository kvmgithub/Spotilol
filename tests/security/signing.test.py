import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().parents[2] / '.github/scripts/apk_identity.py'
spec = importlib.util.spec_from_file_location('apk_identity', SCRIPT)
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)

CERT = '73cf309a74b1a107a1e1cbb9453806f7b0d8e803957ecd371cb51cfed0b21e9a'
OTHER = 'a' * 64


class SigningIdentityTest(unittest.TestCase):
    def test_legacy_output(self):
        self.assertEqual({CERT}, identity.signer_certificates(f'Signer #1 certificate SHA-256 digest: {CERT}\n'))

    def test_build_tools_37_output(self):
        self.assertEqual({CERT}, identity.signer_certificates(f'V2 Signer: certificate SHA-256 digest: {CERT}\n'))

    def test_same_certificate_across_signature_schemes(self):
        old = f'Signer #1 certificate SHA-256 digest: {CERT}\n'
        new = f'V2 Signer: certificate SHA-256 digest: {CERT.upper()}\nV3.1 Signer: certificate SHA-256 digest: {CERT}\n'
        identity.verify_identity(old, new)

    def test_source_stamp_does_not_identify_the_app_signer(self):
        report = f'Source Stamp Signer: certificate SHA-256 digest: {OTHER}\nV2 Signer: certificate SHA-256 digest: {CERT}\n'
        self.assertEqual({CERT}, identity.signer_certificates(report))

    def test_rejects_a_different_signing_certificate(self):
        with self.assertRaisesRegex(ValueError, 'changed'):
            identity.verify_identity(f'V2 Signer: certificate SHA-256 digest: {CERT}\n', f'V2 Signer: certificate SHA-256 digest: {OTHER}\n')

    def test_empty_or_unrecognized_output_is_not_a_match(self):
        for old, new in [('', ''), ('Unrecognized format', f'V2 Signer: certificate SHA-256 digest: {CERT}\n')]:
            with self.assertRaisesRegex(ValueError, 'read'):
                identity.verify_identity(old, new)


if __name__ == '__main__':
    unittest.main()
