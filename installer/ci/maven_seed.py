#!/usr/bin/env python3
"""Maven seed: current worktree snapshots, isolated cache and offline verify evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


SCHEMA = 1
IGNORED_SOURCE_PARTS = {'.git', '.idea', '.vscode', 'target', 'output', 'bundles', 'node_modules', '.m2', '__pycache__', '.pytest_cache'}
SETTINGS = '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n'
SOURCE_ROOTS = {'src', '.mvn', 'installer', 'scripts'}
SOURCE_FILES = {'pom.xml', 'mvnw', 'mvnw.cmd', 'Dockerfile', '.dockerignore',
                'Dockerfile.release', 'Dockerfile.release.dockerignore'}
RELEASE_BOM_KEYS = {
    'BOM_SCHEMA_VERSION', 'BOM_RELEASE', 'BOM_GITLAB_IMAGE', 'BOM_RUNNER_IMAGE',
    'BOM_RUNNER_HELPER_IMAGE', 'BOM_RUNNER_BUILD_IMAGE', 'BOM_NEXUS_IMAGE',
    'BOM_JAVA_VERSION', 'BOM_JAVA_ARCHIVE', 'BOM_JAVA_SHA256', 'BOM_JQ_VERSION',
    'BOM_JQ_SHA256', 'BOM_DEPLOYOPS_JAR', 'BOM_DEPLOYOPS_VERSION',
    'BOM_DEPLOYOPS_REQUIRED_MIGRATIONS', 'BOM_WORKSYSTEM_JAR', 'BOM_WORKSYSTEM_VERSION',
}


class SeedError(Exception):
    pass


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def safe_path(text: str) -> PurePosixPath:
    p = PurePosixPath(text)
    if not text or p.is_absolute() or '\\' in text or ':' in text or any(c in text for c in '\r\n\0'):
        raise SeedError(f'Unsafe relative path: {text!r}')
    if any(part in ('', '.', '..') for part in text.split('/')):
        raise SeedError(f'Unsafe relative path: {text!r}')
    return p


def checked_file(root: Path, relative: str) -> Path:
    p = safe_path(relative)
    current = root
    for part in p.parts:
        current = current / part
        if current.is_symlink():
            raise SeedError(f'Symlink is forbidden: {current}')
    if not current.is_file() or not current.resolve().is_relative_to(root.resolve()):
        raise SeedError(f'Missing or escaping file: {current}')
    return current


def validate_release_bom(path: Path) -> None:
    text = path.read_text(encoding='utf-8')
    if re.search(r'-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----|\b(?:password|secret|token|api[-_]?key|private[-_]?key|credential)\b',
                 text, re.I):
        raise SeedError('Release BOM contains credential-like content')
    seen = set()
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        match = re.fullmatch(r'(BOM_[A-Z0-9_]+)=([^\s]+)', stripped)
        if not match or match.group(1) not in RELEASE_BOM_KEYS or match.group(1) in seen:
            raise SeedError('Release BOM has an unknown, duplicate or malformed entry')
        key, value = match.groups()
        if key.endswith('_SHA256') and not re.fullmatch(r'[a-fA-F0-9]{64}', value):
            raise SeedError('Release BOM checksum has an invalid format')
        seen.add(key)


def clean_env() -> dict[str, str]:
    env = dict(os.environ)
    for name in ('MAVEN_OPTS', 'MAVEN_ARGS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    return env


def maven_identity(executable: str) -> dict[str, str]:
    result = subprocess.run([executable, '-version'], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=clean_env(), check=False)
    output = re.sub(r'\x1b\[[0-9;]*m', '', result.stdout)
    maven = re.search(r'^Apache Maven (.+)$', output, re.M)
    java = re.search(r'^Java version: (.+)$', output, re.M)
    if result.returncode or not maven or not java:
        raise SeedError('Cannot determine Maven/JDK identity using mvn -version')
    node_executable = shutil.which('node')
    if not node_executable:
        raise SeedError('Node.js is required to run UI regression tests without skips')
    node_result = subprocess.run([node_executable, '--version'], text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, env=clean_env(), check=False)
    node = re.fullmatch(r'v?(\d+\.\d+\.\d+)\s*', node_result.stdout or '')
    if node_result.returncode or not node:
        raise SeedError('Cannot determine Node.js identity using node --version')
    return {'maven': maven.group(1).strip(), 'java': java.group(1).split(', runtime:')[0].strip(),
            'node': node.group(1), 'os': platform.system(), 'architecture': platform.machine()}


def source_files(root: Path) -> list[str]:
    result = subprocess.run(['git', '-C', str(root), 'ls-files', '--cached', '--others',
                             '--exclude-standard', '-z', '--', '.'], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise SeedError(f'Source must be a Git worktree directory: {root}')
    selected = []
    for relative in sorted(set(result.stdout.decode('utf-8').split('\0')) - {''}):
        p = safe_path(relative)
        if p.parts[0] not in SOURCE_ROOTS and relative not in SOURCE_FILES:
            continue
        # This small release metadata file is read by migration-lineage tests
        # and is also part of the offline release contract. Keep excluding the
        # rest of installer/bundles, which can contain large/private payloads.
        is_release_bom = relative == 'installer/bundles/RELEASE-BOM.env'
        if any(part in IGNORED_SOURCE_PARTS for part in p.parts) and not is_release_bom:
            continue
        if p.suffix.lower() in {'.pyc', '.pyo'}:
            continue
        # Never package local Maven/user credentials as build inputs.
        if not is_release_bom and (p.name in {'settings.xml', 'settings-security.xml', '.netrc', '.git-credentials'}
                or p.name.startswith('.env')
                or p.suffix.lower() in {'.env', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore'}):
            continue
        if not (root / relative).exists():
            continue  # A deleted tracked file is deleted in the snapshot as well.
        checked_file(root, relative)
        if is_release_bom:
            validate_release_bom(root / relative)
        if relative.startswith('src/main/') and p.suffix.lower() in {'.yml', '.yaml', '.properties', '.xml', '.json'}:
            text = (root / relative).read_text(encoding='utf-8')
            if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', text):
                raise SeedError(f'Private key material in source configuration: {relative}')
            for line in text.splitlines():
                match = re.match(r'''\s*["']?([\w.-]*(?:password|secret|token|api-key|private-key))["']?\s*[:=]\s*(.*?)\s*$''', line, re.I)
                if match:
                    value = match.group(2).strip('"\' ,')
                    if value and value not in {'null', '~'} and not value.startswith('${'):
                        raise SeedError(f'Literal credential-like source configuration requires review: {relative} ({match.group(1)})')
        selected.append(relative)
    if 'pom.xml' not in selected:
        raise SeedError(f'Root pom.xml is missing: {root}')
    if '.mvn/maven.config' in selected and (root / '.mvn/maven.config').read_text().strip():
        raise SeedError('Nonempty .mvn/maven.config needs explicit option review before seed collection')
    return selected


def cache_artifact(relative: str) -> bool:
    p = safe_path(relative)
    if len(p.parts) < 3 or any(part.startswith('.') for part in p.parts):
        return False
    if p.name in {'settings.xml', 'settings-security.xml', 'resolver-status.properties', '_remote.repositories'}:
        return False
    if p.name.endswith(('.lastUpdated', '.part', '.tmp', '.lock')):
        return False
    if re.fullmatch(r'maven-metadata[^/]*\.xml(?:\.(?:sha1|sha256|sha512|md5))?', p.name):
        return True
    if len(p.parts) < 4:
        return False
    artifact, version = p.parts[-3:-1]
    return p.name.startswith(f'{artifact}-{version}') and bool(re.search(
        r'\.(?:jar|pom|war|aar|zip|tar\.gz|tar\.bz2|tar\.xz|exe|dll|so)(?:\.(?:asc|sha1|sha256|sha512|md5))?$', p.name))


def record(root: Path, relative: str) -> dict:
    path = checked_file(root, relative)
    return {'path': relative, 'size': path.stat().st_size, 'sha256': digest(path)}


def copy_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    shutil.copymode(source, destination)


def collect(projects: list[str], caches: list[str], executable: str, output: Path, cache_origin: str = '') -> None:
    if output.exists():
        raise SeedError('Seed output must not already exist; existing data is never overwritten')
    for cache in caches:
        cache_root = Path(cache).resolve()
        if not cache_root.is_dir() or cache_root.name != 'repository':
            raise SeedError('--cache must point specifically to a Maven repository directory, not .m2')
        if output.resolve().is_relative_to(cache_root) or cache_root.is_relative_to(output.resolve()):
            raise SeedError('Seed output must not overlap a candidate cache')
    if any(p.name == '.m2' or (p / '.git').exists() for p in output.resolve().parents):
        raise SeedError('Seed output must be outside Git worktrees and user Maven directories')
    parsed = []
    names = set()
    for item in projects:
        name, separator, path = item.partition('=')
        if not separator or not re.fullmatch(r'[a-zA-Z0-9_-]+', name) or name in names:
            raise SeedError('Use unique --project name=absolute-path values')
        names.add(name)
        root = Path(path).resolve()
        if output.resolve().is_relative_to(root):
            raise SeedError('Seed output must be outside every source project')
        parsed.append((name, root, source_files(root)))
    identity = maven_identity(executable)
    output.mkdir(parents=True)
    lock = {'schema': SCHEMA, 'identity': identity, 'source_selection': 'current-git-tracked-and-nonignored-untracked',
            'source_scope': 'pom.xml,mvnw,mvnw.cmd,Dockerfile[.release][.dockerignore],.dockerignore,src/,.mvn/,installer/,scripts/ minus generated and secret files',
            'cache_origin': cache_origin or platform.system(), 'cache_trust': 'candidate-bytes-not-publisher-authentication',
            'projects': [], 'files': []}
    for name, root, files in parsed:
        for relative in files:
            source = checked_file(root, relative)
            before = digest(source)
            destination = output / 'sources' / name / relative
            copy_file(source, destination)
            if digest(destination) != before or digest(source) != before:
                raise SeedError(f'Worktree changed during collection: {relative}')
        lock['projects'].append({'name': name, 'pom_sha256': digest(output / 'sources' / name / 'pom.xml'),
                                 'files': files})
    copied = 0
    for cache in caches:
        cache_root = Path(cache).resolve()
        if not cache_root.is_dir() or cache_root.name != 'repository':
            raise SeedError('--cache must point specifically to a Maven repository directory, not .m2')
        for path in sorted(cache_root.rglob('*')):
            relative = path.relative_to(cache_root).as_posix()
            if path.is_symlink():
                raise SeedError(f'Cache symlink is forbidden: {relative}')
            if not path.is_file() or not cache_artifact(relative):
                continue
            destination = output / 'repository' / relative
            if destination.exists():
                if digest(destination) != digest(path):
                    raise SeedError(f'Different cache bytes for same Maven coordinate: {relative}')
                continue
            copy_file(path, destination)
            copied += 1
    if not copied:
        raise SeedError('No Maven artifacts selected from candidate caches')
    for name, root, files in parsed:
        if source_files(root) != files:
            raise SeedError(f'Source inventory changed during seed collection: {name}')
        for relative in files:
            if digest(checked_file(root, relative)) != digest(output / 'sources' / name / relative):
                raise SeedError(f'Source bytes changed during seed collection: {name}/{relative}')
    for path in sorted(output.rglob('*')):
        if path.is_file():
            lock['files'].append(record(output, path.relative_to(output).as_posix()))
    (output / 'seed.lock.json').write_text(json.dumps(lock, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    verify_seed(output)


def verify_seed(root: Path, expected_lock_sha256: str | None = None) -> dict:
    lock_file = checked_file(root, 'seed.lock.json')
    if expected_lock_sha256 is not None and digest(lock_file) != expected_lock_sha256:
        raise SeedError('Seed lock differs from independently pinned SHA-256')
    lock = json.loads(lock_file.read_text(encoding='utf-8'))
    if lock.get('schema') != SCHEMA or not lock.get('projects') or not lock.get('identity'):
        raise SeedError('Invalid Maven seed lock schema')
    expected = set()
    for item in lock['files']:
        relative = item['path']
        if relative in expected:
            raise SeedError(f'Duplicate locked path: {relative}')
        expected.add(relative)
        path = checked_file(root, relative)
        if path.stat().st_size != item['size'] or digest(path) != item['sha256']:
            raise SeedError(f'Seed size/SHA differs: {relative}')
    actual = set()
    for path in root.rglob('*'):
        if path.is_symlink():
            raise SeedError(f'Seed symlink is forbidden: {path}')
        if path.is_file() and path != lock_file:
            actual.add(path.relative_to(root).as_posix())
    if expected != actual or not any(p.startswith('repository/') for p in actual):
        raise SeedError('Seed file inventory differs or Maven repository is missing')
    source_inventory = set()
    names = set()
    for project in lock['projects']:
        name = project['name']
        if not re.fullmatch(r'[a-zA-Z0-9_-]+', name) or name in names:
            raise SeedError('Invalid or duplicate project name')
        names.add(name)
        if digest(checked_file(root, f'sources/{name}/pom.xml')) != project['pom_sha256']:
            raise SeedError(f'POM identity differs: {name}')
        for relative in project['files']:
            safe_path(relative)
            source_inventory.add(f'sources/{name}/{relative}')
    if source_inventory != {p for p in actual if p.startswith('sources/')}:
        raise SeedError('Source snapshot inventory differs')
    for relative in actual:
        if relative.startswith('repository/') and not cache_artifact(relative[len('repository/'):]):
            raise SeedError(f'Non-artifact or credentials in seed repository: {relative}')
        if not relative.startswith(('repository/', 'sources/')):
            raise SeedError(f'Unexpected seed payload: {relative}')
    return lock


def test_evidence(project_root: Path) -> dict:
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'suites': 0}
    for report in project_root.glob('**/target/*-reports/TEST-*.xml'):
        suite = ET.parse(report).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(suite.attrib.get(key, 0))
        totals['suites'] += 1
    if not totals['tests'] or totals['failures'] or totals['errors'] or totals['skipped']:
        raise SeedError(f'Tests were absent, failed or skipped: {totals}')
    return totals


def verify_build(seed: Path, executable: str, work: Path, expected_lock_sha256: str) -> dict:
    lock = verify_seed(seed, expected_lock_sha256)
    if maven_identity(executable) != lock['identity']:
        raise SeedError('Maven/JDK/Node.js/OS identity differs from seed collection')
    if work.exists() or work.resolve().parent != seed.resolve().parent:
        raise SeedError('Build work must be a new sibling of the immutable seed in its staging directory')
    if any(p.name == '.m2' or (p / '.git').exists() for p in work.resolve().parents):
        raise SeedError('Build work must be outside Git worktrees and user Maven directories')
    work.mkdir(parents=True)
    settings = work / 'empty-settings.xml'
    settings.write_text(SETTINGS, encoding='utf-8')
    (work / 'home').mkdir()
    result = {'schema': SCHEMA, 'seed_lock_sha256': digest(seed / 'seed.lock.json'),
              'identity': lock['identity'], 'command': 'mvn --offline clean verify',
              'network_isolation': 'not-attested: Maven offline does not constrain tests or Docker daemon',
              'projects': [], 'passed': False}
    try:
        for project in lock['projects']:
            name = project['name']
            project_root = work / name / 'source'
            repository = work / name / 'repository'
            shutil.copytree(seed / 'sources' / name, project_root)
            shutil.copytree(seed / 'repository', repository)
            command = [executable, '--batch-mode', '--offline', '--no-transfer-progress',
                       '--settings', str(settings), '--global-settings', str(settings),
                       f'-Dmaven.repo.local={repository}', f'-Duser.home={work / "home"}',
                       'clean', 'verify']
            log = work / f'{name}.log'
            with log.open('wb') as output:
                run = subprocess.run(command, cwd=project_root, env=clean_env(), stdout=output,
                                     stderr=subprocess.STDOUT, check=False)
            evidence = {'name': name, 'exit_code': run.returncode, 'log_sha256': digest(log)}
            result['projects'].append(evidence)
            if run.returncode:
                raise SeedError(f'Offline clean verify failed: {name}; inspect {log}')
            evidence['tests'] = test_evidence(project_root)
            evidence['artifacts'] = [record(project_root, path.relative_to(project_root).as_posix())
                                     for path in sorted(project_root.glob('target/*.jar'))]
            if not evidence['artifacts']:
                raise SeedError(f'No packaged JAR produced: {name}')
        verify_seed(seed, expected_lock_sha256)
        result['passed'] = True
        return result
    finally:
        (work / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    inspect_parser = sub.add_parser('inspect-source')
    inspect_parser.add_argument('--project', type=Path, required=True)
    collect_parser = sub.add_parser('collect')
    collect_parser.add_argument('--project', action='append', required=True)
    collect_parser.add_argument('--cache', action='append', required=True)
    collect_parser.add_argument('--maven', required=True)
    collect_parser.add_argument('--output', type=Path, required=True)
    collect_parser.add_argument('--cache-origin', default='', help='Candidate cache source platform, e.g. Windows')
    check_parser = sub.add_parser('verify-seed')
    check_parser.add_argument('--seed', type=Path, required=True)
    check_parser.add_argument('--lock-sha256')
    build_parser = sub.add_parser('verify-build')
    build_parser.add_argument('--seed', type=Path, required=True)
    build_parser.add_argument('--maven', required=True)
    build_parser.add_argument('--work', type=Path, required=True)
    build_parser.add_argument('--lock-sha256', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'inspect-source':
            root = args.project.resolve()
            files = source_files(root)
            print(json.dumps({'files': len(files), 'pom_sha256': digest(root / 'pom.xml')}, ensure_ascii=False))
        elif args.command == 'collect':
            collect(args.project, args.cache, args.maven, args.output.resolve(), args.cache_origin)
            print(f'Seed lock SHA-256: {digest(args.output / "seed.lock.json")}')
        elif args.command == 'verify-seed':
            verify_seed(args.seed.resolve(), args.lock_sha256)
        else:
            verify_build(args.seed.resolve(), args.maven, args.work.resolve(), args.lock_sha256)
        print(f'{args.command}: PASS')
        return 0
    except (SeedError, OSError, ValueError, KeyError, ET.ParseError) as error:
        print(f'{args.command}: FAIL: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
