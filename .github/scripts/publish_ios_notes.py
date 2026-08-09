#!/usr/bin/env python3
"""Publish a release's store notes to TestFlight and, when one is open, the App Store.

`xcrun altool --upload-app` uploads a binary and nothing else -- neither
TestFlight's "What to Test" nor the App Store's "What's New" can be set that way,
so this runs after it and writes both through the App Store Connect API:

  * betaBuildLocalizations.whatsNew        -> TestFlight "What to Test", on the
                                              build just uploaded. Load-bearing
                                              today; iOS ships only to TestFlight.
  * appStoreVersionLocalizations.whatsNew  -> App Store "What's New", only when an
                                              editable App Store version for this
                                              release already exists. A no-op
                                              today, so the first App Store
                                              submission is a configuration change
                                              rather than a code change. (Apple
                                              also rejects whatsNew on a version
                                              1.0, which the skip covers.)

Python rather than bash, against the convention that release decisions live in
portable shell (see specs/release/design.md): the ASC API needs an ES256 JWT
assertion, and that is the one step openssl does badly -- it emits a DER-encoded
signature that has to be unpacked into raw R||S before it is a valid JOSE
signature. No *decisions* live here; the text was chosen by whoever wrote
CHANGELOG.md and extracted by release_notes.sh. This is transport.

Standalone and idempotent on purpose. If the step fails after the binary is
already on TestFlight -- ASC ingestion is asynchronous, so the poll below can time
out on a slow day -- the recovery is to run this again against the same tag once
the build appears. Nothing is re-uploaded and no duplicate build number is ever
risked:

    ASC_KEY_ID=... ASC_ISSUER_ID=... \\
      python3 .github/scripts/publish_ios_notes.py \\
        --tag v0.0.8 --build-number 8 --notes-file notes.txt

Needs `pyjwt[crypto]`; everything else is stdlib.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import jwt

API = "https://api.appstoreconnect.apple.com"
LOCALE = "en-US"

# How long to wait for App Store Connect to ingest the upload. The build *record*
# appears well before processing finishes -- betaBuildLocalizations attach to the
# resource, not to a processed binary -- so this is minutes, not the full
# processing time.
POLL_INTERVAL_S = 30
POLL_TIMEOUT_S = 20 * 60

# States in which an App Store version still accepts metadata edits. Deliberately
# conservative: WAITING_FOR_REVIEW is left out because editing there means pulling
# the build from review, which is not something a publish job should do on its own.
EDITABLE_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}


def token(key_id: str, issuer_id: str) -> str:
    """Mint a short-lived ES256 assertion. Apple caps the lifetime at 20 minutes."""
    key_path = Path.home() / ".appstoreconnect" / "private_keys" / f"AuthKey_{key_id}.p8"
    if not key_path.is_file():
        sys.exit(f"No ASC private key at {key_path}")
    now = int(time.time())
    return jwt.encode(
        {"iss": issuer_id, "iat": now, "exp": now + 20 * 60, "aud": "appstoreconnect-v1"},
        key_path.read_text(),
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
    )


def request(method: str, path: str, bearer: str, body: dict | None = None) -> dict:
    url = path if path.startswith("http") else f"{API}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {bearer}")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        # Apple's errors carry the actual reason in the body; the status alone is
        # rarely enough to tell a permissions problem from a malformed payload.
        detail = e.read().decode(errors="replace")
        sys.exit(f"{method} {url} -> HTTP {e.code}\n{detail}")


def find_app(bearer: str, bundle_id: str) -> str:
    q = urllib.parse.urlencode({"filter[bundleId]": bundle_id})
    data = request("GET", f"/v1/apps?{q}", bearer)["data"]
    if not data:
        sys.exit(f"No app with bundle id {bundle_id} is visible to this API key")
    return data[0]["id"]


def wait_for_build(bearer: str, app_id: str, version: str, build_number: str) -> str:
    """Poll until the uploaded build's record exists. Identified by the pair the
    pipeline stamps: marketing version X.Y.Z and CURRENT_PROJECT_VERSION."""
    q = urllib.parse.urlencode(
        {
            "filter[app]": app_id,
            "filter[preReleaseVersion.version]": version,
            "filter[version]": build_number,
        }
    )
    deadline = time.time() + POLL_TIMEOUT_S
    while True:
        data = request("GET", f"/v1/builds?{q}", bearer)["data"]
        if data:
            return data[0]["id"]
        if time.time() >= deadline:
            sys.exit(
                f"App Store Connect still has no build {version} ({build_number}) after "
                f"{POLL_TIMEOUT_S // 60} minutes.\n"
                "The binary uploaded fine -- only the notes are missing. Re-run this "
                "script against the same tag once the build appears; it is idempotent "
                "and re-uploads nothing."
            )
        print(f"waiting for build {version} ({build_number}) to appear...", flush=True)
        time.sleep(POLL_INTERVAL_S)


def set_testflight_notes(bearer: str, build_id: str, notes: str) -> None:
    existing = request("GET", f"/v1/builds/{build_id}/betaBuildLocalizations", bearer)["data"]
    mine = next((r for r in existing if r["attributes"]["locale"] == LOCALE), None)
    if mine:
        request(
            "PATCH",
            f"/v1/betaBuildLocalizations/{mine['id']}",
            bearer,
            {
                "data": {
                    "type": "betaBuildLocalizations",
                    "id": mine["id"],
                    "attributes": {"whatsNew": notes},
                }
            },
        )
        print(f"TestFlight What to Test: updated ({LOCALE})")
    else:
        request(
            "POST",
            "/v1/betaBuildLocalizations",
            bearer,
            {
                "data": {
                    "type": "betaBuildLocalizations",
                    "attributes": {"locale": LOCALE, "whatsNew": notes},
                    "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
                }
            },
        )
        print(f"TestFlight What to Test: created ({LOCALE})")


def set_app_store_notes(bearer: str, app_id: str, version: str, notes: str) -> None:
    q = urllib.parse.urlencode({"filter[versionString]": version})
    versions = request("GET", f"/v1/apps/{app_id}/appStoreVersions?{q}", bearer)["data"]
    if not versions:
        print(f"App Store What's New: skipped, no App Store version {version} exists")
        return

    v = versions[0]
    attrs = v["attributes"]
    state = attrs.get("appVersionState") or attrs.get("appStoreState")
    if state not in EDITABLE_STATES:
        print(f"App Store What's New: skipped, version {version} is {state} (not editable)")
        return

    locs = request("GET", f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations", bearer)
    mine = next((r for r in locs["data"] if r["attributes"]["locale"] == LOCALE), None)
    if not mine:
        print(f"App Store What's New: skipped, version {version} has no {LOCALE} localization")
        return

    request(
        "PATCH",
        f"/v1/appStoreVersionLocalizations/{mine['id']}",
        bearer,
        {
            "data": {
                "type": "appStoreVersionLocalizations",
                "id": mine["id"],
                "attributes": {"whatsNew": notes},
            }
        },
    )
    print(f"App Store What's New: updated ({LOCALE})")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--tag", required=True, help="the v* tag being published, e.g. v0.0.8")
    p.add_argument(
        "--build-number",
        required=True,
        help="CURRENT_PROJECT_VERSION for this build (the Android versionCode). Passed in "
        "rather than recomputed here, so the formula lives only in version_lib.sh.",
    )
    p.add_argument("--notes-file", required=True, type=Path)
    p.add_argument("--bundle-id", default="app.azula")
    args = p.parse_args()

    if not args.tag.startswith("v"):
        sys.exit(f"Not a release tag: '{args.tag}' (expected a v* tag such as v1.2.3)")
    version = args.tag[1:]

    # Read as bytes and decode without touching line endings or trailing
    # whitespace: this text shipped to Play verbatim and must reach Apple as the
    # same bytes, or the two stores show subtly different notes.
    notes = args.notes_file.read_bytes().decode("utf-8")
    if not notes.strip():
        sys.exit(f"{args.notes_file} is empty -- nothing to publish")

    key_id = os.environ.get("ASC_KEY_ID", "")
    issuer_id = os.environ.get("ASC_ISSUER_ID", "")
    if not key_id or not issuer_id:
        sys.exit("ASC_KEY_ID and ASC_ISSUER_ID must both be set")

    bearer = token(key_id, issuer_id)
    app_id = find_app(bearer, args.bundle_id)
    build_id = wait_for_build(bearer, app_id, version, args.build_number)

    set_testflight_notes(bearer, build_id, notes)
    set_app_store_notes(bearer, app_id, version, notes)


if __name__ == "__main__":
    main()
