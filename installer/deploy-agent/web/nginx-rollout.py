"""Temporarily drain one approved upstream. Called under the nginx agent's flock.

Only the selected managed policy is changed. Restoration requires the same
deployment token and the exact bytes written by this helper.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

CONFIG_ROOT = Path("/etc/nginx/conf.d")
STATE_ROOT = Path("/var/lib/deploy-system/nginx-rollout")
MAX_CONFIG_BYTES = 256 * 1024
MAX_STATE_BYTES = 2 * 1024 * 1024


def digest(body):
    return hashlib.sha256(body).hexdigest()


def host_key(host):
    try:
        return str(ipaddress.ip_address(host.strip("[]")))
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,253}", host):
            raise ValueError("upstream host가 올바르지 않습니다.")
        return host.lower()


def endpoint(value):
    if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
        value = value[1:-1]
    matched = re.fullmatch(r"(?:\[([^]]+)\]|([^:]+)):(\d{1,5})", value)
    if not matched or not 1 <= int(matched[3]) <= 65535:
        raise ValueError("upstream endpoint가 올바르지 않습니다.")
    return host_key(matched[1] or matched[2]), int(matched[3])


def drain_body(body, host, port, allow_zero_upstreams=False):
    text = body.decode("utf-8", errors="strict")
    # Preserve all byte content except one server statement. Quoted strings and
    # comments are tokenized so their braces/directive-looking text are inert.
    pattern = re.compile(r'''\s+|\#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{};]|[^\s{};#"']+''')
    stack, words, found, active, selected, selected_block = [], [], [], {}, None, None
    block_id = 0
    for match in pattern.finditer(text):
        word = match[0]
        if word.isspace() or word.startswith("#"):
            continue
        if word == "{":
            if not words:
                raise ValueError("잘못된 nginx 블록입니다.")
            block_id += 1
            stack.append((words[0][0], block_id)); words = []
        elif word == "}":
            if words or not stack:
                raise ValueError("잘못된 nginx 블록입니다.")
            stack.pop()
        elif word == ";":
            if stack and stack[-1][0] == "upstream" and words and words[0][0] == "server":
                if len(words) < 2:
                    raise ValueError("upstream server 주소가 없습니다.")
                address = endpoint(words[1][0])
                disabled = any(value == "down" for value, _ in words[2:])
                if not disabled:
                    active[stack[-1][1]] = active.get(stack[-1][1], 0) + 1
                if address == (host_key(host), port):
                    found.append(address)
                    if disabled:
                        raise ValueError("대상 upstream은 이미 중단 상태입니다.")
                    selected = match.start()
                    selected_block = stack[-1][1]
            words = []
        else:
            words.append((word, match.start()))
    if stack or words or len(found) != 1 or active.get(selected_block, 0) < (1 if allow_zero_upstreams else 2):
        raise ValueError("대상 upstream이 유일하지 않거나 남길 정상 upstream이 없습니다.")
    return (text[:selected] + " down" + text[selected:]).encode("utf-8")


def secure_directory(path):
    for directory in (path,) + tuple(path.parents):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0:
            raise ValueError("관리 경로는 root 소유의 실제 디렉터리여야 합니다.")
        if info.st_mode & 0o022 and not info.st_mode & stat.S_ISVTX:
            raise ValueError("관리 경로의 상위 디렉터리에 안전하지 않은 쓰기 권한이 있습니다.")


def regular_root_file(path, limit=MAX_CONFIG_BYTES):
    handle = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(handle, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError("관리 파일의 소유권·형식이 올바르지 않습니다.")
        body = stream.read(limit + 1)
    if len(body) > limit:
        raise ValueError("관리 파일이 허용 크기를 초과했습니다.")
    return body


def atomic_write(path, body, mode):
    handle, candidate = tempfile.mkstemp(prefix=".deploy-rollout-", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(body); stream.flush(); os.fsync(stream.fileno())
        os.chmod(candidate, mode)
        os.replace(candidate, path)
        directory = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(candidate):
            os.unlink(candidate)


def reload_nginx():
    for command in (("nginx", "-t"), ("systemctl", "reload", "nginx")):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            raise ValueError("nginx 문법 검사 또는 reload에 실패했습니다.")


def read_state(state, policy):
    document = json.loads(regular_root_file(state, MAX_STATE_BYTES))
    fields = {"generation", "policy", "host", "port", "before", "beforeSha", "afterSha", "mode"}
    if not isinstance(document, dict) or set(document) not in (fields, fields | {"allowZeroUpstreams"}):
        raise ValueError("롤링 복구 기록의 구조가 올바르지 않습니다.")
    if type(document.get("allowZeroUpstreams", False)) is not bool:
        raise ValueError("롤링 중단 허용 정책의 형식이 올바르지 않습니다.")
    for key in ("generation", "policy", "host", "before", "beforeSha", "afterSha"):
        if not isinstance(document[key], str):
            raise ValueError("롤링 복구 기록의 값 형식이 올바르지 않습니다.")
    if document["policy"] != policy or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", document["generation"]):
        raise ValueError("롤링 복구 기록의 정책 또는 배포 식별자가 다릅니다.")
    if type(document["port"]) is not int or not 1 <= document["port"] <= 65535:
        raise ValueError("롤링 복구 기록의 포트가 올바르지 않습니다.")
    if type(document["mode"]) is not int or not 0 <= document["mode"] <= 0o777 or document["mode"] & 0o022:
        raise ValueError("롤링 복구 기록의 파일 권한이 올바르지 않습니다.")
    original = document["before"].encode("utf-8")
    if len(original) > MAX_CONFIG_BYTES or digest(original) != document["beforeSha"]:
        raise ValueError("보존한 nginx 설정이 일치하지 않습니다.")
    if not re.match(rb"# Generated by Deploy System\. Request [1-9][0-9]*\.\n", original):
        raise ValueError("보존한 설정이 배포시스템 관리 파일이 아닙니다.")
    if digest(drain_body(original, document["host"], document["port"],
                         document.get("allowZeroUpstreams", False))) != document["afterSha"]:
        raise ValueError("롤링 후 설정 식별값이 복구 기록과 일치하지 않습니다.")
    return document


def transition(action, policy, generation, expected, host, port, allow_zero_upstreams=False):
    if action not in ("drain", "restore") or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("작업 또는 설정 식별값이 올바르지 않습니다.")
    for value in (policy, generation):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", value):
            raise ValueError("정책 또는 배포 식별자가 올바르지 않습니다.")
    config = CONFIG_ROOT / ("deploy-" + policy + ".conf")
    secure_directory(CONFIG_ROOT)
    root = STATE_ROOT
    existing = root
    while not existing.exists() and not existing.is_symlink():
        existing = existing.parent
    secure_directory(existing)
    if root.is_symlink():
        raise ValueError("롤링 상태 경로가 올바르지 않습니다.")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    secure_directory(root)
    os.chmod(root, 0o700)
    state = root / (policy + ".json")
    body = regular_root_file(config)
    mode = stat.S_IMODE(config.stat().st_mode)
    if action == "drain":
        if state.exists() or state.is_symlink():
            document = read_state(state, policy)
            if (document["generation"] == generation and document["beforeSha"] == expected
                    and host_key(document["host"]) == host_key(host) and document["port"] == port
                    and document.get("allowZeroUpstreams", False) == allow_zero_upstreams
                    and digest(body) == document["afterSha"]):
                # A lost SSH response is retryable; reload again in case interruption preceded reload.
                reload_nginx()
                print("TRAFFIC_DRAINED=true")
                return
            raise ValueError("이 정책에 미복귀 롤링이 있습니다. 기존 배포의 트래픽 복구를 먼저 수행하세요.")
        if digest(body) != expected or not re.match(rb"# Generated by Deploy System\. Request [1-9][0-9]*\.\n", body):
            raise ValueError("승인 당시 nginx 설정과 현재 파일이 다릅니다.")
        changed = drain_body(body, host, port, allow_zero_upstreams)
        document = {"generation": generation, "policy": policy, "host": host_key(host), "port": port,
                    "before": body.decode("utf-8"), "beforeSha": digest(body),
                    "afterSha": digest(changed), "mode": mode}
        if allow_zero_upstreams:
            document["allowZeroUpstreams"] = True
        atomic_write(state, json.dumps(document, ensure_ascii=False).encode("utf-8"), 0o600)
        try:
            atomic_write(config, changed, mode); reload_nginx()
        except Exception:
            atomic_write(config, body, mode)
            try:
                reload_nginx(); state.unlink()
            except Exception:
                pass  # Retain recovery record when recovery itself fails.
            raise
        print("TRAFFIC_DRAINED=true")
    else:
        if not state.exists() and not state.is_symlink():
            if digest(body) == expected and re.match(rb"# Generated by Deploy System\. Request [1-9][0-9]*\.\n", body):
                reload_nginx()
                print("TRAFFIC_RESTORED=true")
                return
            raise ValueError("트래픽 복구 기록이 없고 현재 설정도 원본과 다릅니다.")
        document = read_state(state, policy)
        if document["generation"] != generation or document["beforeSha"] != expected:
            raise ValueError("다른 배포 세대의 트래픽 복구 요청입니다.")
        if digest(body) not in (document["afterSha"], document["beforeSha"]):
            raise ValueError("롤링 도중 nginx 설정이 변경되어 자동 복구를 중단했습니다.")
        original = document["before"].encode("utf-8")
        atomic_write(config, original, document["mode"]); reload_nginx(); state.unlink()
        print("TRAFFIC_RESTORED=true")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("drain", "restore"))
    parser.add_argument("--policy", required=True)
    parser.add_argument("--generation", required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--host", default="")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--allow-zero-upstreams", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0 or not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha):
        raise ValueError("root 권한과 승인된 설정 식별값이 필요합니다.")
    transition(args.action, args.policy, args.generation, args.expected_sha, args.host, args.port, args.allow_zero_upstreams)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
