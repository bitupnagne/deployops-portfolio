#!/usr/bin/env python3
"""Execute the actual WAS defaults function with a foreign engine fixture (no Docker mutation)."""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


class LocalDockerEnvironmentTest(unittest.TestCase):
    def test_was_defaults_keep_registry_settings_but_override_remote_engine(self):
        source = (Path(__file__).resolve().parents[1] / 'deploy-agent/was/deploy-was-app.sh').read_text()
        function = re.search(r'^load_defaults\(\)\{\n.*?^\}', source, re.M | re.S)
        self.assertIsNotNone(function)
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'was.env'
            config.write_text('export DOCKER_HOST=ssh://forbidden\nexport DOCKER_CONTEXT=remote\n'
                              'export DOCKER_TLS_VERIFY=1 DOCKER_TLS=1 DOCKER_CERT_PATH=/foreign\n'
                              'REGISTRY_USERNAME=fixture-reader\nREGISTRY_PASSWORD=fixture-value\n')
            script = function.group(0) + '''
CONFIG_FILE="$1"
load_defaults
test "$DOCKER_HOST" = unix:///var/run/docker.sock
test -z "${DOCKER_CONTEXT+x}${DOCKER_TLS_VERIFY+x}${DOCKER_TLS+x}${DOCKER_CERT_PATH+x}"
test "$REGISTRY_USERNAME" = fixture-reader
test "$REGISTRY_PASSWORD" = fixture-value
test "$HEALTH_PORT" = 8080
'''
            result = subprocess.run(['bash', '-eu', '-c', script, 'fixture', str(config)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(result.returncode, 0, result.stderr.decode())


if __name__ == '__main__':
    unittest.main()
