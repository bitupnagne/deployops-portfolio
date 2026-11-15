#!/usr/bin/env bash
set -euo pipefail
base="$(cd "$(dirname "$0")/.." && pwd)"
source "$base/lib/ui.sh"
info() { :; }
err() { :; }
tmp="$(mktemp -d)"
trap 'rm -f "$tmp/answers"; rmdir "$tmp"' EXIT
answer_key_allowed NEXUS_ISOLATED_RESTORE_ENABLED
printf 'NEXUS_ISOLATED_RESTORE_ENABLED=true\n' > "$tmp/answers"
load_answers "$tmp/answers"
[[ "$NEXUS_ISOLATED_RESTORE_ENABLED" == true ]]
printf 'NEXUS_ISOLATED_RESTORE_ENABLED=false\n' > "$tmp/answers"
load_answers "$tmp/answers"
[[ "$NEXUS_ISOLATED_RESTORE_ENABLED" == false ]]
printf 'NEXUS_ISOLATED_RESTORE_ENABLED=1\n' > "$tmp/answers"
if load_answers "$tmp/answers"; then exit 1; fi
printf 'RESTORE_CONFIG_PURE_CHECKS=3 PASS\n'
