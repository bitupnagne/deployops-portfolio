"""Exercise actual broker loaders against actual normal source-copy output only."""
import ast
import hashlib
import json
import pathlib
import sys
from types import SimpleNamespace


def check_copied_sources(root):
    root = pathlib.Path(root)
    broker = root / 'nexus-backup-broker.py'
    source = broker.read_bytes()
    tree = ast.parse(source, feature_version=(3, 6))
    selected = [node for node in tree.body if
                isinstance(node, ast.FunctionDef) and node.name in ('require', 'load_library', 'load_restore_worker')
                or isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'LIBRARY_SHA' for target in node.targets)]
    reads = []
    changed = set()

    def read_file(path, uid, mode, maximum, group=None):
        reads.append((path.name, uid, mode, maximum, group))
        raw = path.read_bytes()
        return raw + b'\n# CHANGED_FIXTURE\n' if path.name in changed else raw

    namespace = dict(hashlib=hashlib, SimpleNamespace=SimpleNamespace, read_file=read_file,
                     LIBRARY=root / 'nexus-local-backup-restore.py')
    exec(compile(ast.Module(body=selected, type_ignores=[]), str(broker), 'exec'), namespace)
    cases = 0
    for name in ('nexus-local-backup-restore.py', 'nexus-isolated-restore-worker.py'):
        raw = (root / name).read_bytes()
        assert b'\r' not in raw and not raw.startswith(b'\xef\xbb\xbf')
        cases += 1
    helper = namespace['load_library']()
    worker = namespace['load_restore_worker']()
    assert callable(helper.main) and callable(worker.run_queue)
    assert reads == [('nexus-local-backup-restore.py', 0, 0o600, 100000, None),
                     ('nexus-isolated-restore-worker.py', 0, 0o600, 100000, 0)]
    cases += 2
    for filename, loader in (('nexus-local-backup-restore.py', 'load_library'),
                             ('nexus-isolated-restore-worker.py', 'load_restore_worker')):
        changed.add(filename)
        try:
            namespace[loader]()
        except ValueError:
            cases += 1
        else:
            raise AssertionError('Exact source pin must reject changed bytes')
        changed.clear()
    print(json.dumps(dict(cases=cases, copiedHelperAndWorkerLoad=True,
                          tamperedBytesRejected=True, workerSha256=hashlib.sha256((root / 'nexus-isolated-restore-worker.py').read_bytes()).hexdigest(),
                          dockerCalls=0, nativeExecuted=False)))


if __name__ == '__main__':
    check_copied_sources(sys.argv[1])
