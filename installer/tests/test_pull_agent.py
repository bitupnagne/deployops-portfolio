#!/usr/bin/env python3
"""Pull worker regression tests, run on Linux with Python 3.6+ (no packages)."""
import base64
import hashlib
import importlib.util
import io
import json
import os
import shutil
import tempfile
import unittest
import time
from unittest import mock

SOURCE = os.path.join(os.path.dirname(__file__), "..", "deploy-agent", "pull", "deploy-pull-agent.py")
SPEC = importlib.util.spec_from_file_location("pull_agent", SOURCE)
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)


class FakeClient:
    def __init__(self):
        self.calls = []
        self.fail_result = False
        self.fail_started = False
        self.content = b"test artifact"

    def request(self, endpoint, data=None, artifact=False):
        self.calls.append((endpoint, data))
        if endpoint.endswith("/result") and self.fail_result:
            raise OSError("network interrupted")
        if endpoint.endswith("/started") and self.fail_started:
            raise OSError("network interrupted")
        if artifact:
            return io.BytesIO(self.content)
        return {}


class FakeExecutor:
    def __init__(self):
        self.executions = 0

    def run(self, task, client):
        self.executions += 1
        return {"state": "SUCCEEDED", "exitCode": 0, "stdout": "done", "stderr": "", "durationMs": 1}


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root worker tests")
class PullWorkerTest(unittest.TestCase):
    def test_child_environment_pins_local_engine_without_inherited_context(self):
        with mock.patch.dict(os.environ, {"DOCKER_HOST": "ssh://forbidden", "DOCKER_CONTEXT": "remote",
                                         "DOCKER_CONFIG": "/tmp/foreign", "DOCKER_TLS_VERIFY": "1"}):
            self.assertEqual(agent.ENVIRONMENT["DOCKER_HOST"], "unix:///var/run/docker.sock")
            self.assertEqual(set(key for key in agent.ENVIRONMENT if key.startswith("DOCKER_")), {"DOCKER_HOST"})

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="deploy-pull-test-")
        os.chmod(self.root, 0o700)
        self.client = FakeClient()
        self.executor = FakeExecutor()
        self.config = {"url": "http://127.0.0.1:19093", "allowHttp": True, "serverId": 8, "machineId": "1" * 32, "role": "WAS", "credential": "q" * 40,
            "stagingRoot": self.root + "/stage", "readRoots": [self.root + "/logs"]}
        self.worker = agent.Worker(self.config, self.root + "/state", self.client, self.executor)
        self.task = {"id": 9, "kind": "EXEC", "argv": ["sudo", "-n", "/usr/local/deploy/bin/deploy-was-app.sh", "inspect"],
            "stdinBase64": base64.b64encode(b"secret-input").decode("ascii"), "sensitive": True, "timeoutSec": 5}

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_success_is_durable_and_duplicate_does_not_execute(self):
        self.worker.handle(self.task)
        self.worker.handle(self.task)
        self.assertEqual(1, self.executor.executions)
        self.assertEqual("ACKNOWLEDGED", self.worker.journal.get(9)["phase"])

    def test_journal_never_contains_stdin_or_credential(self):
        self.worker.handle(self.task)
        with open(self.worker.journal.path(9), "r") as source:
            contents = source.read()
        self.assertNotIn("secret-input", contents)
        self.assertNotIn(self.task["stdinBase64"], contents)
        self.assertNotIn(self.config["credential"], contents)
        self.assertEqual(0o600, os.stat(self.worker.journal.path(9)).st_mode & 0o777)

    def test_both_escaped_output_limits_round_trip_in_journal(self):
        output = "\ufffd" * agent.MAX_OUTPUT
        record = {"phase": "RESULT", "result": {"stdout": output, "stderr": output}}
        self.worker.journal.put(9, record)
        self.assertEqual(record, self.worker.journal.get(9))

    def test_lost_result_ack_replays_result_without_execution(self):
        self.client.fail_result = True
        with self.assertRaises(OSError):
            self.worker.handle(self.task)
        self.assertEqual("RESULT", self.worker.journal.get(9)["phase"])
        self.client.fail_result = False
        restarted = agent.Worker(self.config, self.root + "/state", self.client, self.executor)
        restarted.replay()
        self.assertEqual(1, self.executor.executions)
        self.assertEqual("ACKNOWLEDGED", restarted.journal.get(9)["phase"])

    def test_worker_restart_during_running_reports_uncertain(self):
        self.worker.journal.put(9, {"phase": "RUNNING", "fingerprint": agent.task_fingerprint(self.task)})
        self.worker.replay()
        self.assertEqual(0, self.executor.executions)
        self.assertEqual("UNCERTAIN", self.worker.journal.get(9)["result"]["state"])

    def test_started_ack_loss_never_executes_after_restart(self):
        self.client.fail_started = True
        with self.assertRaises(OSError):
            self.worker.handle(self.task)
        self.client.fail_started = False
        self.worker.replay()
        self.assertEqual(0, self.executor.executions)
        self.assertEqual("UNCERTAIN", self.worker.journal.get(9)["result"]["state"])

    def test_same_id_different_content_is_refused(self):
        self.worker.handle(self.task)
        changed = dict(self.task, timeoutSec=6)
        with self.assertRaises(agent.Refused):
            self.worker.handle(changed)
        self.assertEqual(1, self.executor.executions)

    def test_path_traversal_id_refused(self):
        with self.assertRaises(agent.Refused):
            self.worker.journal.get("../elsewhere")

    def test_replay_acknowledged_is_noop(self):
        self.worker.handle(self.task)
        self.client.calls[:] = []
        self.worker.replay()
        self.assertEqual([], self.client.calls)

    def test_new_registration_cannot_replay_an_old_journal(self):
        self.worker.handle(self.task)
        for changed in (dict(self.config, serverId=9), dict(self.config, credential="r" * 40), dict(self.config, role="WEB")):
            with self.assertRaises(agent.Refused):
                agent.Worker(changed, self.root + "/state", self.client, self.executor)

    def test_agent_update_refuses_unconfirmed_journal(self):
        agent.installation_idle(self.root + "/state")
        self.worker.journal.put(9, {"phase": "RUNNING", "fingerprint": agent.task_fingerprint(self.task)})
        with self.assertRaises(agent.Refused):
            agent.installation_idle(self.root + "/state")
        self.worker.replay()
        agent.installation_idle(self.root + "/state")

    def test_private_config_symlink_refused(self):
        config = self.root + "/config.json"
        agent.write_private_json(config, self.config)
        os.symlink(config, self.root + "/linked.json")
        with self.assertRaises(OSError):
            agent.read_private_json(self.root + "/linked.json")

    def test_private_config_world_readable_refused(self):
        config = self.root + "/config.json"
        agent.write_private_json(config, self.config)
        os.chmod(config, 0o644)
        with self.assertRaises(agent.Refused):
            agent.read_private_json(config)

    def test_rootowned_sticky_parent_is_accepted(self):
        self.assertEqual(self.root, agent.private_directory(self.root))

    def test_writable_state_root_refused(self):
        os.chmod(self.root, 0o777)
        with self.assertRaises(agent.Refused):
            agent.Journal(self.root)
        os.chmod(self.root, 0o700)

    def test_role_allowlist_normalizes_sudo(self):
        executor = agent.Executor(self.config)
        self.assertEqual(("helper", self.task["argv"][2:]), executor.command(self.task["argv"]))
        self.assertEqual(("helper", self.task["argv"][2:]), executor.command(["sudo"] + self.task["argv"][2:]))

    def test_opposite_role_helper_refused(self):
        executor = agent.Executor(self.config)
        with self.assertRaises(agent.Refused):
            executor.command(["sudo", "-n", "/usr/local/deploy/bin/deploy-nginx-policy.sh", "apply"])

    def test_shell_and_docker_arbitrary_commands_refused(self):
        executor = agent.Executor(self.config)
        for command in (["bash", "-c", "touch /tmp/unsafe"], ["docker", "run", "--privileged", "image"],
                        ["/bin/sh", "-c", "id"], ["sudo", "bash", "-c", "id"]):
            with self.assertRaises(agent.Refused):
                executor.command(command)

    def test_helper_traversal_refused(self):
        with self.assertRaises(agent.Refused):
            agent.Executor(self.config).command(["/usr/local/deploy/bin/../bin/deploy-was-app.sh", "inspect"])

    def test_only_staging_mkdir_allowed(self):
        executor = agent.Executor(self.config)
        self.assertEqual(("mkdir", [self.config["stagingRoot"]]), executor.command(["mkdir", "-p", self.config["stagingRoot"]]))
        with self.assertRaises(agent.Refused):
            executor.command(["mkdir", "-p", "/etc/anything"])

    def test_staging_traversal_and_symlink_refused(self):
        executor = agent.Executor(self.config)
        with self.assertRaises(agent.Refused):
            executor.stage(self.config["stagingRoot"] + "/../evil")
        os.symlink("/etc", self.config["stagingRoot"])
        with self.assertRaises(agent.Refused):
            executor.stage(self.config["stagingRoot"] + "/passwd")

    def test_cleanup_only_web_nginx_staging(self):
        executor = agent.Executor(dict(self.config, role="WEB"))
        stage = self.config["stagingRoot"]
        self.assertEqual(("remove", [stage + "/nginx-change-3"]), executor.command(["rm", "-rf", "--", stage + "/nginx-change-3"]))
        for path in (stage, stage + "/files", "/etc"):
            with self.assertRaises(agent.Refused):
                executor.command(["rm", "-rf", "--", path])

    def test_read_roots_refuse_secret_paths(self):
        executor = agent.Executor(self.config)
        with self.assertRaises(agent.Refused):
            executor.command(["tail", "-c", "16000", "/etc/shadow"])

    def test_read_root_symlink_escape_refused(self):
        os.mkdir(self.root + "/logs")
        os.symlink("/etc/shadow", self.root + "/logs/secret")
        with self.assertRaises(agent.Refused):
            agent.Executor(self.config).command(["tail", "-c", "16000", self.root + "/logs/secret"])

    def test_find_exact_form_only(self):
        executor = agent.Executor(self.config)
        path = self.root + "/logs"
        command = ["find", "-L", path, "-maxdepth", "1", "-mindepth", "1", "-printf", "%y\\t%s\\t%f\\n"]
        self.assertEqual("read", executor.command(command)[0])
        with self.assertRaises(agent.Refused):
            executor.command(command + ["-exec", "bash"])

    def test_artifact_is_streamed_and_published_0600(self):
        path = self.config["stagingRoot"] + "/data.tar"
        task = {"id": 10, "kind": "COPY", "file": {"path": path, "size": len(self.client.content), "sha256": hashlib.sha256(self.client.content).hexdigest()}}
        result = agent.Executor(self.config).run(task, self.client)
        self.assertEqual("SUCCEEDED", result["state"])
        with open(path, "rb") as source:
            self.assertEqual(self.client.content, source.read())
        self.assertEqual(0o600, os.stat(path).st_mode & 0o777)

    def test_bad_artifact_digest_does_not_replace_existing(self):
        path = self.config["stagingRoot"] + "/data.tar"
        os.makedirs(self.config["stagingRoot"], mode=0o700)
        with open(path, "wb") as target:
            target.write(b"original")
        os.chmod(path, 0o600)
        task = {"id": 10, "kind": "COPY", "file": {"path": path, "size": len(self.client.content), "sha256": "0" * 64}}
        result = agent.Executor(self.config).run(task, self.client)
        self.assertEqual("FAILED", result["state"])
        with open(path, "rb") as source:
            self.assertEqual(b"original", source.read())

    def test_oversize_artifact_does_not_publish(self):
        path = self.config["stagingRoot"] + "/data.tar"
        task = {"id": 10, "kind": "COPY", "file": {"path": path, "size": 1, "sha256": "0" * 64}}
        self.assertEqual("FAILED", agent.Executor(self.config).run(task, self.client)["state"])
        self.assertFalse(os.path.exists(path))

    def test_sensitive_refusal_output_is_empty(self):
        result = agent.Executor(self.config).run(dict(self.task, argv=["bash", "-c", "secret"]), self.client)
        self.assertEqual("FAILED", result["state"])
        self.assertEqual("", result["stdout"])
        self.assertEqual("", result["stderr"])

    def test_process_output_is_bounded_and_drained(self):
        executor = agent.Executor(self.config)
        result = executor.process(["/usr/libexec/platform-python", "-c", "import sys;sys.stdout.write('x'*3000000);sys.stderr.write('y'*3000000)"], {"timeoutSec": 10})
        self.assertEqual("SUCCEEDED", result["state"])
        self.assertEqual(agent.MAX_OUTPUT, len(result["stdout"]))
        self.assertEqual(agent.MAX_OUTPUT, len(result["stderr"]))

    def test_process_sensitive_output_is_drained_but_not_retained(self):
        executor = agent.Executor(self.config)
        result = executor.process(["/usr/libexec/platform-python", "-c", "import sys;sys.stdout.write('secret');sys.stderr.write('secret')"], {"timeoutSec": 10, "sensitive": True})
        self.assertEqual("SUCCEEDED", result["state"])
        self.assertEqual("", result["stdout"])
        self.assertEqual("", result["stderr"])

    def test_process_timeout_is_uncertain(self):
        executor = agent.Executor(self.config)
        result = executor.process(["/usr/libexec/platform-python", "-c", "import time;time.sleep(5)"], {"timeoutSec": 1})
        self.assertEqual("UNCERTAIN", result["state"])
        self.assertEqual(124, result["exitCode"])

    def test_full_stdin_cannot_bypass_timeout(self):
        executor = agent.Executor(self.config)
        started = time.monotonic()
        result = executor.process(["/usr/libexec/platform-python", "-c", "import time;time.sleep(5)"],
            {"timeoutSec": 1, "stdinBase64": base64.b64encode(b"x" * 65536).decode("ascii")})
        self.assertEqual("UNCERTAIN", result["state"])
        self.assertLess(time.monotonic() - started, 4)


class UrlTest(unittest.TestCase):
    def test_https_default(self):
        self.assertEqual("https://deploy.internal:9443", agent.base_url("https://deploy.internal:9443/"))

    def test_http_needs_explicit_opt_in(self):
        with self.assertRaises(agent.Refused):
            agent.base_url("http://deploy.internal")
        self.assertEqual("http://127.0.0.1:9090", agent.base_url("http://127.0.0.1:9090", True))

    def test_credentials_query_and_fragment_refused(self):
        for value in ("https://user:secret@deploy", "https://deploy?token=secret", "https://deploy#x", "file:///tmp/test"):
            with self.assertRaises(agent.Refused):
                agent.base_url(value)

    def test_fingerprint_covers_secret_input_without_storing_it(self):
        task = {"id": 1, "stdinBase64": "c2VjcmV0"}
        self.assertNotEqual(agent.task_fingerprint(task), agent.task_fingerprint(dict(task, stdinBase64="eA==")))
        self.assertEqual(64, len(agent.task_fingerprint(task)))

    def test_web_readiness_requires_running_nginx_and_valid_config(self):
        good = type("Result", (), {"returncode": 0, "stdout": b"nginx/1.24", "stderr": b""})()
        bad = type("Result", (), {"returncode": 3, "stdout": b"", "stderr": b""})()
        with mock.patch.object(agent.os.path, "isfile", return_value=True), mock.patch.object(agent.os, "access", return_value=True):
            with mock.patch.object(agent.subprocess, "run", side_effect=[good, good, bad]):
                result = agent.probe({"role": "WEB"})
            self.assertFalse(result["ready"])
            self.assertTrue(result["checks"]["nginx"]["configurationReady"])
            self.assertFalse(result["checks"]["nginx"]["serviceReady"])

    def test_web_readiness_succeeds_with_all_checks(self):
        good = type("Result", (), {"returncode": 0, "stdout": b"nginx/1.24", "stderr": b""})()
        with mock.patch.object(agent.os.path, "isfile", return_value=True), mock.patch.object(agent.os, "access", return_value=True), mock.patch.object(agent.subprocess, "run", return_value=good):
            self.assertTrue(agent.probe({"role": "WEB"})["ready"])


if __name__ == "__main__":
    unittest.main()
