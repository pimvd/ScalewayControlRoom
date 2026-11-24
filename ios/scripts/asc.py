"""A minimal App Store Connect API client: an ES256 token and JSON:API calls.

Shared by the scripts that sync App Store Connect from AppStore/. Needs the
`cryptography` package for signing the token.
"""
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.appstoreconnect.apple.com"


class SyncError(Exception):
    pass


class NotFound(Exception):
    pass


def token_claims(key_id, issuer_id, now=None):
    now = int(now if now is not None else time.time())
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    payload = {"iss": issuer_id, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}
    return header, payload


def make_token(key_id, issuer_id, key_path):
    """An ES256 JSON Web Token for the App Store Connect API."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    def encode(data):
        raw = data if isinstance(data, bytes) else json.dumps(data, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    header, payload = token_claims(key_id, issuer_id)
    signing_input = encode(header) + b"." + encode(payload)
    key = serialization.load_pem_private_key(Path(key_path).read_bytes(), password=None)
    r, s = decode_dss_signature(key.sign(signing_input, ec.ECDSA(hashes.SHA256())))
    signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return (signing_input + b"." + encode(signature)).decode()


class AppStoreConnect:
    """A minimal JSON:API client. A fresh token per call keeps long runs valid."""

    def __init__(self, token):
        self.token = token

    def request(self, method, path, params=None, body=None):
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token()}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404 and method == "GET":
                raise NotFound(path) from error
            raise SyncError(f"{method} {path}: HTTP {error.code} {error.read().decode(errors='replace')}") from error
        return json.loads(raw) if raw else {}

    def upload(self, operation, chunk):
        headers = {h["name"]: h["value"] for h in operation.get("requestHeaders", [])}
        request = urllib.request.Request(operation["url"], data=chunk, method=operation["method"], headers=headers)
        with urllib.request.urlopen(request, timeout=120):
            pass


def client_from_environment():
    """A client for the key in ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH."""
    missing = [name for name in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_PATH") if not os.environ.get(name)]
    if missing:
        raise SyncError(f"missing environment variable(s): {', '.join(missing)}")
    return AppStoreConnect(lambda: make_token(
        os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], os.environ["ASC_KEY_PATH"]))
