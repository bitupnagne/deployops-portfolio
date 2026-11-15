import importlib.util
import io
import json
import os
import pathlib
import stat
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('runtime', '/src/was/deploy-runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

class RuntimeAgentTest(unittest.TestCase):
    def setUp(self):
        self.sandbox = tempfile.TemporaryDirectory(dir='/sandbox')
        self.root = self.sandbox.name
        runtime.ROOT = self.root + '/runtime'
        self.args = types.SimpleNamespace(project=1, environment='TEST', revision=7, component=2, uid=10001, gid=10001, lines=2)
    def tearDown(self):
        self.sandbox.cleanup()
    def write(self, payload):
        with patch.object(runtime.sys, 'stdin', types.SimpleNamespace(buffer=io.BytesIO(payload))):
            runtime.write_env(self.args)
    def test_env_root600_and_literal_values(self):
        payload=b'SECRET=$(never-run)\nDB_PASSWORD=a=b\n'
        self.write(payload)
        path=pathlib.Path(runtime.ROOT+'/1/TEST/7.env')
        self.assertEqual(path.read_bytes(),payload)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode),0o600)
        self.assertFalse(os.path.exists(self.root+'/never-run'))
    def test_failed_atomic_publication_leaves_no_final_or_temporary_file(self):
        with patch.object(runtime.os, 'link', side_effect=OSError('injected publish failure')):
            with self.assertRaises(OSError): self.write(b'A=complete')
        directory=pathlib.Path(runtime.ROOT+'/1/TEST')
        self.assertEqual(list(directory.iterdir()), [])
    def test_publication_uses_complete_private_inode_before_final_name(self):
        real_link=runtime.os.link
        def publish(source,target,**kwargs):
            self.assertFalse(os.path.exists(target))
            self.assertEqual(pathlib.Path(source).read_bytes(),b'A=complete')
            self.assertEqual(stat.S_IMODE(os.stat(source).st_mode),0o600)
            return real_link(source,target,**kwargs)
        with patch.object(runtime.os,'link',side_effect=publish): self.write(b'A=complete')
        directory=pathlib.Path(runtime.ROOT+'/1/TEST')
        self.assertEqual([p.name for p in directory.iterdir()],['7.env'])
    def test_retry_exact_and_drift_preserves_original(self):
        self.write(b'A=original')
        self.write(b'A=original')
        with self.assertRaises(ValueError): self.write(b'A=different')
        self.assertEqual(pathlib.Path(runtime.ROOT+'/1/TEST/7.env').read_bytes(),b'A=original')
    def test_env_symlink_and_hardlink_refused(self):
        self.write(b'A=original')
        path=runtime.ROOT+'/1/TEST/7.env'
        os.link(path,self.root+'/linked')
        with self.assertRaises(ValueError): self.write(b'A=original')
        os.unlink(path)
        os.symlink(self.root+'/linked',path)
        with self.assertRaises(ValueError): self.write(b'A=original')
    def test_unsafe_parent_refused(self):
        os.mkdir(runtime.ROOT,0o777)
        os.chmod(runtime.ROOT,0o777)
        with self.assertRaises(ValueError): self.write(b'A=secret')
    def test_invalid_content_bounded(self):
        for payload in (b'A=x\nA=y',b'export A=x',b'A=x\r',b'A=x\x00',b'A='+b'x'*65536,b'\xff=x'):
            with self.assertRaises((ValueError,UnicodeError)): self.write(payload)
        self.assertFalse(os.path.exists(runtime.ROOT))
    def test_interrupted_partial_is_not_repaired(self):
        self.write(b'A=original')
        path=pathlib.Path(runtime.ROOT+'/1/TEST/7.env')
        path.write_bytes(b'A=')
        with self.assertRaises(ValueError): self.write(b'A=original')
        self.assertEqual(path.read_bytes(),b'A=')
    def volume_fixture(self):
        root=self.root+'/docker'
        name=runtime.volume_name(self.args)
        data=root+'/volumes/'+name+'/_data'
        os.makedirs(data,mode=0o700)
        labels={'deploy.project':'1','deploy.component':'2','deploy.environment':'TEST','deploy.purpose':'app-logs'}
        info={'Name':name,'Driver':'local','Options':{},'Labels':labels,'Mountpoint':data}
        def command(arguments):
            if arguments[:2]==['volume','ls']: return name
            if arguments[:2]==['volume','inspect']: return json.dumps([info])
            if arguments[0]=='info': return root
            self.fail('unexpected Docker mutation: '+str(arguments))
        return data,info,command
    def test_empty_volume_owner_initializes_once_and_existing_data_preserved(self):
        data,info,command=self.volume_fixture()
        with patch.object(runtime,'docker',command):
            self.assertEqual(runtime.volume(self.args,True),data)
            self.assertEqual(os.stat(data).st_uid,10001)
            pathlib.Path(data+'/keep').write_text('retained')
            runtime.volume(self.args,True)
            self.assertEqual(pathlib.Path(data+'/keep').read_text(),'retained')
    def test_foreign_labels_refused_before_ownership_change(self):
        data,info,command=self.volume_fixture()
        info['Labels']['deploy.project']='other'
        with patch.object(runtime,'docker',command):
            with self.assertRaises(ValueError): runtime.volume(self.args,True)
        self.assertEqual(os.stat(data).st_uid,0)
    def test_bind_driver_options_and_path_drift_refused(self):
        data,info,command=self.volume_fixture()
        for changed in ({'Driver':'nfs'},{'Options':{'device':'/etc'}},{'Mountpoint':'/etc'}):
            original=info.copy(); info.update(changed)
            with patch.object(runtime,'docker',command):
                with self.assertRaises(ValueError): runtime.volume(self.args,True)
            info.clear(); info.update(original)
        self.assertEqual(os.stat(data).st_uid,0)
    def test_nonempty_foreign_owner_never_chowned(self):
        data,info,command=self.volume_fixture()
        pathlib.Path(data+'/keep').write_text('original')
        with patch.object(runtime,'docker',command):
            with self.assertRaises(ValueError): runtime.volume(self.args,True)
        self.assertEqual(os.stat(data).st_uid,0)
        self.assertEqual(pathlib.Path(data+'/keep').read_text(),'original')
    def test_fixed_file_log_tail_and_json_escape(self):
        data,info,command=self.volume_fixture()
        with patch.object(runtime,'docker',command):
            runtime.volume(self.args,True)
            pathlib.Path(data+'/demo-app.log').write_text('first\n<script>second</script>\nlast\n')
            with patch('sys.stdout',new_callable=io.StringIO) as output:
                runtime.logs(self.args)
            result=json.loads(output.getvalue())
            self.assertEqual(result['text'],'<script>second</script>\nlast')
            self.assertTrue(result['truncated'])
    def test_filelog_symlink_binary_and_hardlink_refused(self):
        data,info,command=self.volume_fixture()
        path=data+'/demo-app.log'
        with patch.object(runtime,'docker',command):
            runtime.volume(self.args,True)
            os.symlink('/etc/passwd',path)
            with self.assertRaises(ValueError): runtime.logs(self.args)
            os.unlink(path)
            pathlib.Path(path).write_bytes(b'private\0data')
            with self.assertRaises(ValueError): runtime.logs(self.args)
            pathlib.Path(path).write_text('safe')
            os.link(path,data+'/alias')
            with self.assertRaises(ValueError): runtime.logs(self.args)
    def test_large_single_line_remains_bounded_and_explicitly_truncated(self):
        data,info,command=self.volume_fixture()
        with patch.object(runtime,'docker',command):
            runtime.volume(self.args,True)
            pathlib.Path(data+'/demo-app.log').write_text('x'*300000)
            with patch('sys.stdout',new_callable=io.StringIO) as output:
                runtime.logs(self.args)
            result=json.loads(output.getvalue())
            self.assertTrue(result['text'])
            self.assertLessEqual(len(result['text'].encode('utf-8')),262144)
            self.assertTrue(result['truncated'])

unittest.main()
