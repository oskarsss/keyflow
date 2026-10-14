#!/usr/bin/env python3
"""Build a signed release using the local, Git-ignored Keyflow signing key."""
import hashlib
import os
import re
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
artifacts = root / "artifacts"
key = artifacts / "keyflow-release.jks"
password_file = artifacts / "release-password"
if not key.exists() or not password_file.exists():
    raise SystemExit("Local signing key missing. See README.md for release signing environment variables.")
password = password_file.read_text().strip()
environment = os.environ.copy()
environment.update(KEYFLOW_KEYSTORE=str(key), KEYFLOW_STORE_PASSWORD=password, KEYFLOW_KEY_PASSWORD=password)
subprocess.run([str(root / "gradlew"), ":app:assembleRelease", "--console=plain"], cwd=root, env=environment, check=True)
version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
apk = artifacts / f"keyflow-{version}.apk"
shutil.copyfile(root / "app/build/outputs/apk/release/app-release.apk", apk)
checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
(artifacts / "SHA256SUMS").write_text(f"{checksum}  {apk.name}\n")
print(f"Signed APK: {apk}")
print(f"SHA-256: {checksum}")
