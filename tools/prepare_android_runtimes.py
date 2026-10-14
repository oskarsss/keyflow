#!/usr/bin/env python3
"""Extract verified, official YTDLnis native runtime bundles for Android builds."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIMES = {
    "arm64-v8a": "aa7b78d52575c886e4639ab319c7cd6cbada7543a31c4e523b4237cebb0b43e7",
    "x86_64": "ce58f841f57d76d2c16e60299dd6350aeb1ac8e2fc44eb7b4531024f2f3739e8",
}
FILES = {"libpython.so", "libpython.zip.so", "libffmpeg.so", "libffmpeg.zip.so", "libffprobe.so", "libqjs.so", "libtermux.so"}


def prepare():
    cache = ROOT / "artifacts/upstream"
    cache.mkdir(parents=True, exist_ok=True)
    for abi, digest in RUNTIMES.items():
        apk = cache / f"ytdlnis-{abi}.apk"
        if not apk.exists():
            url = f"https://github.com/deniscerri/ytdlnis/releases/download/v1.9.0/YTDLnis-1.9.0-{abi}-github-release.apk"
            print(f"Fetching official YTDLnis runtime bundle ({abi})", flush=True)
            urllib.request.urlretrieve(url, apk)
        if hashlib.sha256(apk.read_bytes()).hexdigest() != digest:
            apk.unlink()
            raise ValueError(f"YTDLnis bundle checksum mismatch: {abi}")
        output = ROOT / "app/src/main/jniLibs" / abi
        output.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(apk) as archive:
            for name in FILES:
                (output / name).write_bytes(archive.read(f"lib/{abi}/{name}"))
        print(f"Verified and prepared {abi}", flush=True)


if __name__ == "__main__":
    prepare()
