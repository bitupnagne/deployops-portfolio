#!/usr/bin/env bash
# 실제 keygen은 임시 fixture에만 실행한다. root 소유권과 위험 mode는 별도 stat 모델로 검증한다.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_base="${TARGET_HOST_KEYS_TEST_TMPDIR:?Set a dedicated local fixture parent directory}"
[[ -d "$task_base" && ! -L "$task_base" ]] || exit 1
task_tmp="$(mktemp -d "$task_base/target-host-keys.XXXXXX")"
trap 'rm -rf -- "$task_tmp"' EXIT
. "$root/lib/target-bootstrap.sh"
KEYGEN_REAL="$(command -v ssh-keygen)"
WRONG_OWNER='' BAD_MODE_PATH='' BAD_MODE='' FAIL_TYPE='' RACE_DEST='' RACE_DIRECTORY=0
SELINUX_STATE=Disabled FAIL_RESTORECON=0 MISSING_RESTORECON=0 MISSING_MATCHPATHCON=0 RESTORECON_NO_EFFECT=0
HOST_POSIX_MODES=1
case "$(uname -s)" in MINGW*|MSYS*) HOST_POSIX_MODES=0 ;; esac
stat() {
  [[ "$1" == -c ]] || return 1
  if [[ "$2" == %u ]]; then
    if [[ "$3" == "$WRONG_OWNER" ]]; then printf '1000\n'; else printf '0\n'; fi
  elif [[ "$2" == %a && "$3" == "$BAD_MODE_PATH" ]]; then
    printf '%s\n' "$BAD_MODE"
  elif [[ "$2" == %a && "$HOST_POSIX_MODES" == 0 && "$3" == */ssh_host_*_key.pub ]]; then
    printf '644\n'
  elif [[ "$2" == %a && "$HOST_POSIX_MODES" == 0 && "$3" == */ssh_host_*_key ]]; then
    printf '600\n'
  else
    command stat "$@"
  fi
}
chmod() { printf '%s\n' "$*" >> "$task_tmp/chmod-calls"; command chmod "$@"; }
getenforce() { printf '%s\n' "$SELINUX_STATE"; }
restorecon() {
  printf '%s\n' "$@" >> "$task_tmp/restorecon-calls"
  [[ "$FAIL_RESTORECON" == 0 ]] || return 1
  [[ "$RESTORECON_NO_EFFECT" == 0 ]] || return 0
  local file
  for file in "$@"; do
    awk -v path="$file" '$0!=path' "$task_tmp/bad-contexts" > "$task_tmp/contexts-next"
    mv -- "$task_tmp/contexts-next" "$task_tmp/bad-contexts"
  done
}
matchpathcon() {
  [[ "$1" == -V ]] || return 1
  shift
  printf '%s\n' "$@" >> "$task_tmp/context-verify-calls"
  local file
  for file in "$@"; do
    if grep -Fxq "$file" "$task_tmp/bad-contexts"; then return 1; fi
  done
}
command() {
  [[ "${1:-}" == -v && "${2:-}" == restorecon && "$MISSING_RESTORECON" == 1 ]] && return 1
  [[ "${1:-}" == -v && "${2:-}" == matchpathcon && "$MISSING_MATCHPATHCON" == 1 ]] && return 1
  builtin command "$@"
}
ssh-keygen() {
  printf '%s\n' "$*" >> "$task_tmp/keygen-calls"
  if [[ "$1" == -q && "$3" == "$FAIL_TYPE" ]]; then return 1; fi
  "$KEYGEN_REAL" "$@"
}
ln() {
  if [[ -n "$RACE_DEST" && "${!#}" == "$RACE_DEST" ]]; then
    if [[ "$RACE_DIRECTORY" == 1 ]]; then mkdir -- "$RACE_DEST"; else printf 'racing-existing-key\n' > "$RACE_DEST"; fi
  fi
  command ln "$@" || return 1
  [[ "$SELINUX_STATE" == Disabled ]] || printf '%s\n' "${!#}" >> "$task_tmp/bad-contexts"
  return 0
}
expect_fail() { if "$@" > "$task_tmp/rejection.log" 2>&1; then echo 'Unexpected success' >&2; exit 1; fi; }
new_fixture() {
  TARGET_SSH_HOST_KEY_DIR="$task_tmp/$1/ssh"
  mkdir -p -- "$TARGET_SSH_HOST_KEY_DIR"
  chmod 700 -- "$task_tmp/$1" "$TARGET_SSH_HOST_KEY_DIR"
  : > "$task_tmp/keygen-calls"
  : > "$task_tmp/restorecon-calls"
  : > "$task_tmp/context-verify-calls"
  : > "$task_tmp/bad-contexts"
}
snapshot() {
  (cd "$TARGET_SSH_HOST_KEY_DIR" && find . -maxdepth 1 -type f -name 'ssh_host_*' -print0 | sort -z | xargs -0 -r sha256sum)
}
assert_no_generation() { ! grep -q '^-q ' "$task_tmp/keygen-calls"; }
new_fixture pristine
target_ssh_host_keys_ensure
[[ ! -s "$task_tmp/restorecon-calls" ]]
[[ ! -s "$task_tmp/context-verify-calls" ]]
[[ $(find "$TARGET_SSH_HOST_KEY_DIR" -maxdepth 1 -name 'ssh_host_*' | wc -l) == 6 ]]
[[ ! -e "$TARGET_SSH_HOST_KEY_DIR/ssh_host_dsa_key" ]]
[[ $(grep -c '^-q ' "$task_tmp/keygen-calls") == 3 ]]
grep -q '^-q -t rsa -b 3072 ' "$task_tmp/keygen-calls"
! grep -q -- '-A\|^-q -t dsa' "$task_tmp/keygen-calls"
[[ $("$KEYGEN_REAL" -l -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub" | awk '{print $1}') == 3072 ]]
for type in rsa ecdsa ed25519; do
  grep -q "^600 -- .*ssh_host_${type}_key$" "$task_tmp/chmod-calls"
  grep -q "^644 -- .*ssh_host_${type}_key.pub$" "$task_tmp/chmod-calls"
  if [[ "$HOST_POSIX_MODES" == 1 ]]; then
    [[ $(command stat -c %a "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key") == 600 ]]
    [[ $(command stat -c %a "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key.pub") == 644 ]]
  fi
done
snapshot > "$task_tmp/pristine-sha"
for type in rsa ecdsa ed25519; do "$KEYGEN_REAL" -l -E sha256 -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key.pub"; done > "$task_tmp/fingerprints"
: > "$task_tmp/keygen-calls"
target_ssh_host_keys_ensure
snapshot > "$task_tmp/rerun-sha"
cmp "$task_tmp/pristine-sha" "$task_tmp/rerun-sha"
for type in rsa ecdsa ed25519; do "$KEYGEN_REAL" -l -E sha256 -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key.pub"; done > "$task_tmp/rerun-fingerprints"
cmp "$task_tmp/fingerprints" "$task_tmp/rerun-fingerprints"
assert_no_generation
printf 'PASS: pristine modern inventory, RSA3072, requested modes, rerun bytes/fingerprints preserved\n'

# 기존 주석/더 큰 RSA 및 추가 알고리즘도 변경하지 않는다.
new_fixture existing
"$KEYGEN_REAL" -q -t rsa -b 4096 -N '' -C legacy-rsa -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
"$KEYGEN_REAL" -q -t ecdsa -b 384 -N '' -C legacy-ecdsa -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ecdsa_key"
chmod 644 -- "$TARGET_SSH_HOST_KEY_DIR/"*.pub
# DSA는 만들지 않는다. 이미 존재하는 추가 host key의 보존 경로를 실제 키로 검증한다.
cp "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ecdsa_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_legacy_key"
cp "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ecdsa_key.pub" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_legacy_key.pub"
snapshot > "$task_tmp/existing-sha"
target_ssh_host_keys_ensure
[[ $(grep -c '^-q ' "$task_tmp/keygen-calls") == 1 ]]
grep -q '^-q -t ed25519 ' "$task_tmp/keygen-calls"
(cd "$TARGET_SSH_HOST_KEY_DIR" && sha256sum -c "$task_tmp/existing-sha") > /dev/null
printf 'PASS: existing RSA4096/ECDSA384/additional keys preserved; only absent type generated\n'
SELINUX_STATE=Enforcing
: > "$task_tmp/keygen-calls"
target_ssh_host_keys_ensure
assert_no_generation
[[ ! -s "$task_tmp/restorecon-calls" ]]
[[ $(wc -l < "$task_tmp/context-verify-calls") == 8 ]]
SELINUX_STATE=Disabled

clone_fixture() {
  new_fixture "$1"
  cp -p -- "$task_tmp/pristine/ssh/"* "$TARGET_SSH_HOST_KEY_DIR/"
}
reject_unchanged() {
  snapshot > "$task_tmp/before-reject"
  expect_fail target_ssh_host_keys_ensure
  snapshot > "$task_tmp/after-reject"
  cmp "$task_tmp/before-reject" "$task_tmp/after-reject"
  assert_no_generation
}
clone_fixture missing-public; rm -- "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub"; reject_unchanged
clone_fixture missing-private; rm -- "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; reject_unchanged
clone_fixture mismatch
cp "$task_tmp/existing/ssh/ssh_host_rsa_key.pub" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub"
reject_unchanged
clone_fixture wrong-algorithm
cp -p "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
cp -p "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key.pub" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub"
reject_unchanged
clone_fixture wrong-owner; WRONG_OWNER="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; reject_unchanged; WRONG_OWNER=''
clone_fixture wrong-mode; BAD_MODE_PATH="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; BAD_MODE=644; reject_unchanged; BAD_MODE_PATH=''
clone_fixture writable-parent; BAD_MODE_PATH="$task_tmp/writable-parent"; BAD_MODE=777; reject_unchanged; BAD_MODE_PATH=''
clone_fixture parent-owner; WRONG_OWNER="$task_tmp/parent-owner"; reject_unchanged; WRONG_OWNER=''
clone_fixture public-mode; BAD_MODE_PATH="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub"; BAD_MODE=600; reject_unchanged; BAD_MODE_PATH=''
clone_fixture public-owner; WRONG_OWNER="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key.pub"; reject_unchanged; WRONG_OWNER=''
if [[ "$HOST_POSIX_MODES" == 1 ]]; then
  clone_fixture directory-link
  command ln -s -- "$TARGET_SSH_HOST_KEY_DIR" "$task_tmp/linked-ssh"
  TARGET_SSH_HOST_KEY_DIR="$task_tmp/linked-ssh"
  reject_unchanged
  command rm -- "$task_tmp/linked-ssh"
  clone_fixture file-link
  command rm -- "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
  command ln -s -- "$task_tmp/pristine/ssh/ssh_host_rsa_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
  reject_unchanged
elif [[ -n "${TARGET_HOST_KEYS_TEST_LINK_DIR:-}" ]]; then
  # Windows에서는 미리 준비한 빈 junction을 읽기 전용으로 검사한다.
  [[ -L "$TARGET_HOST_KEYS_TEST_LINK_DIR" ]]
  TARGET_SSH_HOST_KEY_DIR="$TARGET_HOST_KEYS_TEST_LINK_DIR"
  : > "$task_tmp/keygen-calls"
  expect_fail target_ssh_host_keys_ensure
  assert_no_generation
fi
clone_fixture not-regular; rm -- "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; mkdir "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; reject_unchanged
clone_fixture unreadable; printf 'invalid private key\n' > "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"; reject_unchanged
clone_fixture encrypted
"$KEYGEN_REAL" -p -P '' -N fixture-test-passphrase -f "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key" > /dev/null
reject_unchanged
clone_fixture extra-unsafe; mkdir "$TARGET_SSH_HOST_KEY_DIR/ssh_host_dsa_key"; reject_unchanged
clone_fixture relative-path; TARGET_SSH_HOST_KEY_DIR=relative; expect_fail target_ssh_host_keys_ensure; assert_no_generation
clone_fixture traversal; TARGET_SSH_HOST_KEY_DIR="$TARGET_SSH_HOST_KEY_DIR/../ssh"; expect_fail target_ssh_host_keys_ensure; assert_no_generation
printf 'PASS: partial/mismatched/wrong-algorithm/unsafe ownership-mode-path/link ancestry states fail closed without key writes\n'

new_fixture generation-failure
FAIL_TYPE=ed25519
expect_fail target_ssh_host_keys_ensure
[[ $(find "$TARGET_SSH_HOST_KEY_DIR" -mindepth 1 | wc -l) == 0 ]]
FAIL_TYPE=''
printf 'PASS: generation failure publishes no keys and cleans temporary staging\n'
new_fixture publication-race
RACE_DEST="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
expect_fail target_ssh_host_keys_ensure
[[ $(cat "$RACE_DEST") == racing-existing-key ]]
[[ ! -e "$RACE_DEST.pub" ]]
RACE_DEST=''
: > "$task_tmp/keygen-calls"
reject_unchanged
printf 'PASS: concurrent destination is never overwritten; partial publication is rejected on rerun\n'
new_fixture publication-directory-race
RACE_DEST="$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key"
RACE_DIRECTORY=1
expect_fail target_ssh_host_keys_ensure
[[ -d "$RACE_DEST" && $(find "$RACE_DEST" -mindepth 1 | wc -l) == 0 && ! -e "$RACE_DEST.pub" ]]
[[ $(find "$TARGET_SSH_HOST_KEY_DIR" -maxdepth 1 -name '.deployops-host-keys.*' | wc -l) == 0 ]]
RACE_DEST=''; RACE_DIRECTORY=0
printf 'PASS: racing directory rejects publication without nested writes\n'

clone_fixture enforcing-missing
rm -- "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key.pub"
snapshot > "$task_tmp/enforcing-existing-sha"
SELINUX_STATE=Enforcing
target_ssh_host_keys_ensure
(cd "$TARGET_SSH_HOST_KEY_DIR" && sha256sum -c "$task_tmp/enforcing-existing-sha") > /dev/null
printf '%s\n' "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_ed25519_key.pub" > "$task_tmp/expected-context-paths"
cmp "$task_tmp/restorecon-calls" "$task_tmp/expected-context-paths"
new_fixture permissive
SELINUX_STATE=Permissive
target_ssh_host_keys_ensure
for type in rsa ecdsa ed25519; do printf '%s\n' "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key" "$TARGET_SSH_HOST_KEY_DIR/ssh_host_${type}_key.pub"; done > "$task_tmp/expected-context-paths"
cmp "$task_tmp/restorecon-calls" "$task_tmp/expected-context-paths"
new_fixture invalid-selinux; SELINUX_STATE=invalid; expect_fail target_ssh_host_keys_ensure; assert_no_generation
new_fixture missing-restorecon; SELINUX_STATE=Enforcing; MISSING_RESTORECON=1; expect_fail target_ssh_host_keys_ensure; assert_no_generation; MISSING_RESTORECON=0
clone_fixture missing-validator; MISSING_MATCHPATHCON=1; reject_unchanged; MISSING_MATCHPATHCON=0
clone_fixture context-mismatch
printf '%s\n' "$TARGET_SSH_HOST_KEY_DIR/ssh_host_rsa_key" > "$task_tmp/bad-contexts"
reject_unchanged
[[ ! -s "$task_tmp/restorecon-calls" ]]
clone_fixture existing-invalid-selinux; SELINUX_STATE=invalid; reject_unchanged; SELINUX_STATE=Enforcing
new_fixture post-restore-mismatch
RESTORECON_NO_EFFECT=1
expect_fail target_ssh_host_keys_ensure
RESTORECON_NO_EFFECT=0
[[ $(find "$TARGET_SSH_HOST_KEY_DIR" -maxdepth 1 -name 'ssh_host_*' | wc -l) == 6 ]]
new_fixture label-failure
FAIL_RESTORECON=1
expect_fail target_ssh_host_keys_ensure
[[ $(find "$TARGET_SSH_HOST_KEY_DIR" -maxdepth 1 -name 'ssh_host_*' | wc -l) == 6 ]]
: > "$task_tmp/keygen-calls"; : > "$task_tmp/restorecon-calls"
reject_unchanged
[[ ! -s "$task_tmp/restorecon-calls" ]]
# 운영자가 문맥만 복구한 상태를 모델링한다. 다음 실행은 읽기 전용 검증만 수행한다.
: > "$task_tmp/bad-contexts"; : > "$task_tmp/context-verify-calls"
target_ssh_host_keys_ensure
assert_no_generation
[[ ! -s "$task_tmp/restorecon-calls" && $(wc -l < "$task_tmp/context-verify-calls") == 6 ]]
snapshot > "$task_tmp/after-context-recovery"
cmp "$task_tmp/before-reject" "$task_tmp/after-context-recovery"
new_fixture configure-label-failure
TARGET_SSH_CONFIG="$task_tmp/configure-label-failure/sshd_config"
TARGET_SSH_PORT=2022
printf 'Port 22\n' > "$TARGET_SSH_CONFIG"
cp "$TARGET_SSH_CONFIG" "$task_tmp/config-before-label-failure"
target_bootstrap_require_tools() { :; }
flock() { :; }
sshd() { printf 'sshd\n' >> "$task_tmp/unexpected-configuration-calls"; }
systemctl() { printf 'systemctl\n' >> "$task_tmp/unexpected-configuration-calls"; }
semanage() { printf 'semanage\n' >> "$task_tmp/unexpected-configuration-calls"; }
expect_fail target_ssh_configure
cmp "$TARGET_SSH_CONFIG" "$task_tmp/config-before-label-failure"
[[ ! -e "$task_tmp/unexpected-configuration-calls" ]]
: > "$task_tmp/keygen-calls"; : > "$task_tmp/restorecon-calls"
snapshot > "$task_tmp/config-label-key-sha"
expect_fail target_ssh_configure
cmp "$TARGET_SSH_CONFIG" "$task_tmp/config-before-label-failure"
snapshot > "$task_tmp/config-label-rerun-key-sha"
cmp "$task_tmp/config-label-key-sha" "$task_tmp/config-label-rerun-key-sha"
assert_no_generation
[[ ! -s "$task_tmp/restorecon-calls" && ! -e "$task_tmp/unexpected-configuration-calls" ]]
FAIL_RESTORECON=0
printf 'PASS: Disabled avoids labels, Enforcing/Permissive restore only generated paths; label failures precede config/services\n'
printf 'PASS: failed-label rerun/context mismatch/missing validator fail closed; manually corrected labels readonly-accept with unchanged key bytes\n'
printf 'target-host-keys regression: PASS (actual local ssh-keygen; root ownership/unsafe modes modeled; no services/network; POSIX modes=%s)\n' "$HOST_POSIX_MODES"
