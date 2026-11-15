import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('embedded_config', ROOT / 'installer/deploy-system/embedded-config.py')
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


def fixture():
    data = json.loads((ROOT / 'config/application-install.example.yml').read_text(encoding='utf-8'))
    config.put(data, 'spring.datasource.password', 'qa-password-quote\'";&|$x')
    config.put(data, 'app.crypto.secret-key', 'qa-existing-key')
    config.put(data, 'app.sso.shared-secret', 'qa-existing-sso')
    config.put(data, 'app.bootstrap.admin-password', 'qa-admin')
    return data


class EmbeddedConfigTest(unittest.TestCase):
    def test_placeholder_template_cannot_be_deployed(self):
        with self.assertRaisesRegex(ValueError, 'Required configuration'):
            config.read(ROOT / 'config/application-install.example.yml')

    def test_generated_json_is_loaded_from_boot_jar_without_yaml_runtime_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'test.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr(config.ENTRY, json.dumps(fixture()))
            self.assertEqual(config.get(config.read(jar), 'app.crypto.secret-key'), 'qa-existing-key')

    def test_plain_jar_cannot_erase_existing_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'test.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/application.yml', 'spring: {}')
            with self.assertRaisesRegex(ValueError, 'missing or duplicated'):
                config.read(jar)

    def test_existing_db_and_key_ring_must_survive_jar_patch(self):
        for key in ('spring.datasource.url', 'spring.datasource.password', 'app.crypto.secret-key', 'app.crypto.key-id', 'app.crypto.previous-keys', 'app.sso.shared-secret'):
            current, candidate = fixture(), fixture()
            config.put(candidate, key, str(config.get(candidate, key)) + '-changed')
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'continuity mismatch'):
                config.same(current, candidate)

    def test_bootstrap_password_can_change_without_rotating_existing_keys(self):
        current, candidate = fixture(), fixture()
        config.put(candidate, 'app.bootstrap.admin-password', 'new-bootstrap-for-empty-db')
        config.same(current, candidate)

    def test_legacy_env_migration_preserves_secret_characters_without_shell_evaluation(self):
        data = fixture()
        legacy = '\n'.join(key + '=' + str(config.get(data, field)) for key, field in config.ENV.items()
                           if key not in ('SECRET_ENCRYPTION_KEY', 'SECRET_ENCRYPTION_KEY_ID', 'SECRET_PREVIOUS_KEYS', 'APP_SSO_SHARED_SECRET') and config.get(data, field) != '')
        migrated = config.legacy(io.StringIO(legacy))
        config.same(data, migrated)
        self.assertEqual(config.get(migrated, 'spring.datasource.password'), 'qa-password-quote\'";&|$x')

    def test_placeholders_are_not_interpreted_as_environment_variables_in_private_secrets(self):
        data = fixture()
        config.put(data, 'spring.datasource.password', '${UNEXPECTED}')
        with self.assertRaisesRegex(ValueError, 'Unsupported scalar'):
            config.validate(data)

    def test_boolean_and_numeric_secrets_are_rejected_without_scalar_coercion(self):
        for key, value in [('spring.datasource.password', True), ('app.crypto.secret-key', 1234), ('server.port', True)]:
            data = fixture()
            config.put(data, key, value)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'Unsupported scalar'):
                config.validate(data)


if __name__ == '__main__':
    unittest.main()
