#!/usr/bin/env python3
"""Verify or restore the pinned MIT-licensed Beat This! model asset. No inference-time downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/rhythm'


def valid(path, manifest):
    return path.is_file() and path.stat().st_size == manifest['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == manifest['sha256']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Only verify; never download')
    args = parser.parse_args()
    manifest = json.loads((ASSETS / 'model.json').read_text())
    target = ASSETS / 'beat-this-final0-int8.onnx'
    if not valid(target, manifest):
        if args.check:
            raise SystemExit('The rhythm model is missing or its checksum does not match model.json')
        url = manifest['export'].replace('https://github.com/', 'https://raw.githubusercontent.com/').replace('/blob/', '/')
        temporary = target.with_suffix('.part')
        try:
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                import shutil
                shutil.copyfileobj(response, output)
            if not valid(temporary, manifest):
                raise SystemExit('Downloaded rhythm model failed checksum verification')
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    print('Beat This! final0 model checksum verified')


if __name__ == '__main__':
    main()
