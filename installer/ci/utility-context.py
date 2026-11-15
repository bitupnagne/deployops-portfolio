#!/usr/bin/env python3
"""Prepare candidate utility inputs; no pull, package, build or credentials."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import sys


SOURCES = {
    'Dockerfile': 'Dockerfile.utility',
    '.dockerignore': 'Dockerfile.utility.dockerignore',
    'maven_seed.py': 'maven_seed.py',
    'nexus_seed_import.py': 'nexus_seed_import.py',
    'utility-check.py': 'utility-check.py',
    'utility-entrypoint.py': 'utility-entrypoint.py',
}


def prepare(output: Path, base_image: str) -> str:
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}', base_image):
        raise ValueError('An independently approved Python base repository@sha256 digest is required')
    source = Path(__file__).resolve().parent
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError('Output must be a new directory')
    if output.resolve().is_relative_to(source.parents[1]):
        raise ValueError('Output must be outside the deploy-system worktree')
    for parent in output.parents:
        if parent.is_symlink():
            raise ValueError('Symlink output parent is forbidden')
    inputs = {}
    for target, original in SOURCES.items():
        path = source / original
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError(f'A regular source file is required: {original}')
        inputs[target] = path.read_bytes()
    inputs['BASE_IMAGE'] = (base_image + '\n').encode('ascii')
    output.mkdir(parents=True)
    for name, content in inputs.items():
        (output / name).write_bytes(content)
    lock = {'schemaVersion': 1, 'files': [
        {'path': name, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
        for name, content in sorted(inputs.items())
    ]}
    encoded = (json.dumps(lock, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    (output / 'INPUTS.lock.json').write_bytes(encoded)
    return hashlib.sha256(encoded).hexdigest()


def main() -> int:
    if sys.version_info < (3, 10):
        print('utility context: Python 3.10+ is required', file=sys.stderr)
        return 1
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--base-image', required=True)
    args = parser.parse_args()
    try:
        pin = prepare(args.output, args.base_image)
    except (OSError, ValueError) as error:
        print(f'utility context: {error}', file=sys.stderr)
        return 1
    print(f'Candidate INPUTS.lock.json SHA-256: {pin}')
    print('Review and independently approve this candidate pin before building or packaging.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
