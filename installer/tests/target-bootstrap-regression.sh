#!/usr/bin/env bash
# Retired SSH entry points must refuse without touching any host command.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf -- "$task_tmp"' EXIT
. "$root/lib/target-bootstrap.sh"
TARGET_SSH_CONFIG="$task_tmp/sshd_config"
printf 'Port 22\nMatch User corporate\n  PasswordAuthentication yes\n' > "$TARGET_SSH_CONFIG"
cp "$TARGET_SSH_CONFIG" "$task_tmp/original"
for tool in systemctl ssh-keygen sshd semanage firewall-cmd; do
  eval "$tool() { printf '%s\\n' '$tool' >> \"$task_tmp/mutations\"; return 99; }"
done
for entry in target_bootstrap_options_valid target_bootstrap_require_tools target_bootstrap_apply target_ssh_verify target_ssh_configure; do
  if "$entry" > "$task_tmp/result" 2>&1; then
    printf 'FAIL retired SSH entry succeeded: %s\n' "$entry" >&2
    exit 1
  fi
  grep -q 'retired' "$task_tmp/result"
  cmp "$TARGET_SSH_CONFIG" "$task_tmp/original"
  [[ ! -e "$task_tmp/mutations" ]]
done
printf 'PASS retired SSH bootstrap refuses all entry points without host mutation\n'
