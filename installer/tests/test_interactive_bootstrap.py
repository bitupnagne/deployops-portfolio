#!/usr/bin/env python3
"""Native Linux temp-directory tests; no Docker, DB, systemd or network changes."""
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


b = load("interactive_bootstrap", ROOT / "installer/interactive_bootstrap.py")
builder = load("build_interactive_installer", ROOT / "scripts/build-interactive-installer.py")


class CopyProgressTest(unittest.TestCase):
    def test_single_file_reports_after_five_seconds_and_preserves_bytes(self):
        payload = b'x' * (2 * 1024 * 1024 + 17)
        output, messages = io.BytesIO(), []
        state = {'bytes': 0, 'reported': 0, 'time': 0}
        with patch.object(b.time, 'monotonic', side_effect=[1, 6, 7]):
            b.copy_payload(io.BytesIO(payload), output, messages.append, state)
        self.assertEqual(output.getvalue(), payload)
        self.assertEqual(state['bytes'], len(payload))
        self.assertEqual(len(messages), 1)
        self.assertNotIn('%', messages[0])

    def test_byte_threshold_reports_without_waiting_five_seconds(self):
        output, messages = io.BytesIO(), []
        prior = 256 * 1024 ** 2 - 1
        state = {'bytes': prior, 'reported': 0, 'time': 10}
        with patch.object(b.time, 'monotonic', return_value=11):
            b.copy_payload(io.BytesIO(b'ab'), output, messages.append, state)
        self.assertEqual(output.getvalue(), b'ab')
        self.assertEqual(len(messages), 1)
        self.assertEqual(state['reported'], prior + 2)


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="deploy-interactive-unit-")
        self.root = Path(self.temp.name)
        self.owner = os.getuid()
        self.destination = self.root / "install"
        self.archive = self.root / "release.tar.gz"
        self.minimum = {}
        for name in b.REQUIRED:
            self.minimum[b.RELEASE + "/" + name] = b"fixture\n"
        self.minimum[b.RELEASE + "/installer/bundles/RELEASE-BOM.env"] = (
            b"BOM_RELEASE=2026.10\nBOM_DEPLOYOPS_JAR=common/deploy-system/deploy-ops.jar\n")

    def tearDown(self):
        self.temp.cleanup()

    def archive_with(self, extra=None, contents=None):
        with tarfile.open(str(self.archive), "w:gz") as archive:
            for name, data in (self.minimum if contents is None else contents).items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(data))
            for info, data in extra or []:
                archive.addfile(info, io.BytesIO(data) if data is not None else None)
        return self.archive

    def prepare(self):
        return b.prepare_archive(self.archive, self.destination, owner=self.owner)

    def assert_not_published(self):
        self.assertFalse((self.destination / b.RELEASE).exists())
        if self.destination.exists():
            self.assertEqual(list(self.destination.glob(".deploy-prepare-*")), [])

    def test_extract_complete_and_permissions(self):
        self.archive_with()
        result = self.prepare()
        self.assertEqual(result, self.destination / b.RELEASE)
        self.assertEqual(b.check_package(result, owner=self.owner), result)
        self.assertEqual(stat.S_IMODE((result / "installer/install.sh").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((result / "installer/bundles/common/jq/jq-linux-amd64").stat().st_mode), 0o755)
        self.assertEqual(stat.S_IMODE(result.stat().st_mode), 0o700)

    def test_existing_tree_is_not_replaced(self):
        self.archive_with()
        result = self.prepare()
        (result / "keep.txt").write_text("KEEP", encoding="utf-8")
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assertEqual((result / "keep.txt").read_text(), "KEEP")

    def test_atomic_publication_refuses_concurrently_created_empty_directory(self):
        source = self.root / "source"
        target = self.root / "target"
        source.mkdir()
        (source / "payload").write_text("new")
        target.mkdir()
        with self.assertRaises(OSError):
            b.publish_noreplace(source, target)
        self.assertTrue((source / "payload").exists())
        self.assertEqual(list(target.iterdir()), [])

    def test_parallel_destination_creation_preserves_others(self):
        self.archive_with()
        original = b.publish_noreplace
        def racing_publish(source, target):
            target.mkdir()
            (target / "other-installer").write_text("keep")
            return original(source, target)
        with patch.object(b, "publish_noreplace", side_effect=racing_publish), self.assertRaises(b.PreparationError):
            self.prepare()
        self.assertEqual((self.destination / b.RELEASE / "other-installer").read_text(), "keep")
        self.assertEqual(list(self.destination.glob(".deploy-prepare-*")), [])

    def test_path_escape_absolute_wrong_prefix_and_backslash_are_rejected(self):
        for name in (b.RELEASE + "/../escaped", "/tmp/escaped", "wrong/file", b.RELEASE + "\\evil", b.RELEASE + "/./file"):
            with self.subTest(name=name):
                info = tarfile.TarInfo(name)
                info.size = 1
                self.archive_with([(info, b"x")])
                with self.assertRaises(b.PreparationError):
                    self.prepare()
                self.assert_not_published()

    def test_links_devices_and_fifo_rejected(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind):
                info = tarfile.TarInfo(b.RELEASE + "/unsafe")
                info.type = kind
                info.linkname = "/etc/passwd"
                self.archive_with([(info, None)])
                with self.assertRaises(b.PreparationError):
                    self.prepare()
                self.assert_not_published()

    def test_duplicate_files_rejected(self):
        info = tarfile.TarInfo(b.RELEASE + "/installer/install.sh")
        info.size = 4
        self.archive_with([(info, b"evil")])
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assert_not_published()

    def test_missing_required_files_not_published(self):
        values = dict(self.minimum)
        del values[b.RELEASE + "/operations/configure-deploy-jar.py"]
        self.archive_with(contents=values)
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assert_not_published()

    def test_truncated_gzip_not_published(self):
        self.archive_with()
        content = self.archive.read_bytes()
        self.archive.write_bytes(content[:-6])
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assert_not_published()

    def test_crc_error_not_published(self):
        self.archive_with()
        content = bytearray(self.archive.read_bytes())
        content[-8] ^= 1
        self.archive.write_bytes(content)
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assert_not_published()

    def test_archive_changed_during_extraction_not_published(self):
        extra = []
        for index in range(250):
            info = tarfile.TarInfo(b.RELEASE + "/extra/{0}.txt".format(index))
            info.size = 1
            extra.append((info, b"x"))
        self.archive_with(extra)
        changed = [False]
        def modify(_message):
            if not changed[0]:
                current = self.archive.stat()
                os.utime(str(self.archive), ns=(current.st_atime_ns, current.st_mtime_ns + 10000000))
                changed[0] = True
        with self.assertRaises(b.PreparationError):
            b.prepare_archive(self.archive, self.destination, owner=self.owner, progress=modify)
        self.assertTrue(changed[0])
        self.assert_not_published()

    def test_existing_package_writable_file_rejected(self):
        self.archive_with()
        package = self.prepare()
        os.chmod(str(package / "installer/install.sh"), 0o666)
        with self.assertRaises(b.PreparationError):
            b.check_package(package, owner=self.owner)

    def test_existing_package_wrong_owner_rejected(self):
        self.archive_with()
        package = self.prepare()
        with self.assertRaises(b.PreparationError):
            b.check_package(package, owner=self.owner + 1)

    def test_existing_package_symlink_rejected(self):
        self.archive_with()
        package = self.prepare()
        os.symlink("/etc/passwd", str(package / "link"))
        with self.assertRaises(b.PreparationError):
            b.check_package(package, owner=self.owner)

    def test_destination_symlink_rejected(self):
        self.archive_with()
        real = self.root / "real"
        real.mkdir()
        os.symlink(str(real), str(self.destination))
        with self.assertRaises(b.PreparationError):
            self.prepare()
        self.assertEqual(list(real.iterdir()), [])

    def test_root_and_os_checks(self):
        config = self.root / "os-release"
        config.write_text('ID="rocky"\nVERSION_ID="8.10"\n')
        b.host_check(str(config), uid=0, machine="x86_64", system="Linux")
        with self.assertRaises(b.PreparationError):
            b.host_check(str(config), uid=1000, machine="x86_64", system="Linux")
        config.write_text('ID="ubuntu"\nVERSION_ID="24.04"\n')
        with self.assertRaises(b.PreparationError):
            b.host_check(str(config), uid=0, machine="x86_64", system="Linux")

    def test_cancellation_has_no_prepare_call(self):
        with patch.object(b, "prepare_archive") as prepare:
            self.assertIsNone(b.run_preparation(reader=lambda _prompt: "0", writer=lambda _text: None))
            prepare.assert_not_called()

    def test_resume_accepts_extraction_parent_and_installer_directory(self):
        self.archive_with()
        package = self.prepare()
        self.assertEqual(b.existing_package(package), package)
        self.assertEqual(b.existing_package(self.destination), package)
        self.assertEqual(b.existing_package(package / 'installer'), package)

    def test_resume_enter_reuses_recorded_package_without_touching_state(self):
        self.archive_with()
        package = self.prepare()
        state_base = self.root / 'state'
        directory = state_base / 'deploy'
        directory.mkdir(parents=True, mode=0o700)
        state_file = directory / 'state.json'
        state = {'package': str(package), 'role': 'deploy', 'values': {'password': 'preserve-secret'},
                 'stages': {'infrastructure': 'RUNNING'}}
        state_file.write_text(json.dumps(state), encoding='utf-8')
        os.chmod(str(state_file), 0o600)
        before = state_file.read_bytes()
        self.assertEqual(b.resume_default(state_base, owner=self.owner), package)
        replies = iter(['2', ''])
        with patch.object(b, 'resume_default', return_value=package), patch.object(b, 'prepare_archive') as prepare:
            self.assertEqual(b.run_preparation(reader=lambda _p: next(replies), writer=lambda _t: None), package)
            prepare.assert_not_called()
        self.assertEqual(state_file.read_bytes(), before)

    def test_resume_wrong_path_can_be_corrected_without_repeating_start_menu(self):
        self.archive_with()
        package = self.prepare()
        wrong = self.root / 'outer-r7'
        wrong.mkdir()
        messages = []
        replies = iter(['2', str(wrong), str(self.destination)])
        with patch.object(b, 'resume_default', return_value=package):
            result = b.run_preparation(reader=lambda _p: next(replies), writer=messages.append)
        self.assertEqual(result, package)
        self.assertEqual(sum('1. SFTP' in m for m in messages), 1)
        self.assertTrue(any('바깥 r7' in m for m in messages))

    def test_resume_bad_path_can_return_to_menu_and_exit(self):
        replies = iter(['2', str(self.root / 'missing'), '0', '0'])
        self.assertIsNone(b.run_preparation(reader=lambda _p: next(replies), writer=lambda _t: None))

    def test_resume_discovery_ignores_nonprivate_state_and_symlink(self):
        self.archive_with()
        package = self.prepare()
        directory = self.root / 'state' / 'deploy'
        directory.mkdir(parents=True)
        state_file = directory / 'state.json'
        state_file.write_text(json.dumps({'package': str(package)}))
        os.chmod(str(state_file), 0o644)
        self.assertEqual(b.resume_default(directory.parent, owner=self.owner), b.DESTINATION / b.RELEASE)
        state_file.unlink()
        target = self.root / 'private-state'
        target.write_text(json.dumps({'package': str(package)}))
        os.chmod(str(target), 0o600)
        state_file.symlink_to(target)
        self.assertEqual(b.resume_default(directory.parent, owner=self.owner), b.DESTINATION / b.RELEASE)

    def test_persistent_launcher_verifies_current_media_before_execution(self):
        core = load('interactive_install', ROOT / 'installer/interactive_install.py')
        with tempfile.TemporaryDirectory(dir='/root') as directory:
            base = Path(directory)
            package = base / 'release'
            package.mkdir()
            stub = package / 'Deploy-System-Interactive-Installer.sh'
            stub.write_text('#!/bin/bash\necho ORIGINAL\n')
            command = base / 'deploy-installer'
            index = base / 'state/installation.json'
            with patch.dict(sys.modules, {'interactive_install': core}):
                b.activate_package(package, index, command)
            def invoke():
                return subprocess.run([str(command)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(invoke().stdout, b'ORIGINAL\n')
            stub.write_text('#!/bin/bash\necho REPLACED\n')
            refused = invoke()
            self.assertNotEqual(refused.returncode, 0)
            self.assertNotIn(b'REPLACED', refused.stdout)
            stub.write_text('#!/bin/bash\necho ORIGINAL\n')
            package.chmod(0o777)
            self.assertNotEqual(invoke().returncode, 0)
            package.chmod(0o700)
            stub.unlink()
            stub.symlink_to('/bin/bash')
            self.assertNotEqual(invoke().returncode, 0)
            stub.unlink()
            self.assertNotEqual(invoke().returncode, 0)
            index.chmod(0o644)
            self.assertNotEqual(invoke().returncode, 0)

    def test_resume_parent_does_not_bypass_symlink_or_writable_package_checks(self):
        self.archive_with()
        package = self.prepare()
        os.chmod(str(package / 'installer/install.sh'), 0o666)
        with self.assertRaises(b.PreparationError):
            b.existing_package(self.destination)
        os.chmod(str(package / 'installer/install.sh'), 0o600)
        alias = self.root / 'alias'
        alias.symlink_to(self.destination, target_is_directory=True)
        with self.assertRaises(b.PreparationError):
            b.existing_package(alias)

    def test_builder_is_readable_syntax_valid_and_keeps_stdin_for_input(self):
        core = 'def run(package_root):\n    return 0\n'
        bootstrap = 'def main():\n    answer = input("Input: ")\n    print("RECEIVED=" + answer)\n    return 0\n'
        rendered = builder.render(core, bootstrap)
        self.assertIn(core, rendered)
        self.assertIn(bootstrap, rendered)
        self.assertNotIn("base64", rendered)
        script = self.root / "interactive.sh"
        script.write_text(rendered, encoding="utf-8")
        subprocess.run(["bash", "-n", str(script)], check=True)
        result = subprocess.run(["bash", str(script)], input="answer-from-stdin\n", stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RECEIVED=answer-from-stdin", result.stdout)
        help_result = subprocess.run(["bash", str(script), "--help"], stdout=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(help_result.returncode, 0)
        self.assertIn("root", help_result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
