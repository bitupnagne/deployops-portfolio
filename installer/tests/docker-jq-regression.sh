#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/components/docker.sh"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
printf '#!/bin/sh\nexit 0\n' > "$task_tmp/jq"
chmod 0555 "$task_tmp/jq"
BOM_JQ_SHA256="$(sha256sum "$task_tmp/jq" | awk '{print $1}')"
bundle_file() { printf '%s' "$task_tmp/jq"; }
err() { printf '%s\n' "$*" >&2; }
# 시스템 jq 유무에 상관없이 반입 바이너리 경로를 검사한다.
command() {
  if [[ "$*" == '-v jq' ]]; then return 1; fi
  builtin command "$@"
}
chmod() { printf called >> "$task_tmp/chmod-calls"; return 1; }
[[ "$(docker_jq_binary)" == "$task_tmp/jq" ]]
[[ ! -e "$task_tmp/chmod-calls" ]]
# 실행 가능한 파일도 해시가 다르면 거부해야 한다.
BOM_JQ_SHA256=invalid
if docker_jq_binary; then exit 1; fi
[[ ! -e "$task_tmp/chmod-calls" ]]
BOM_JQ_SHA256="$(sha256sum "$task_tmp/jq" | awk '{print $1}')"
builtin command chmod 0444 "$task_tmp/jq"
if docker_jq_binary; then exit 1; fi
[[ -s "$task_tmp/chmod-calls" ]]
# 쓰기 가능한 매체에서는 실행 권한을 보완할 수 있다.
unset -f chmod
[[ "$(docker_jq_binary)" == "$task_tmp/jq" ]]
[[ -x "$task_tmp/jq" ]]
echo 'docker-jq-regression: PASS (executable read-only media, SHA mismatch, chmod failure, writable media)'
