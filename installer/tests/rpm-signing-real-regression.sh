#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/rpm-signing-keys.sh"
key_dir="$1"; shift
verify_rpm_signing_keys "$key_dir"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
mkdir -m 700 "$task_tmp/rpmdb"
while IFS='|' read -r vendor fingerprint expected url; do
  rpmkeys --dbpath "$task_tmp/rpmdb" --import "$key_dir/$vendor.gpg"
done < <(rpm_key_contracts)
for package_dir in "$@"; do
  rpmkeys --dbpath "$task_tmp/rpmdb" --checksig "$package_dir"/*.rpm > "$task_tmp/signatures.log"
  if grep -Ev ': digests signatures OK$' "$task_tmp/signatures.log"; then
    printf 'RPM signature verification did not attest every package\n' >&2; exit 1
  fi
  printf 'Verified package signatures: %s (%s RPMs)\n' "$package_dir" "$(wc -l < "$task_tmp/signatures.log")"
done
echo 'Real isolated RPM key import and package signatures: PASS'
