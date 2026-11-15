import unittest
from ci_bootstrap_registry import immutable_policy, manifest_reference, direct_connector


class Contracts(unittest.TestCase):
    def policy(self):
        return {'name': 'ci-bases', 'format': 'docker', 'type': 'hosted', 'online': True,
                'storage': {'writePolicy': 'ALLOW_ONCE', 'strictContentTypeValidation': True, 'latestPolicy': False}}

    def test_only_immutable_online_docker_hosted_policy_accepted(self):
        self.assertTrue(immutable_policy(self.policy(), 'ci-bases'))
        for key, bad in [('writePolicy', 'ALLOW'), ('latestPolicy', True), ('strictContentTypeValidation', False)]:
            value = self.policy(); value['storage'][key] = bad
            self.assertFalse(immutable_policy(value, 'ci-bases'))
        value = self.policy(); del value['storage']['latestPolicy']
        self.assertFalse(immutable_policy(value, 'ci-bases'))
        value = self.policy(); value['online'] = False
        self.assertFalse(immutable_policy(value, 'ci-bases'))
        self.assertFalse(immutable_policy(self.policy(), 'different'))

    def test_actual_digest_must_match_config_not_config_as_registry_digest(self):
        config = 'sha256:' + 'a' * 64
        digest = 'sha256:' + 'b' * 64
        value = {'Descriptor': {'digest': digest}, 'SchemaV2Manifest': {'config': {'digest': config}}}
        self.assertEqual(manifest_reference(value, 'registry.example/ci/builder', config), 'registry.example/ci/builder@' + digest)
        with self.assertRaises(ValueError):
            manifest_reference(value, 'registry.example/ci/builder', 'sha256:' + 'c' * 64)
        for bad in [[], {}, {'Descriptor': {'digest': 'tag-only'}}]:
            with self.assertRaises(ValueError):
                manifest_reference(bad, 'registry.example/ci/builder', config)

    def test_registry_must_bind_checked_direct_connector(self):
        value = self.policy(); value['docker'] = {'httpPort': 5000, 'httpsPort': 5443}
        self.assertTrue(direct_connector(value, 'http://nexus.example:8081', 'nexus.example:5000', True))
        self.assertTrue(direct_connector(value, 'https://nexus.example:8443', 'nexus.example:5443', False))
        for registry in ['unrelated.example:5000', 'nexus.example:5001', 'registry-alias.example:5000', 'user:pass@nexus.example:5000']:
            self.assertFalse(direct_connector(value, 'http://nexus.example:8081', registry, True))
        self.assertFalse(direct_connector(value, 'https://nexus.example:8443', 'nexus.example:5000', False))
        value['docker']['httpPort'] = None
        self.assertFalse(direct_connector(value, 'http://nexus.example:8081', 'nexus.example:5000', True))


if __name__ == '__main__':
    unittest.main()
