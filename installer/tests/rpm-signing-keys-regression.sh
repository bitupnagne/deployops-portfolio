#!/usr/bin/env bash
# 공개키 파일은 수집 결과를 입력하며 실제 target RPM DB에는 접근하지 않는다.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/rpm-signing-keys.sh"
source_keys="${1:?Usage: rpm-signing-keys-regression.sh collected-key-directory}"
verify_rpm_signing_keys "$source_keys"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
cp -r "$source_keys" "$task_tmp/keys"
rpmkeys() { printf '%s\n' "$*" >> "$task_tmp/imports"; return "${import_rc:-0}"; }
bootstrap_rpm_signing_keys "$task_tmp/keys"
[[ "$(wc -l < "$task_tmp/imports")" == 3 ]]
printf tampered >> "$task_tmp/keys/nginx.gpg"
: > "$task_tmp/imports"
if bootstrap_rpm_signing_keys "$task_tmp/keys"; then exit 1; fi
[[ ! -s "$task_tmp/imports" ]]
cp "$source_keys/nginx.gpg" "$task_tmp/keys/nginx.gpg"
sed -i 's/8540A6F1/00000000/' "$task_tmp/keys/KEYS.lock"
if bootstrap_rpm_signing_keys "$task_tmp/keys"; then exit 1; fi
[[ ! -s "$task_tmp/imports" ]]
cp "$source_keys/KEYS.lock" "$task_tmp/keys/KEYS.lock"
import_rc=1
if bootstrap_rpm_signing_keys "$task_tmp/keys"; then exit 1; fi
echo 'rpm-signing-keys-regression: PASS (actual source pins, tamper before import, contract drift, import failure)'
