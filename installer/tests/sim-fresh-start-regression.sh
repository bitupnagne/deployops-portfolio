#!/usr/bin/env bash
# Retired simulator must fail before any Docker or SSH operation.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
for tool in docker ssh-keygen ssh sshd; do
  printf '#!/bin/sh\nprintf "host command called\\n" >> "$SIM_CALLS"\nexit 99\n' > "$work/bin/$tool"
  chmod +x "$work/bin/$tool"
done
export SIM_CALLS="$work/calls"
for operation in keys sshd wire all dev prod depnet gitlab runner verify; do
  if PATH="$work/bin:$PATH" bash "$root/sim/up.sh" "$operation" > "$work/result" 2>&1; then
    printf 'FAIL retired simulator succeeded: %s\n' "$operation" >&2
    exit 1
  fi
  [[ ! -e "$SIM_CALLS" ]]
done
printf 'PASS retired simulation makes no host/Docker/SSH calls\n'
