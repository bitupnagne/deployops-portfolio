#!/usr/bin/env python3
"""Real shell flow with isolated fake DB/systemd; never changes a real service."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'Linux root shell fixture')
class BackupFlowTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='deploy-backup-flow-'))
        self.root.chmod(0o700)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.home = self.root / 'app'
        (self.home / 'app').mkdir(parents=True)
        (self.home / 'config').mkdir()
        (self.home / 'app/deploy-ops.jar').write_bytes(b'dummy-configured-jar')
        self.service = self.root / 'service-active'
        self.service.write_text('1')
        self.timer = self.root / 'timer-active'
        self.timer.write_text('1')
        self.log = self.root / 'operations.log'
        self.helper = self.root / 'embedded.py'
        self.helper.write_text('import sys\nsys.stdout.buffer.write(b"jdbc:mariadb://127.0.0.1:3306/deploy\\0user\\0private-never-log\\09090\\0")\n')
        self.maintenance = self.root / 'maintenance.py'
        self.maintenance.write_text('''import os,sys
from pathlib import Path
root=Path(os.environ['FIXTURE_ROOT']); count=root/'idle-count'
n=int(count.read_text())+1 if count.exists() else 1; count.write_text(str(n))
with (root/'operations.log').open('a') as log: log.write('idle'+str(n)+'\\n')
sys.exit(1 if os.environ.get('BUSY_BEFORE')=='1' or n>1 and os.environ.get('BUSY_AFTER')=='1' else 0)
''')
        self.state = self.root / 'state.py'
        self.state.write_text('''import os,sys
from pathlib import Path
root=Path(os.environ['FIXTURE_ROOT'])
with (root/'operations.log').open('a') as log: log.write('state\\n')
if os.environ.get('FAIL_STATE')=='1': sys.exit(1)
Path(sys.argv[sys.argv.index('--archive')+1]).write_bytes(b'fixture-state')
print('{"fixture":true}')
''')
        self.tls = self.root / 'tls.py'
        self.tls.write_text('''import os,sys
from pathlib import Path
with (Path(os.environ['FIXTURE_ROOT'])/'operations.log').open('a') as log: log.write('health\\n')
sys.exit(1 if os.environ.get('FAIL_HEALTH')=='1' else 0)
''')
        self.script('systemctl', '''import os,sys
from pathlib import Path
root=Path(os.environ['FIXTURE_ROOT']); args=sys.argv[1:]
file=root/('timer-active' if args[-1]=='deployops-nexus-backup.timer' else 'service-active')
with (root/'operations.log').open('a') as log: log.write(' '.join(args)+'\\n')
if args[0]=='is-active': sys.exit(0 if file.read_text()=='1' else 3)
if args[0]=='stop': file.write_text('0')
if args[0]=='start': file.write_text('1')
''')
        self.script('mariadb-dump', '''import os,sys
from pathlib import Path
with (Path(os.environ['FIXTURE_ROOT'])/'operations.log').open('a') as log: log.write('dump\\n')
if os.environ.get('FAIL_DUMP')=='1': sys.exit(2)
print('CREATE TABLE backup_fixture(id INT);')
''')
        (self.bin / 'sleep').write_text('#!/bin/sh\nexit 0\n')
        (self.bin / 'sleep').chmod(0o755)
        source = (ROOT / 'deploy-system/deployops-backup.sh').read_text()
        replacements = {'/usr/libexec/platform-python': sys.executable,
            '/usr/local/deploy/bin/deployops-maintenance.py': str(self.maintenance),
            '/usr/local/deploy/bin/deployops-state.py': str(self.state),
            '/usr/local/deploy/bin/deployops-tls.py': str(self.tls)}
        for old, new in replacements.items():
            source = source.replace(old, new)
        self.backup = self.root / 'backup.sh'
        self.backup.write_text(source)
        self.env = dict(os.environ, FIXTURE_ROOT=str(self.root), PATH=str(self.bin)+os.pathsep+os.environ['PATH'],
            DEPLOY_APP_HOME=str(self.home), DEPLOYOPS_CONFIG_HELPER=str(self.helper),
            DEPLOYOPS_BACKUP_DIR=str(self.root / 'backups'), DEPLOYOPS_BACKUP_LOCKS_HELD='1')

    def script(self, name, body):
        path = self.bin / name
        path.write_text('#!'+sys.executable+'\n'+body)
        path.chmod(0o755)

    def tearDown(self):
        shutil.rmtree(str(self.root))

    def run_backup(self, **settings):
        env = dict(self.env, **settings)
        return subprocess.run(['bash', str(self.backup)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)

    def operations(self):
        return self.log.read_text() if self.log.exists() else ''

    def test_success_stops_then_rechecks_before_dump_and_restores_original_activity(self):
        result = self.run_backup()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.operations()
        self.assertLess(calls.index('idle1'), calls.index('stop deploy-system'))
        self.assertLess(calls.index('stop deploy-system'), calls.index('idle2'))
        self.assertLess(calls.index('idle2'), calls.index('dump'))
        self.assertEqual('1', self.service.read_text())
        self.assertEqual('1', self.timer.read_text())
        self.assertEqual(1, len(list((self.root / 'backups').rglob('BACKUP-COMPLETE'))))
        self.assertNotIn(b'private-never-log', result.stdout+result.stderr)

    def test_busy_before_does_not_stop_any_service(self):
        result = self.run_backup(BUSY_BEFORE='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('stop ', self.operations())
        self.assertNotIn('dump', self.operations())

    def test_task_race_after_stop_blocks_backup_and_resumes_original_service(self):
        result = self.run_backup(BUSY_AFTER='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('dump', self.operations())
        self.assertEqual('1', self.service.read_text())
        self.assertEqual('1', self.timer.read_text())

    def test_dump_failure_has_no_complete_marker_and_resumes_original(self):
        result = self.run_backup(FAIL_DUMP='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(list((self.root / 'backups').rglob('BACKUP-COMPLETE')))
        self.assertEqual('1', self.service.read_text())

    def test_state_failure_has_no_complete_marker(self):
        result = self.run_backup(FAIL_STATE='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(list((self.root / 'backups').rglob('BACKUP-COMPLETE')))
        self.assertEqual('1', self.service.read_text())

    def test_originally_stopped_services_stay_stopped(self):
        self.service.write_text('0'); self.timer.write_text('0')
        result = self.run_backup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('0', self.service.read_text())
        self.assertEqual('0', self.timer.read_text())

    def test_health_failure_keeps_nexus_timer_stopped_and_returns_failure(self):
        result = self.run_backup(FAIL_HEALTH='1')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('0', self.timer.read_text())


if __name__ == '__main__':
    unittest.main()
