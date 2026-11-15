import json
import os
import pathlib
import subprocess
import tempfile
import unittest

class ContainerArgsTest(unittest.TestCase):
    def case(self, role, options):
        with tempfile.TemporaryDirectory(dir='/sandbox') as directory:
            root=pathlib.Path(directory)
            trace=root/'trace'
            runtime=root/'runtime-agent'
            runtime.write_text('#!/bin/bash\nprintf "runtime-called\\n" >> "$TRACE"\n')
            runtime.chmod(0o700)
            systemctl=root/'systemctl'
            systemctl.write_text('#!/bin/sh\nexit 0\n')
            systemctl.chmod(0o700)
            docker=root/'docker'
            docker.write_text('''#!/usr/libexec/platform-python
import json,os,sys
with open(os.environ['TRACE'],'a') as stream: stream.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1]=='inspect': sys.exit(1)
if sys.argv[1]=='run': print('fixture-container')
''')
            docker.chmod(0o700)
            source=pathlib.Path('/src/'+role+'/deploy-'+role+'-app.sh').read_text()
            self.assertEqual(source.count('/usr/local/deploy/bin/deploy-runtime.sh'),1)
            script=root/'deploy.sh'
            script.write_text(source.replace('/usr/local/deploy/bin/deploy-runtime.sh',str(runtime)))
            arguments=['/bin/bash',str(script),'deploy','--image','qa/pinned:fixture','--container','qa-fixture','--health-mode','none','--user','10001:10001']
            if role=='web': arguments+=['--reload-nginx','false']
            result=subprocess.run(arguments+options,env=dict(os.environ,PATH=str(root)+':/usr/bin:/bin',TRACE=str(trace)),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
            records=trace.read_text().splitlines() if trace.exists() else []
            self.lastDiagnostic=role+': '+result.stdout.decode('utf-8','replace')+result.stderr.decode('utf-8','replace')
            runs=[json.loads(line) for line in records if line.startswith('["run"')]
            return result.returncode,records,runs
    def scope(self):
        return ['--managed-project','12','--managed-environment','TEST','--managed-component','3']
    def test_actual_was_and_web_build_nocopy_mount_and_bounded_tmpfs(self):
        for role in ('was','web'):
            code,records,runs=self.case(role,self.scope()+['--persistent-logs','--tmp-mib','32','--read-only','true'])
            self.assertEqual(code,0,self.lastDiagnostic)
            self.assertEqual(len(runs),1)
            args=runs[0]
            self.assertIn('type=volume,source=deploy-p12-c3-test-logs,target=/app/logs,volume-nocopy',args)
            self.assertIn('/tmp:rw,nosuid,nodev,noexec,size=32m,mode=0700,uid=10001,gid=10001',args)
            self.assertIn('--read-only',args)
            self.assertNotIn('--privileged',args)
            self.assertNotIn('volume',' '.join(args[:1]))
            self.assertEqual(records.count('runtime-called'),1)
    def test_invalid_tmpfs_never_provisions_or_launches(self):
        for role in ('was','web'):
            for size in ('15','513','../bad'):
                code,records,runs=self.case(role,self.scope()+['--persistent-logs','--tmp-mib',size])
                self.assertNotEqual(code,0); self.assertEqual(runs,[]); self.assertNotIn('runtime-called',records)
    def test_root_or_privileged_or_caps_never_provisions_or_launches(self):
        for role in ('was','web'):
            for options in (['--user','0:0'],['--privileged','true'],['--cap-add','SYS_ADMIN']):
                code,records,runs=self.case(role,self.scope()+['--persistent-logs']+options)
                self.assertNotEqual(code,0); self.assertEqual(runs,[]); self.assertNotIn('runtime-called',records)
    def test_invalid_scope_never_provisions_or_launches(self):
        for role in ('was','web'):
            code,records,runs=self.case(role,['--managed-project','0','--managed-environment','PROD','--persistent-logs'])
            self.assertNotEqual(code,0); self.assertEqual(runs,[]); self.assertNotIn('runtime-called',records)
    def test_legacy_container_has_no_implicit_storage(self):
        for role in ('was','web'):
            code,records,runs=self.case(role,[])
            self.assertEqual(code,0,self.lastDiagnostic); self.assertEqual(len(runs),1)
            self.assertNotIn('--mount',runs[0]); self.assertNotIn('--tmpfs',runs[0]); self.assertNotIn('runtime-called',records)

unittest.main()
