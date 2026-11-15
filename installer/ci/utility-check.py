#!/usr/bin/env python3
"""Verify locked inputs and test importer help/stdlib without Nexus access."""
from __future__ import annotations
import argparse
import ast
import hashlib
import importlib
import json
from pathlib import Path
import platform
import re
import ssl
import stat
import subprocess
import sys


FILES = {'Dockerfile', '.dockerignore', 'BASE_IMAGE', 'maven_seed.py',
         'nexus_seed_import.py', 'utility-check.py', 'utility-entrypoint.py'}


def canonical(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON key')
        value[key] = item
    return value


def verify_context(root: Path, pin: str, base_image: str) -> None:
    if not re.fullmatch(r'[0-9a-f]{64}', pin):
        raise ValueError('An independently approved INPUTS.lock.json SHA-256 is required')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}', base_image):
        raise ValueError('A digest-pinned Python base is required')
    if root.is_symlink() or not root.is_dir():
        raise ValueError('A regular context directory is required')
    files = {}
    for path in root.iterdir():
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError('Context directories, links and special files are forbidden')
        files[path.name] = path.read_bytes()
    if set(files) != FILES | {'INPUTS.lock.json'}:
        raise ValueError('Unexpected or missing utility context file')
    raw = files['INPUTS.lock.json']
    if hashlib.sha256(raw).hexdigest() != pin:
        raise ValueError('Input lock pin mismatch')
    lock = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(lock, dict) or raw != canonical(lock) or set(lock) != {'schemaVersion', 'files'} \
            or type(lock['schemaVersion']) is not int or lock['schemaVersion'] != 1 \
            or not isinstance(lock['files'], list):
        raise ValueError('Invalid or noncanonical input lock')
    seen = set()
    for item in lock['files']:
        if not isinstance(item, dict) or set(item) != {'path', 'size', 'sha256'}:
            raise ValueError('Invalid input lock row')
        name = item['path']
        if not isinstance(name, str) or name not in FILES or name in seen:
            raise ValueError('Unexpected or duplicate input path')
        content = files[name]
        if type(item['size']) is not int or item['size'] != len(content) \
                or item['sha256'] != hashlib.sha256(content).hexdigest():
            raise ValueError(f'Input bytes differ: {name}')
        seen.add(name)
    if seen != FILES or files['BASE_IMAGE'] != (base_image + '\n').encode('ascii'):
        raise ValueError('Input inventory or base identity mismatch')


def smoke(root: Path) -> None:
    if not sys.flags.isolated or sys.version_info < (3, 10):
        raise ValueError('Python 3.10+ isolated mode (-I) is required')
    if platform.system() != 'Linux' or platform.machine() not in {'x86_64', 'amd64'}:
        raise ValueError('Linux amd64 is required')
    for name in ('maven_seed.py', 'nexus_seed_import.py', 'utility-entrypoint.py'):
        tree = ast.parse((root / name).read_text(encoding='utf-8'))
        for node in ast.walk(tree):
            modules = [alias.name for alias in node.names] if isinstance(node, ast.Import) else \
                [node.module or ''] if isinstance(node, ast.ImportFrom) else []
            for module in modules:
                top = module.split('.')[0]
                if top not in sys.stdlib_module_names and top != 'maven_seed':
                    raise ValueError(f'Non-stdlib importer dependency: {top}')
                if top != 'maven_seed':
                    importlib.import_module(module)
    if not ssl.create_default_context().cert_store_stats().get('x509_ca', 0):
        raise ValueError('Default CA trust store is empty')
    for algorithm in ('sha1', 'sha256', 'sha512'):
        hashlib.new(algorithm, b'utility-smoke').hexdigest()
    run = subprocess.run([sys.executable, '-I', '-B', str(root / 'utility-entrypoint.py'), '--help'],
                         stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, check=False, timeout=30)
    if run.returncode or any(option not in run.stdout for option in
                             ('--seed', '--lock-sha256', '--nexus-url', '--auth-file', '--apply')):
        raise ValueError('Bundled importer --help failed')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--context', type=Path)
    mode.add_argument('--smoke', action='store_true')
    parser.add_argument('--lock-sha256')
    parser.add_argument('--base-image')
    args = parser.parse_args()
    root = args.context or Path(__file__).resolve().parent
    try:
        if args.context:
            verify_context(root, args.lock_sha256 or '', args.base_image or '')
        elif args.lock_sha256 or args.base_image:
            raise ValueError('Lock/base options belong to --context')
        smoke(root)
    except (OSError, ValueError, ImportError, subprocess.TimeoutExpired) as error:
        print(f'utility check: {error}', file=sys.stderr)
        return 1
    print(f'Utility stdlib/help passed: Python {platform.python_version()}; Linux amd64; Nexus not contacted')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
