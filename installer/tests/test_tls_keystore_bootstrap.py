#!/usr/bin/env python3
"""Root-only Linux tests for the offline TLS file bootstrap; no crypto packages."""
import importlib.util
import json
import os
import shutil
import stat
import tempfile
import unittest
import zipfile

SOURCE = os.path.join(os.path.dirname(__file__), "..", "deploy-system", "install-tls-keystore.py")
SPEC = importlib.util.spec_from_file_location("tls_bootstrap", SOURCE)
tls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tls)


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root bootstrap tests")
class TlsBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="deploy-tls-test-")
        os.chmod(self.root, 0o700)
        os.mkdir(self.root + "/etc", 0o755)
        self.destination = self.root + "/etc/deploy-system/tls"
        self.target = self.destination + "/company.p12"
        self.source = self.root + "/input.p12"
        with open(self.source, "wb") as stream:
            stream.write(b"opaque-test-keystore-material")
        os.chmod(self.source, 0o600)
        self.jar = self.root + "/company.jar"
        self.group = 23232
        self.settings = {"enabled": True, "key-store": "file:" + self.target,
            "key-store-type": "PKCS12", "key-store-password": "private-password-never-output"}
        self.write_jar(self.settings)

    def tearDown(self):
        shutil.rmtree(self.root)

    def write_jar(self, settings, extra=None):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr(tls.ENTRY, json.dumps({"server": {"ssl": settings}}))
            if extra:
                archive.writestr(extra, b"internal-test-key")

    def install(self, source=None, check_only=False):
        return tls.install_keystore(self.jar, source, self.group, self.destination, check_only)

    def test_external_keystore_installs_with_app_group_and_private_permissions(self):
        result = self.install(self.source)
        self.assertTrue(result["installed"])
        for path in (os.path.dirname(self.destination), self.destination):
            info = os.stat(path)
            self.assertEqual((0, self.group, 0o750), (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)))
        info = os.stat(self.target)
        self.assertEqual((0, self.group, 0o640), (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)))
        with open(self.target, "rb") as target, open(self.source, "rb") as original:
            self.assertEqual(original.read(), target.read())

    def test_same_keystore_reinstall_is_idempotent(self):
        self.install(self.source)
        inode = os.stat(self.target).st_ino
        self.assertTrue(self.install(self.source)["preserved"])
        self.assertEqual(inode, os.stat(self.target).st_ino)

    def test_different_keystore_never_overwrites_existing(self):
        self.install(self.source)
        with open(self.source, "wb") as source:
            source.write(b"new-key-material")
        with self.assertRaises(tls.Refused):
            self.install(self.source)
        with open(self.target, "rb") as target:
            self.assertEqual(b"opaque-test-keystore-material", target.read())

    def test_missing_source_for_missing_external_file_is_actionable(self):
        with self.assertRaisesRegex(tls.Refused, "DEPLOY_TLS_KEYSTORE_FILE"):
            self.install()

    def test_existing_valid_file_is_preserved_without_source(self):
        self.install(self.source)
        self.assertTrue(self.install()["preserved"])

    def test_source_supplied_when_ssl_disabled_is_refused(self):
        self.write_jar({"enabled": False})
        with self.assertRaises(tls.Refused):
            self.install(self.source)
        self.assertFalse(os.path.exists(self.target))

    def test_plain_http_configuration_does_not_require_keystore(self):
        self.write_jar({"enabled": False})
        self.assertEqual("DISABLED", self.install()["mode"])

    def test_source_symlink_is_refused(self):
        path = self.root + "/linked.p12"
        os.symlink(self.source, path)
        with self.assertRaises(tls.Refused):
            self.install(path)

    def test_world_readable_source_is_refused(self):
        os.chmod(self.source, 0o644)
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_target_symlink_is_refused(self):
        os.makedirs(self.destination)
        os.symlink(self.source, self.target)
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_target_ancestor_symlink_is_refused(self):
        os.symlink(self.root, self.root + "/etc/deploy-system")
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_path_outside_fixed_destination_is_refused(self):
        self.settings["key-store"] = "file:" + self.root + "/outside.p12"
        self.write_jar(self.settings)
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_destination_traversal_is_refused(self):
        self.settings["key-store"] = "file:" + self.destination + "/../outside.p12"
        self.write_jar(self.settings)
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_readonly_preflight_does_not_create_tls_directories(self):
        result = self.install(self.source, check_only=True)
        self.assertTrue(result["sourceReady"])
        self.assertFalse(os.path.exists(self.destination))

    def test_classpath_keystore_must_exist_in_jar(self):
        self.write_jar({"enabled": True, "key-store": "classpath:tls/company.p12"})
        with self.assertRaises(tls.Refused):
            self.install()
        self.write_jar({"enabled": True, "key-store": "classpath:tls/company.p12"}, "BOOT-INF/classes/tls/company.p12")
        self.assertEqual("CLASSPATH", self.install()["mode"])
        with self.assertRaises(tls.Refused):
            self.install(self.source)

    def test_tls_health_requires_explicit_https_company_address(self):
        with self.assertRaisesRegex(tls.Refused, "DEPLOY_HEALTHCHECK_URL"):
            tls.validate_health(self.jar, None, None)
        with self.assertRaises(tls.Refused):
            tls.validate_health(self.jar, "http://localhost:9090/health", None)
        self.assertEqual("https://deploy.company:9443/health", tls.validate_health(self.jar, "https://deploy.company:9443/health", None))

    def test_plain_http_health_keeps_legacy_default(self):
        self.write_jar({"enabled": False})
        self.assertEqual("", tls.validate_health(self.jar, None, None))

    def test_health_url_credentials_queries_redirect_targets_and_wrong_path_refused(self):
        for value in ("https://user:secret@deploy/health", "https://deploy/health?token=secret", "https://deploy/login", "https://deploy/health#x", "https://deploy /health"):
            with self.assertRaises(tls.Refused):
                tls.validate_health(self.jar, value, None)

    def test_health_ca_is_root_controlled_regular_file(self):
        ca = self.root + "/company-ca.pem"
        with open(ca, "w") as stream:
            stream.write("test-public-ca")
        os.chmod(ca, 0o644)
        self.assertEqual("https://deploy/health", tls.validate_health(self.jar, "https://deploy/health", ca))
        os.chmod(ca, 0o666)
        with self.assertRaises(tls.Refused):
            tls.validate_health(self.jar, "https://deploy/health", ca)

    def test_health_settings_preserve_url_and_ca_without_password(self):
        ca = self.root + "/company-ca.pem"
        with open(ca, "wb") as stream:
            stream.write(b"test-public-ca")
        os.chmod(ca, 0o644)
        settings_path = self.root + "/etc/deploy-system/tls-health.json"
        value = tls.configure_health(self.jar, "https://deploy/health", ca, self.group, settings_path, self.destination)
        self.assertEqual(("https://deploy/health", value["caFile"]), tls.health_settings(self.jar, 9443, settings_path))
        self.assertTrue(os.path.isfile(value["caFile"]))
        with open(settings_path, "r") as stream:
            self.assertNotIn("private-password", stream.read())
        self.assertEqual(0o600, stat.S_IMODE(os.stat(settings_path).st_mode))

    def test_tls_patch_refuses_missing_or_different_health_configuration(self):
        with self.assertRaises(tls.Refused):
            tls.health_settings(self.jar, 9443, self.root + "/missing.json")
        settings_path = self.root + "/etc/deploy-system/tls-health.json"
        tls.configure_health(self.jar, "https://deploy/health", None, self.group, settings_path, self.destination)
        self.write_jar({"enabled": False})
        with self.assertRaises(tls.Refused):
            tls.health_settings(self.jar, 9443, settings_path)

    def test_installed_app_owned_jar_can_be_read(self):
        os.chown(self.jar, self.group, self.group)
        os.chmod(self.jar, 0o640)
        self.assertTrue(tls.read_configuration(self.jar)["enabled"])


if __name__ == "__main__":
    unittest.main()
