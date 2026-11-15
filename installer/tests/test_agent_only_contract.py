"""Agent-only entry points and role command dispatch regression."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('worker', ROOT / 'deploy-agent/pull/deploy-pull-agent.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)

class AgentOnlyContractTest(unittest.TestCase):
    def test_infrastructure_roles_cannot_execute_application_or_shell_commands(self):
        for role in worker.INFRA_ROLES:
            executor = worker.Executor.__new__(worker.Executor)
            executor.role = role
            with self.subTest(role=role):
                self.assertEqual(executor.command(['/usr/local/deploy/bin/deploy-infrastructure.sh', 'status'])[0], 'helper')
                for command in [['ssh', 'host'], ['scp', 'a', 'b'], ['bash', '-c', 'id'], ['/usr/local/deploy/bin/deploy-was-app.sh', 'stop']]:
                    with self.assertRaises(worker.Refused):
                        executor.command(command)

    def test_infrastructure_probe_requires_helper_success(self):
        result = subprocess.CompletedProcess([], 1, b'{}', b'')
        with patch.object(worker.subprocess, 'run', return_value=result):
            self.assertFalse(worker.probe({'role':'NEXUS'})['ready'])

    def test_infrastructure_probe_checks_required_service_readiness(self):
        for payload, expected in [(b'{"ready":false}', False), (b'{}', False), (b'{"ready":true}', True)]:
            with patch.object(worker.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, payload, b'')):
                self.assertEqual(worker.probe({'role':'GITLAB'})['ready'], expected)

    def test_ssh_bootstrap_has_only_refusal_entry_points(self):
        text = (ROOT/'lib/target-bootstrap.sh').read_text(encoding='utf8')
        for mutation in ['systemctl enable sshd', 'ssh-keygen', 'authorized_keys', 'semanage port']:
            self.assertNotIn(mutation, text)

    def test_target_prerequisites_do_not_request_openssh(self):
        text=(ROOT/'components/target-prerequisites.sh').read_text(encoding='utf8')
        self.assertNotIn('openssh-', text)

if __name__ == '__main__':
    unittest.main()
