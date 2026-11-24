"""Which certificates scripts/revoke_runner_certificates.py may revoke."""
import base64
import hashlib
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import revoke_runner_certificates as revoke  # noqa: E402

RUNNER_DER = b"certificate made on this runner"
OTHER_DER = b"certificate made somewhere else"


def certificate(identifier, kind, name, der):
    return {"id": identifier, "attributes": {
        "certificateType": kind, "name": name, "certificateContent": base64.b64encode(der).decode()}}


CERTIFICATES = [
    certificate("runner", "DEVELOPMENT", "Apple Development: Created via API", RUNNER_DER),
    certificate("other-run", "DEVELOPMENT", "Apple Development: Created via API", OTHER_DER),
    certificate("mac", "DEVELOPMENT", "Apple Development: Pim van Dijk", OTHER_DER),
    certificate("distribution", "DISTRIBUTION", "Apple Distribution: Created via API", RUNNER_DER),
    certificate("old-style", "IOS_DEVELOPMENT", "iPhone Developer: Created via API", OTHER_DER),
]


def ids(certificates):
    return [c["id"] for c in certificates]


class FingerprintTests(unittest.TestCase):
    def test_matches_the_upper_case_sha1_security_prints(self):
        content = base64.b64encode(RUNNER_DER).decode()
        self.assertEqual(revoke.fingerprint(content), hashlib.sha1(RUNNER_DER).hexdigest().upper())


class RunnerCertificateTests(unittest.TestCase):
    def test_only_the_runners_own_development_certificate(self):
        runner = hashlib.sha1(RUNNER_DER).hexdigest()
        self.assertEqual(ids(revoke.certificates_to_revoke(CERTIFICATES, [runner])), ["runner"])

    def test_nothing_without_fingerprints(self):
        self.assertEqual(revoke.certificates_to_revoke(CERTIFICATES, []), [])


class StaleCertificateTests(unittest.TestCase):
    def test_every_api_created_development_certificate_and_nothing_else(self):
        self.assertEqual(ids(revoke.stale_certificates(CERTIFICATES)), ["runner", "other-run", "old-style"])


if __name__ == "__main__":
    unittest.main()
