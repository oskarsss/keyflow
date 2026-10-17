#!/usr/bin/env python3
"""Build signed release downloads using CI secrets or the ignored local signing key."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="Release tag; must match the app version and checked-out commit")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    artifacts = root / "artifacts"
    artifacts.mkdir(exist_ok=True)
    version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
    if args.tag:
        if args.tag != f"v{version}":
            raise SystemExit("Release tag must match versionName in app/build.gradle.kts")
        tagged = subprocess.check_output(["git", "rev-parse", f"{args.tag}^{{commit}}"], cwd=root).strip()
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).strip()
        if tagged != head:
            raise SystemExit("The release tag must point to the checked-out commit")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=root).strip():
        raise SystemExit("Commit all changes before building release downloads")

    environment = os.environ.copy()
    signing = ("KEYFLOW_KEYSTORE", "KEYFLOW_STORE_PASSWORD", "KEYFLOW_KEY_PASSWORD")
    if any(name in environment for name in signing):
        if not all(environment.get(name) for name in signing):
            raise SystemExit("Set KEYFLOW_KEYSTORE, KEYFLOW_STORE_PASSWORD, and KEYFLOW_KEY_PASSWORD together")
        if not Path(environment["KEYFLOW_KEYSTORE"]).is_file():
            raise SystemExit("KEYFLOW_KEYSTORE does not point to a signing key")
    else:
        key = artifacts / "keyflow-release.jks"
        password_file = artifacts / "release-password"
        if not key.is_file() or not password_file.is_file():
            raise SystemExit("Local signing key missing. See README.md for release signing environment variables.")
        password = password_file.read_text().strip()
        environment.update(KEYFLOW_KEYSTORE=str(key), KEYFLOW_STORE_PASSWORD=password, KEYFLOW_KEY_PASSWORD=password)

    subprocess.run([str(root / "gradlew"), ":app:assembleRelease", "--console=plain"],
                   cwd=root, env=environment, check=True)
    # Phones get the plain name; the emulator build is marked.
    outputs = root / "app/build/outputs/apk/release"
    apk = artifacts / f"keyflow-{version}.apk"
    emulator = artifacts / f"keyflow-{version}-x86_64.apk"
    shutil.copyfile(outputs / "app-arm64-v8a-release.apk", apk)
    shutil.copyfile(outputs / "app-x86_64-release.apk", emulator)
    source = artifacts / f"keyflow-{version}-source.tar.gz"
    subprocess.run(["git", "archive", "--format=tar.gz", f"--prefix=keyflow-{version}/",
                    f"--output={source}", "HEAD"], cwd=root, check=True)
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in (apk, emulator, source))
    (artifacts / "SHA256SUMS").write_text(checksums)
    print(f"Signed APKs: {apk}, {emulator}")
    print(f"Source archive: {source}")
    print(checksums, end="")


if __name__ == "__main__":
    main()
