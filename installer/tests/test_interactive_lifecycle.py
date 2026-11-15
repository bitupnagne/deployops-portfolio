"""Exercise ownership discovery, fail-closed rollout and recoverable retention without host changes."""
import importlib.util
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location(
    'interactive_lifecycle', str(Path(__file__).resolve().parents[1] / 'interactive_lifecycle.py'))
life = importlib.util.module_from_spec(spec)
spec.loader.exec_module(life)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.w = Mock(base=self.base, directory=self.base / 'deploy')
        self.w.state = {'role': 'deploy', 'values': {'server_name': 'demo-deploy', 'db_password': 'preserve'},
                        'stages': {'company-jar': 'SUCCEEDED'}, 'installation_started': True}
        self.before = {'id': 'a' * 64}
        self.after = {'id': 'b' * 64}
        self.container = 'worksystem-nexus'
        self.files = '/usr/local/deploy/bin/deploy-agent-probe'

    def test_only_first_seen_creation_becomes_owned(self):
        probes = {self.container: None, self.files: {'sha256': 'preexisting'}}
        with patch.object(life, 'identity', side_effect=lambda k, n: probes.get(n)):
            life.before_install(self.w)
            probes.update({self.container: self.after, self.files: {'sha256': 'updated'}})
            life.after_install(self.w)
        self.assertEqual({self.container: self.after}, self.w.state['lifecycle_owned'])

    def test_unavailable_daemon_does_not_prove_absence(self):
        probes = {self.container: life.UNKNOWN}
        with patch.object(life, 'identity', side_effect=lambda k,n: probes.get(n)):
            life.before_install(self.w)
            probes[self.container] = self.after
            life.after_install(self.w)
        self.assertEqual({}, self.w.state['lifecycle_owned'])

    def test_stale_id_or_unknown_legacy_state_preserved(self):
        with patch.object(life, 'identity', side_effect=lambda k,n: self.after if n == self.container else None):
            self.assertEqual([('keep', self.container, self.after)], life.plan(self.w))
            self.w.state['lifecycle_owned'] = {self.container: self.before}
            self.assertEqual([('keep', self.container, self.after)], life.plan(self.w))

    def test_new_version_of_owned_resource_is_tracked_only_when_baseline_matched(self):
        self.w.state['lifecycle_owned'] = {self.container: self.before}
        with patch.object(life, 'identity', side_effect=lambda k,n: self.before if n==self.container else None):
            life.before_install(self.w)
        with patch.object(life, 'identity', side_effect=lambda k,n: self.after if n==self.container else None):
            life.after_install(self.w)
        self.assertEqual(self.after,self.w.state['lifecycle_owned'][self.container])

    def test_foreign_or_override_unit_keeps_dependents(self):
        unit = str(life.SYSTEMD/'deploy-pull-agent.service')
        probes = {self.container: self.before, unit:{'sha256':'a'}}
        self.w.state['lifecycle_owned'] = dict(probes)
        with patch.object(life, 'identity',side_effect=lambda k,n:probes.get(n)), patch.object(life,'unit_matches',return_value=False):
            self.assertTrue(all(k=='keep' for k,n,r in life.plan(self.w)))
        (self.base/'gitlab').mkdir()
        (self.base/'gitlab'/'state.json').touch()
        with patch.object(life, 'identity',side_effect=lambda k,n:probes.get(n)), patch.object(life,'unit_matches',return_value=True):
            self.assertTrue(all(k=='keep' for k,n,r in life.plan(self.w)))

    def test_reset_keeps_secret_values_and_key_protection(self):
        life.reset(self.w)
        self.assertEqual({},self.w.state['stages'])
        self.assertEqual('preserve',self.w.state['values']['db_password'])
        self.assertTrue(self.w.state['installation_started'])

    def test_remove_rejects_preview_drift_before_side_effects(self):
        with patch.object(life,'plan',return_value=[]), patch.object(life,'require') as require:
            with self.assertRaises(life.LifecycleError):
                life.remove_runtime(self.w,[('container',self.container,self.before)])
            require.assert_not_called()

    def test_default_retirement_keeps_writable_layer_and_volumes(self):
        with patch.object(life,'container_identity',return_value=None), patch.object(life,'require') as do:
            life.retire(self.w,self.container,self.before)
        args=[c[0][0] for c in do.call_args_list]
        self.assertEqual(['update','stop','rename'],[a[1] for a in args])
        self.assertTrue(all(self.before['id'] in a for a in args))
        self.assertEqual(self.container,self.w.state['lifecycle_retired'][self.before['id']])

    def test_purge_never_forces_or_erases_volumes(self):
        token=self.before['id']
        self.w.state['lifecycle_retired']={token:self.container}
        with patch.object(life,'container_identity',return_value=self.before), patch.object(life,'require',return_value='false') as do:
            life.purge(self.w,[(token,life.retired_name(token))])
        do.assert_called_with(['docker','container','rm',token])
        self.assertEqual({},self.w.state['lifecycle_retired'])

    def test_purge_refuses_replacement_or_running_archive(self):
        self.w.state['lifecycle_retired']={self.before['id']:self.container}
        with patch.object(life,'container_identity',return_value=self.after):
            with self.assertRaises(life.LifecycleError):life.purge_candidates(self.w)
        with patch.object(life,'container_identity',return_value=self.before),patch.object(life,'require',return_value='true'):
            with self.assertRaises(life.LifecycleError):life.purge_candidates(self.w)

    def test_mistyped_confirmation_has_no_effect(self):
        self.w.io.ask.side_effect=['2','demo-deploy']
        with patch.object(life,'plan',return_value=[('container',self.container,self.before)]),patch.object(life,'remove_runtime') as remove:
            life.manage(self.w)
        remove.assert_not_called()

    def test_interrupted_install_does_not_adopt_unobserved_asset(self):
        with patch.object(life, 'identity', return_value=None):
            life.before_install(self.w)
        self.assertTrue(self.w.state['lifecycle_install_pending'])
        with patch.object(life, 'identity', side_effect=lambda k,n: self.after if n == self.container else None):
            life.before_install(self.w)
            life.after_install(self.w)
        self.assertTrue(self.w.state['lifecycle_interrupted'])
        self.assertNotIn(self.container, self.w.state['lifecycle_owned'])
        self.assertNotIn('lifecycle_install_pending', self.w.state)

    def test_upgrade_handoff_requires_every_verified_edge_to_original(self):
        name=self.container
        a,b,c=('a'*64,'b'*64,'c'*64)
        img='sha256:'+'d'*64
        binds=[('/nexus-data','volume','nexus-data','/var/lib/docker/volumes/nexus-data/_data','True','')]
        nodes={}
        edges={}
        for cid, predecessor in ((a,None),(b,a),(c,b)):
            nodes[cid]={'id':cid,'name':name if cid==c else name+'-before-'+cid[:12], 'image':img,
                        'source':predecessor,'running':cid==c,'restart':'always' if cid==c else 'no',
                        'mounts':binds}
            if predecessor:
                edges[cid]={'phase':'done','operation':{'name':name,'machine':'qa/qa','component':'nexus'},
                            'retainedName':name+'-before-'+predecessor[:12], 'sourceId':predecessor,
                            'targetContainerId':cid,'targetImageId':img}
        self.w.state['lifecycle_owned']={name:{'id':a}}
        with patch.object(life.Path,'read_text',return_value='qa'), patch.object(life.socket,'gethostname',return_value='qa'), patch.object(life,'confirmed_edge',side_effect=lambda n,cid:edges.get(cid)), patch.object(life,'node_snapshot',side_effect=nodes.get), patch.object(life,'identity',side_effect=lambda k,n:{'id':c} if n==name else None):
            self.assertEqual([('container',name,{'id':c})],life.plan(self.w))
            nodes[b]['mounts']=[]
            self.assertEqual('keep',life.plan(self.w)[0][0])
            nodes[b]['mounts']=binds
            nodes[b]['source']=None
            self.assertEqual('keep',life.plan(self.w)[0][0])
            nodes[b]['source']=a
            edges[c]['targetImageId']='sha256:'+'e'*64
            self.assertEqual('keep',life.plan(self.w)[0][0])
            edges[c]['targetImageId']=img
            edges[c]['operation']['machine']='another-machine'
            self.assertEqual('keep',life.plan(self.w)[0][0])
            edges[c]['operation']['machine']='qa/qa'
            edges.pop(b)
            self.assertEqual('keep',life.plan(self.w)[0][0])
            self.w.state['lifecycle_owned']={}
            self.assertEqual('keep',life.plan(self.w)[0][0])

    def test_purge_retry_finishes_previously_confirmed_missing_container(self):
        self.w.state['lifecycle_retired'] = {self.before['id']:self.container}
        self.w.state['lifecycle_purge_pending'] = self.before['id']
        with patch.object(life,'container_identity',return_value=None), patch.object(life,'require') as do:
            self.assertEqual([],life.purge_candidates(self.w))
        self.assertNotIn('lifecycle_purge_pending',self.w.state)
        self.assertEqual({},self.w.state['lifecycle_retired'])
        do.assert_not_called()

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid()==0, 'Rocky root 역할 저장소 시험')
    def test_real_wizard_reset_and_next_start_preserve_keys_and_unknown_files(self):
        spec = importlib.util.spec_from_file_location('reset_test_wizard', str(
            Path(__file__).resolve().parents[1] / 'interactive_install.py'))
        core = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(core)
        with tempfile.TemporaryDirectory(dir='/root') as temp:
            base = Path(temp)
            wizard = core.Wizard(base, state_base=base/'state')
            wizard.directory = wizard.base/'deploy'
            wizard.identity='qa-machine'
            wizard.state=dict(self.w.state, version=core.VERSION, role='deploy', identity=wizard.identity,
                              package=str(wizard.package))
            wizard.save()
            valid=wizard.directory/'component-nexus.status'
            invalid=wizard.directory/'component-docker.status'
            unknown=wizard.directory/'component-customer.status'
            for file,text in [(valid,'FAILED\n'), (invalid,'USER DATA\n'), (unknown,'VERIFIED\n')]:
                file.write_text(text)
                file.chmod(0o600)
            original=wizard.state['values'].copy()
            wizard.io=Mock()
            wizard.io.ask.return_value='1'
            wizard.io.confirm.return_value=True
            with patch.object(wizard,'show_runtime') as show:
                life.manage(wizard)
                show.assert_called_once_with()
            restarted=core.Wizard(base,state_base=wizard.base)
            restarted.identity=wizard.identity
            loaded=restarted.load('deploy')
            self.assertEqual({},loaded['stages'])
            self.assertEqual(original,loaded['values'])
            self.assertTrue(loaded['installation_started'])
            self.assertFalse(valid.exists())
            self.assertTrue(invalid.exists())
            self.assertTrue(unknown.exists())

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid()==0,
                         '실제 Linux root 경로 권한 시험')
    def test_actual_files_refuse_symlink_hardlink_and_preserve_unknown(self):
        import os
        with tempfile.TemporaryDirectory(dir='/root') as root:
            role=Path(root)
            app=role/'owned-helper'
            original=role/'existing-user-file'
            original.write_text('belongs to customer')
            entries=[('file',str(app)), ('file',str(original))]
            with patch.object(life,'assets',return_value=entries):
                life.before_install(self.w)
                app.write_text('installer helper')
                life.after_install(self.w)
                preview=life.plan(self.w)
                self.assertEqual(['file','keep'],[k for k,n,r in preview])
                life.remove_runtime(self.w,preview)
                self.assertFalse(app.exists())
                self.assertEqual('belongs to customer',original.read_text())
            app.symlink_to(original)
            self.assertEqual(life.UNKNOWN,life.file_identity(str(app)))
            app.unlink()
            os.link(str(original),str(app))
            self.assertEqual(life.UNKNOWN,life.file_identity(str(app)))

if __name__=='__main__':
    unittest.main()
