#!/usr/bin/env python3
"""One-time Spotify PKCE bootstrap for the Glass Spotify Widget.

Glass has no keyboard, so authorization happens here on the laptop exactly once.
The resulting refresh token is pushed to the device and rotated in place from then on.

Usage:
    python3 tools/bootstrap_token.py <client-id>
"""

import base64
import hashlib
import http.server
import json
import secrets
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

REDIRECT_URI = "http://127.0.0.1:8888/callback"
PORT = 8888
SCOPES = "user-read-playback-state user-modify-playback-state"
OUTPUT = "tools/refresh_token.txt"

_received = {}
_expected_state = None


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global _expected_state

        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/callback":
            self.send_response(404)
            self.end_headers()
            return

        params = urllib.parse.parse_qs(parsed.query)
        returned_state = params.get("state", [None])[0]
        _received["code"] = params.get("code", [None])[0]
        _received["error"] = params.get("error", [None])[0]

        # Verify state parameter for CSRF protection
        if returned_state != _expected_state:
            _received["code"] = None
            _received["state_mismatch"] = True
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            body = "<h1>Failed.</h1><p>State mismatch: CSRF check failed.</p>"
            self.wfile.write(body.encode("utf-8"))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        if _received["code"]:
            body = "<h1>Authorized.</h1><p>You can close this tab.</p>"
        else:
            body = "<h1>Failed.</h1><p>%s</p>" % _received["error"]
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, fmt, *args):
        pass  # keep the console clean


def make_verifier():
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(64)).decode().rstrip("=")
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).decode().rstrip("=")
    return verifier, challenge


def main():
    global _expected_state

    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    client_id = sys.argv[1]

    verifier, challenge = make_verifier()
    state = secrets.token_urlsafe(16)
    _expected_state = state

    authorize_url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
        "code_challenge_method": "S256",
        "code_challenge": challenge,
        "state": state,
    })

    server = http.server.HTTPServer(("127.0.0.1", PORT), CallbackHandler)
    thread = threading.Thread(target=server.handle_request)
    thread.start()

    print("Opening browser. If it does not open, visit:\n\n%s\n" % authorize_url)
    webbrowser.open(authorize_url)
    thread.join(timeout=300)
    server.server_close()

    if _received.get("state_mismatch"):
        print("State mismatch: CSRF check failed. Authorization aborted.")
        return 1

    if not _received.get("code"):
        print("No authorization code received: %s" % _received.get("error"))
        return 1

    payload = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": _received["code"],
        "redirect_uri": REDIRECT_URI,
        "client_id": client_id,
        "code_verifier": verifier,
    }).encode("ascii")

    request = urllib.request.Request(
        "https://accounts.spotify.com/api/token",
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request) as response:
        token = json.loads(response.read().decode("utf-8"))

    refresh_token = token.get("refresh_token")
    if not refresh_token:
        error_msg = token.get("error") or token.get("error_description") or "unknown error"
        print("No refresh_token in response: %s" % error_msg)
        return 1

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write(refresh_token)

    print("\nRefresh token written to %s\n" % OUTPUT)
    print("Now push it to the Glass:\n")
    print("  adb -s 0123456789ABCDEF push %s /data/local/tmp/spotify_bootstrap_token"
          % OUTPUT)
    print("\nThen launch Spotify from the Glass launcher. The app imports the token")
    print("into its private storage on first run and deletes the pushed copy.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
