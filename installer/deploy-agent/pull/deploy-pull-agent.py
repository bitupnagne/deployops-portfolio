#!/usr/libexec/platform-python
"""Outbound Deploy worker. Python 3.6 stdlib; no shell and no inbound listener."""
from __future__ import print_function

import argparse
import base64
import datetime
import hashlib
import ipaddress
import json
import os
import re
import shutil
import signal
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

VERSION = "1.1.0"
MAX_OUTPUT = 2 * 1024 * 1024
MAX_JSON = 4 * 1024 * 1024
MAX_PRIVATE_JSON = 32 * 1024 * 1024  # Both bounded streams may expand sixfold under JSON escaping.
ENVIRONMENT = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
               "DOCKER_HOST": "unix:///var/run/docker.sock"}
COMMON = {"deploy-runtime.sh", "deploy-service-control.sh", "deploy-read.sh", "deploy-agent-probe"}
WAS = {"deploy-was-app.sh", "deploy-storage.sh"}
INFRA_ROLES = {"DEPLOY", "NEXUS", "GITLAB", "RUNNER"}
INFRA = {"deploy-infrastructure.sh", "deploy-agent-probe"}
WEB = {"deploy-web-app.sh", "deploy-web-static.sh", "deploy-web-proxy.sh", "deploy-nginx-policy.sh"}


class Refused(Exception):
    pass


def utc_now():
    return datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def valid_absolute(value):
    if not isinstance(value, str) or not value.startswith("/") or "\x00" in value:
        raise Refused("Absolute path required")
    if "\\" in value or any(x in (".", "..", "") for x in value.split("/")[1:]):
        raise Refused("Canonical path required")
    return value


def private_directory(path):
    path = os.path.abspath(path)
    os.makedirs(path, mode=0o700, exist_ok=True)
    cursor = path
    while True:
        info = os.lstat(cursor)
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
            raise Refused("Directory ancestry contains a link")
        sticky_parent = cursor != path and info.st_uid == 0 and info.st_mode & stat.S_ISVTX
        if info.st_uid != os.geteuid() or (info.st_mode & 0o022 and not sticky_parent):
            raise Refused("Directory ancestry is not privately controlled")
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent
    if os.stat(path).st_mode & 0o077:
        raise Refused("Private directory mode must be 0700")
    return path


def read_private_json(path):
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise Refused("Private JSON ownership/mode refused")
        content = stream.read(MAX_PRIVATE_JSON + 1)
    if len(content) > MAX_PRIVATE_JSON:
        raise Refused("Private JSON too large")
    return json.loads(content.decode("utf-8"))


def write_private_json(path, value):
    encoded = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    if len(encoded) > MAX_PRIVATE_JSON:
        raise Refused("Private JSON too large")
    parent = private_directory(os.path.dirname(os.path.abspath(path)))
    descriptor, temporary = tempfile.mkstemp(prefix=".agent-", dir=parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def base_url(value, allow_http=False):
    if not isinstance(value, str) or any(char.isspace() or ord(char) < 32 for char in value):
        raise Refused("URL whitespace is refused")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise Refused("Credential-free HTTP(S) URL required")
    if parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise Refused("Deploy base URL without query or path required")
    if parsed.scheme != "https" and not allow_http:
        raise Refused("HTTPS required; HTTP requires explicit --allow-http for isolated QA")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise Refused("Agent HTTP redirects are refused")


class Client:
    def __init__(self, config):
        self.url = base_url(config["url"], config.get("allowHttp", False))
        self.credential = config.get("credential")
        context = ssl.create_default_context(cafile=config.get("caFile") or None)
        # Do not inherit system proxy variables or forward credentials on redirects.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=context), NoRedirect())

    def request(self, endpoint, data=None, artifact=False):
        headers = {"Accept": "application/octet-stream" if artifact else "application/json"}
        if self.credential:
            headers["Authorization"] = "Bearer " + self.credential
        payload = None
        if data is not None:
            payload = json.dumps(data, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.url + "/api/agent/v1" + endpoint,
            data=payload, headers=headers, method="POST" if data is not None else "GET")
        response = self.opener.open(request, timeout=30 if not artifact else 120)
        if artifact:
            return response
        with response:
            content = response.read(MAX_JSON + 1)
        if len(content) > MAX_JSON:
            raise Refused("Broker JSON too large")
        return json.loads(content.decode("utf-8")) if content else {}


class Journal:
    def __init__(self, root):
        self.root = private_directory(root)

    def path(self, task_id):
        if not isinstance(task_id, (str, int)) or not re.match(r"^[A-Za-z0-9_-]{1,80}$", str(task_id)):
            raise Refused("Invalid task ID")
        return os.path.join(self.root, str(task_id) + ".json")

    def get(self, task_id):
        path = self.path(task_id)
        return read_private_json(path) if os.path.exists(path) else None

    def put(self, task_id, value):
        write_private_json(self.path(task_id), value)

    def records(self):
        for name in sorted(os.listdir(self.root)):
            if re.match(r"^[A-Za-z0-9_-]{1,80}\.json$", name):
                yield name[:-5], read_private_json(os.path.join(self.root, name))


def task_fingerprint(task):
    # Hash includes stdin, but the journal never stores its contents.
    return hashlib.sha256(json.dumps(task, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def within(path, root):
    return path == root or path.startswith(root.rstrip("/") + "/")


class Executor:
    def __init__(self, config):
        self.role = config["role"].upper()
        if self.role not in ({"WAS", "WEB"} | INFRA_ROLES):
            raise Refused("Unsupported agent role")
        self.staging = valid_absolute(config.get("stagingRoot", "/var/tmp/deploy-ops"))
        self.read_roots = [valid_absolute(x) for x in config.get("readRoots", ["/var/log", "/var/www", "/srv/deploy-data"])]

    def stage(self, value, allow_root=False):
        value = valid_absolute(value)
        if not within(value, self.staging) or (value == self.staging and not allow_root):
            raise Refused("Path is outside the agent staging root")
        cursor = value
        while not os.path.lexists(cursor):
            cursor = os.path.dirname(cursor)
        while True:
            info = os.lstat(cursor)
            sticky_parent = not within(cursor, self.staging) and info.st_mode & stat.S_ISVTX
            if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or (info.st_mode & 0o022 and not sticky_parent):
                raise Refused("Unsafe staging path ancestry")
            parent = os.path.dirname(cursor)
            if parent == cursor:
                break
            cursor = parent
        return value

    def readable(self, value):
        value = valid_absolute(value)
        resolved = os.path.realpath(value)
        if not any(within(resolved, root) for root in self.read_roots):
            raise Refused("Read path is outside configured readRoots")
        return resolved

    def command(self, argv):
        if not isinstance(argv, list) or not argv or len(argv) > 512:
            raise Refused("Invalid argument list")
        if any(not isinstance(x, str) or "\x00" in x or len(x) > 65536 for x in argv) or sum(len(x) for x in argv) > 65536:
            raise Refused("Invalid argument")
        args = argv[2:] if argv[:2] == ["sudo", "-n"] else (argv[1:] if argv[:1] == ["sudo"] else argv[:])
        if not args:
            raise Refused("Empty command")
        permitted = INFRA if self.role in INFRA_ROLES else COMMON | (WAS if self.role == "WAS" else WEB)
        if args[0].startswith("/usr/local/deploy/bin/") and os.path.basename(args[0]) in permitted:
            if args[0] != "/usr/local/deploy/bin/" + os.path.basename(args[0]):
                raise Refused("Invalid helper path")
            return "helper", args
        if args[0] == "mkdir" and args[1:-1] in (["-p"], ["-m", "700", "-p"]):
            return "mkdir", [self.stage(args[-1], allow_root=True)]
        if args[:3] == ["rm", "-rf", "--"] and len(args) == 4:
            path = self.stage(args[3])
            if self.role != "WEB" or not re.match(r"^nginx-change-[1-9][0-9]*$", os.path.basename(path)):
                raise Refused("Only nginx staging cleanup is allowed")
            return "remove", [path]
        if args[:2] == ["tail", "-c"] and len(args) == 4 and args[2].isdigit() and 1 <= int(args[2]) <= MAX_OUTPUT:
            return "read", ["/usr/bin/tail", "-c", args[2], self.readable(args[3])]
        if len(args) == 9 and args[:2] == ["find", "-L"] and args[3:] == ["-maxdepth", "1", "-mindepth", "1", "-printf", "%y\\t%s\\t%f\\n"]:
            return "read", ["/usr/bin/find", "-L", self.readable(args[2])] + args[3:]
        raise Refused("Command is not in the agent allowlist")

    def run(self, task, client):
        started = time.monotonic()
        try:
            if task.get("kind") == "COPY":
                self.copy(task, client)
                result = {"state": "SUCCEEDED", "exitCode": 0, "stdout": "", "stderr": ""}
            elif task.get("kind") == "EXEC":
                kind, args = self.command(task.get("argv"))
                if kind == "mkdir":
                    os.makedirs(args[0], mode=0o700, exist_ok=True)
                    self.stage(args[0], allow_root=True)
                    result = {"state": "SUCCEEDED", "exitCode": 0, "stdout": "", "stderr": ""}
                elif kind == "remove":
                    if os.path.isdir(args[0]):
                        shutil.rmtree(args[0])
                    result = {"state": "SUCCEEDED", "exitCode": 0, "stdout": "", "stderr": ""}
                else:
                    result = self.process(args, task)
            else:
                raise Refused("Unknown task kind")
        except Refused as error:
            result = {"state": "FAILED", "exitCode": 126, "stdout": "", "stderr": str(error)}
        except Exception:
            # No argv, credential, input or response body appears in diagnostics.
            result = {"state": "FAILED", "exitCode": 125, "stdout": "", "stderr": "Local agent operation failed; inspect the server locally"}
        result["durationMs"] = int((time.monotonic() - started) * 1000)
        if task.get("sensitive"):
            result["stdout"] = result["stderr"] = ""
        return result

    def process(self, args, task):
        if args[0].startswith("/usr/local/deploy/bin/"):
            cursor = args[0]
            while True:
                info = os.lstat(cursor)
                if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                    raise Refused("Helper ownership refused")
                if cursor == args[0] and not stat.S_ISREG(info.st_mode):
                    raise Refused("Helper type refused")
                parent = os.path.dirname(cursor)
                if parent == cursor:
                    break
                cursor = parent
        timeout = task.get("timeoutSec", 240)
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 7200:
            raise Refused("Task timeout refused")
        encoded = task.get("stdinBase64")
        data = base64.b64decode(encoded, validate=True) if encoded is not None else b""
        if len(data) > 65536:
            raise Refused("Task input too large")
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=ENVIRONMENT, cwd="/", start_new_session=True)
        outputs = [bytearray(), bytearray()]

        def drain(stream, index):
            with stream:
                while True:
                    block = stream.read(8192)
                    if not block:
                        break
                    remaining = MAX_OUTPUT - len(outputs[index])
                    if remaining > 0 and not task.get("sensitive"):
                        outputs[index].extend(block[:remaining])

        threads = [threading.Thread(target=drain, args=(process.stdout, 0)), threading.Thread(target=drain, args=(process.stderr, 1))]
        input_error = []

        def feed():
            try:
                process.stdin.write(data)
                process.stdin.close()
            except (OSError, ValueError):
                input_error.append(True)

        writer = threading.Thread(target=feed)
        writer.daemon = True
        for thread in threads:
            thread.daemon = True
            thread.start()
        writer.start()
        try:
            code = process.wait(timeout=timeout)
            state = "SUCCEEDED" if code == 0 else "FAILED"
            writer.join(timeout=5)
            if input_error and code == 0:
                code, state = 125, "UNCERTAIN"
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code, state = 124, "UNCERTAIN"
        except Exception:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            code, state = 125, "UNCERTAIN"
        finally:
            writer.join(timeout=5)
            for thread in threads:
                thread.join(timeout=5)
        return {"state": state, "exitCode": code,
            "stdout": outputs[0].decode("utf-8", "replace"), "stderr": outputs[1].decode("utf-8", "replace")}

    def copy(self, task, client):
        file_info = task.get("file") or {}
        path = self.stage(file_info.get("path"))
        sha = file_info.get("sha256")
        size = file_info.get("size")
        if not isinstance(sha, str) or not re.match(r"^[0-9a-f]{64}$", sha) or isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise Refused("Artifact metadata refused")
        parent = os.path.dirname(path)
        os.makedirs(parent, mode=0o700, exist_ok=True)
        self.stage(parent, allow_root=True)
        if os.path.lexists(path):
            info = os.lstat(path)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
                raise Refused("Artifact destination refused")
        descriptor, temporary = tempfile.mkstemp(prefix=".download-", dir=parent)
        try:
            count = 0
            digest = hashlib.sha256()
            with os.fdopen(descriptor, "wb") as output:
                os.fchmod(output.fileno(), 0o600)
                with client.request("/tasks/{}/artifact".format(task["id"]), artifact=True) as response:
                    while True:
                        block = response.read(1024 * 1024)
                        if not block:
                            break
                        count += len(block)
                        if count > size:
                            raise Refused("Artifact size differs")
                        output.write(block)
                        digest.update(block)
                if count != size or digest.hexdigest() != sha:
                    raise Refused("Artifact checksum differs")
                output.flush()
                os.fsync(output.fileno())
            self.stage(parent, allow_root=True)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


class Worker:
    def __init__(self, config, state_root, client=None, executor=None):
        self.config = config
        self.client = client or Client(config)
        self.executor = executor or Executor(config)
        root = private_directory(state_root)
        identity = {"serverId": config.get("serverId"), "machineId": config.get("machineId"), "role": config["role"],
            "credentialSha256": hashlib.sha256(config.get("credential", "").encode("utf-8")).hexdigest()}
        identity_path = os.path.join(root, "identity.json")
        if os.path.lexists(identity_path):
            if read_private_json(identity_path) != identity:
                raise Refused("Agent state belongs to a different registration; reconcile and archive the old generation first")
        else:
            if os.path.exists(os.path.join(root, "tasks")) and os.listdir(os.path.join(root, "tasks")):
                raise Refused("Unidentified task journal must be reconciled before registration")
            write_private_json(identity_path, identity)
        self.journal = Journal(os.path.join(root, "tasks"))

    def replay(self):
        for task_id, record in self.journal.records():
            if record["phase"] == "RUNNING":
                record["phase"] = "RESULT"
                record["result"] = {"state": "UNCERTAIN", "exitCode": 125, "stdout": "", "stderr": "Worker restarted before durable completion; operation must be reconciled", "durationMs": 0}
                self.journal.put(task_id, record)
            if record["phase"] == "RESULT":
                self.client.request("/tasks/{}/result".format(task_id), record["result"])
                record["phase"] = "ACKNOWLEDGED"
                self.journal.put(task_id, record)

    def handle(self, task):
        task_id = task.get("id")
        fingerprint = task_fingerprint(task)
        record = self.journal.get(task_id)
        if record:
            if record["fingerprint"] != fingerprint:
                raise Refused("Task ID was reused for different content")
            if record["phase"] == "RUNNING":
                self.replay()
                return
            self.client.request("/tasks/{}/result".format(task_id), record["result"])
            record["phase"] = "ACKNOWLEDGED"
            self.journal.put(task_id, record)
            return
        # A durable RUNNING record precedes broker started and local execution.
        # Losing either acknowledgement therefore never causes blind re-execution.
        record = {"phase": "RUNNING", "fingerprint": fingerprint, "createdAt": utc_now()}
        self.journal.put(task_id, record)
        self.client.request("/tasks/{}/started".format(task_id), {})
        heartbeat_stop = threading.Event()

        def keep_connected():
            while not heartbeat_stop.wait(10):
                try:
                    self.heartbeat()
                except Exception:
                    pass

        heartbeat_thread = threading.Thread(target=keep_connected)
        heartbeat_thread.daemon = True
        heartbeat_thread.start()
        try:
            result = self.executor.run(task, self.client)
        finally:
            heartbeat_stop.set()
            heartbeat_thread.join(timeout=1)
        record.update({"phase": "RESULT", "result": result, "finishedAt": utc_now()})
        self.journal.put(task_id, record)
        self.client.request("/tasks/{}/result".format(task_id), result)
        record["phase"] = "ACKNOWLEDGED"
        self.journal.put(task_id, record)

    def heartbeat(self):
        readiness = probe(self.config)
        return self.client.request("/heartbeat", {"hostname": os.uname()[1], "version": VERSION, "readiness": readiness})

    def run(self, once=False):
        self.replay()
        while True:
            try:
                heartbeat = self.heartbeat()
                if heartbeat.get("state") == "ACTIVE":
                    task = self.client.request("/poll", {}).get("task")
                    if task:
                        self.handle(task)
                if once:
                    return
                time.sleep(max(1, min(int(self.config.get("pollSeconds", 2)), 30)))
            except Exception as error:
                # Never emit request URLs, payloads, credentials or raw exceptions.
                print("{} agent communication/operation unavailable ({})".format(utc_now(), type(error).__name__), flush=True)
                if once:
                    raise
                time.sleep(5)
                self.replay()


def machine_id(path="/etc/machine-id"):
    with open(path, "r") as stream:
        value = stream.read(128).strip()
    if not re.match(r"^[a-fA-F0-9]{32}$", value):
        raise Refused("Valid host machine-id required")
    return value.lower()


def installation_idle(state_root):
    tasks = os.path.join(state_root, "tasks")
    if os.path.exists(tasks):
        for task_id, record in Journal(tasks).records():
            if record.get("phase") != "ACKNOWLEDGED":
                raise Refused("Unconfirmed tasks remain; reconcile before updating the agent")


def probe(config):
    role = config["role"].upper()
    if role in INFRA_ROLES:
        helper = "/usr/local/deploy/bin/deploy-infrastructure.sh"
        try:
            result = subprocess.run([helper, "status"], input=json.dumps({"role": role}).encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20, env=ENVIRONMENT)
            detail = json.loads(result.stdout.decode("utf-8"))
            return {"ready": result.returncode == 0 and isinstance(detail, dict) and detail.get("ready") is True, "role": role, "agent": VERSION, "checks": detail}
        except Exception:
            return {"ready": False, "role": role, "agent": VERSION}
    checks = {}
    for name in (["docker"] if role == "WAS" else ["nginx", "docker"]):
        try:
            args = ["/usr/bin/docker", "version", "--format", "{{.Server.Version}}"] if name == "docker" else ["/usr/sbin/nginx", "-v"]
            result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, env=ENVIRONMENT)
            checks[name] = {"ready": result.returncode == 0, "version": (result.stdout + result.stderr).decode("utf-8", "replace")[:256].strip()}
            if name == "nginx":
                configuration = subprocess.run(["/usr/sbin/nginx", "-t"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, env=ENVIRONMENT)
                service = subprocess.run(["/usr/bin/systemctl", "is-active", "--quiet", "nginx.service"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, env=ENVIRONMENT)
                checks[name]["configurationReady"] = configuration.returncode == 0
                checks[name]["serviceReady"] = service.returncode == 0
                checks[name]["ready"] = checks[name]["ready"] and configuration.returncode == 0 and service.returncode == 0
        except Exception:
            checks[name] = {"ready": False}
    helpers = ["deploy-runtime.sh", "deploy-was-app.sh", "deploy-storage.sh"] if role == "WAS" else ["deploy-web-static.sh", "deploy-web-proxy.sh", "deploy-nginx-policy.sh"]
    checks["helpers"] = {"ready": all(os.path.isfile("/usr/local/deploy/bin/" + name) and os.access("/usr/local/deploy/bin/" + name, os.X_OK) for name in helpers)}
    # Native WEB hosts need nginx, not Docker. Report Docker separately so a
    # container deployment can reject an incompatible target before rollout.
    required_checks = ["helpers", "docker" if role == "WAS" else "nginx"]
    return {"ready": all(checks[name]["ready"] for name in required_checks), "role": role,
        "python": sys.version.split()[0], "agent": VERSION, "checks": checks}


def enroll(args):
    if os.path.lexists(args.config):
        raise Refused("Existing agent configuration preserved; use explicit re-registration after revocation")
    config = {"url": base_url(args.url, args.allow_http), "serverId": args.server_id,
        "role": args.role, "machineId": machine_id(), "allowHttp": args.allow_http,
        "caFile": args.ca_file, "stagingRoot": args.staging_root, "readRoots": args.read_root or ["/var/log", "/var/www", "/srv/deploy-data"]}
    descriptor = os.open(args.enrollment_code_file, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "r") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
            raise Refused("Enrollment code file must be root-owned mode600")
        code = source.read(4096).strip()
    if not code or len(code) > 2048:
        raise Refused("Invalid enrollment code file")
    response = Client(config).request("/enroll", {"serverId": config["serverId"], "role": config["role"],
        "machineId": config["machineId"], "hostname": os.uname()[1], "version": VERSION, "enrollmentCode": code})
    credential = response.get("credential")
    if response.get("serverId") != config["serverId"] or not isinstance(credential, str) or len(credential) < 24 or len(credential) > 2048:
        raise Refused("Broker did not return a credential")
    config["credential"] = credential
    write_private_json(args.config, config)
    print("Agent enrolled; approve the server registration in Deploy UI. Credential saved privately.")


def main():
    parser = argparse.ArgumentParser(description="Deploy outbound WAS/WEB agent")
    parser.add_argument("--config", default="/etc/deploy-system/agent.json")
    parser.add_argument("--state-root", default="/var/lib/deploy-system/agent")
    commands = parser.add_subparsers(dest="command")
    register = commands.add_parser("enroll")
    register.add_argument("--url", required=True)
    register.add_argument("--server-id", required=True, type=int)
    register.add_argument("--role", required=True, choices=["WAS", "WEB", "DEPLOY", "NEXUS", "GITLAB", "RUNNER"])
    register.add_argument("--enrollment-code-file", required=True)
    register.add_argument("--ca-file")
    register.add_argument("--allow-http", action="store_true")
    register.add_argument("--staging-root", default="/var/tmp/deploy-ops")
    register.add_argument("--read-root", action="append")
    run = commands.add_parser("run")
    run.add_argument("--once", action="store_true")
    commands.add_parser("probe")
    commands.add_parser("install-check")
    verify = commands.add_parser("verify")
    verify.add_argument("--role", required=True, choices=["WAS", "WEB", "DEPLOY", "NEXUS", "GITLAB", "RUNNER"])
    verify.add_argument("--server-id", type=int)
    verify.add_argument("--url")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise Refused("Run agent as root")
    if args.command == "enroll":
        enroll(args)
    elif args.command == "run":
        import fcntl
        root = private_directory(args.state_root)
        lock = os.open(os.path.join(root, "worker.lock"), os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            config = read_private_json(args.config)
            if machine_id() != config.get("machineId"):
                raise Refused("Host identity differs; cloned hosts must register a new agent")
            Worker(config, root).run(args.once)
        finally:
            os.close(lock)
    elif args.command == "probe":
        print(json.dumps(probe(read_private_json(args.config)), separators=(",", ":")))
    elif args.command == "verify":
        config = read_private_json(args.config)
        if config.get("role") != args.role or config.get("machineId") != machine_id():
            raise Refused("Existing agent host or role differs; explicit re-registration is required")
        if args.server_id is not None and config.get("serverId") != args.server_id:
            raise Refused("Existing agent server ID differs")
        if args.url and config.get("url") != args.url.rstrip("/"):
            raise Refused("Existing agent control URL differs")
        print("Existing agent configuration identity verified")
    elif args.command == "install-check":
        installation_idle(args.state_root)
        print("Agent task journal is idle")
    else:
        parser.error("enroll or run required")


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        message = str(exception) if isinstance(exception, Refused) else type(exception).__name__
        print("Agent stopped: {}".format(message), file=sys.stderr)
        sys.exit(1)
