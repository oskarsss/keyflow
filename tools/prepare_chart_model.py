#!/usr/bin/env python3
"""Verify committed chart-model assets, or reproduce them from the pinned human-trained model."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/chart'
SOURCE_REVISION = '0e2b0e387aab4b35c64b0b11b12d47578dea7587'
MODEL_REVISION = '7807f0dc70cab671be012e1f5ddf945b0b8b7278'


def verify(directory: Path):
    manifest = json.loads((directory / 'model.json').read_text())
    if manifest['revision'] != MODEL_REVISION or manifest['upstream_revision'] != SOURCE_REVISION:
        raise SystemExit('Unexpected chart model/source revision in manifest')
    for name, info in manifest['files'].items():
        if Path(name).name != name:
            raise SystemExit('Invalid model asset name')
        path = directory / name
        if not path.is_file() or path.stat().st_size != info['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != info['sha256']:
            raise SystemExit(f'Chart asset checksum mismatch: {name}')
    print('Human-trained chart model checksums verified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify only; never download or export')
    parser.add_argument('--export', action='store_true', help='Download pinned source/weights and reproduce ONNX graphs')
    parser.add_argument('--work', type=Path, default=ROOT / 'artifacts/chart-learning')
    args = parser.parse_args()
    if args.check and args.export:
        parser.error('--check and --export are mutually exclusive')
    if args.export:
        from huggingface_hub import snapshot_download
        work = args.work.resolve(); work.mkdir(parents=True, exist_ok=True)
        source = work / 'mapperatorinator'
        if not source.exists():
            source.mkdir()
            subprocess.run(['git', 'init', str(source)], check=True)
            subprocess.run(['git', '-C', str(source), 'remote', 'add', 'origin', 'https://github.com/OliBomby/Mapperatorinator.git'], check=True)
            subprocess.run(['git', '-C', str(source), 'fetch', '--depth', '1', 'origin', SOURCE_REVISION], check=True)
            subprocess.run(['git', '-C', str(source), 'checkout', '--detach', 'FETCH_HEAD'], check=True)
        actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != SOURCE_REVISION:
            raise SystemExit(f'Use the pinned source revision in {source}; the script will not change an existing checkout.')
        if subprocess.run(['git', '-C', str(source), 'diff', '--quiet', 'HEAD']).returncode:
            raise SystemExit('The upstream checkout has modified source. Review it before reproducing the model.')
        checkpoint = work / 'checkpoint'
        snapshot_download('OliBomby/Mapperatorinator-v32-mini', revision=MODEL_REVISION,
                          allow_patterns=['gamemode=3/*', 'README.md'], local_dir=str(checkpoint))
        subprocess.run([sys.executable, '-m', 'tools.chart_model.export', '--upstream', str(source),
                        '--checkpoint', str(checkpoint / 'gamemode=3'), '--output', str(work / 'export')], cwd=ROOT, check=True)
        print(f'Exported under {work / "export"}. Review validation before replacing the committed assets.')
    else:
        verify(ASSETS)


if __name__ == '__main__':
    main()
