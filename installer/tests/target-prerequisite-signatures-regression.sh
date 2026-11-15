#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
. "$root/components/target-prerequisites.sh"
mkdir "$task_tmp/rpms" "$task_tmp/keys"
printf 'signed' > "$task_tmp/rpms/one.rpm"
printf 'signed' > "$task_tmp/rpms/two.rpm"
FAIL_IMPORT=0
verify_rpm_signing_keys() { [[ "$1" == "$task_tmp/keys" ]]; }
rpm() { [[ " $* " == *' --initdb '* ]]; }
rpmkeys() {
  if [[ " $* " == *' --import '* ]]; then
    [[ "${*: -1}" == "$task_tmp/keys/rocky8.gpg" && "$FAIL_IMPORT" == 0 ]]; return
  fi
  printf '%s\n' "${*: -1}" >> "$task_tmp/checked"
  case "$(cat "${*: -1}")" in
    signed) printf '%s: digests signatures OK\n' "${*: -1}" ;;
    unsigned) printf '%s: digests OK\n' "${*: -1}" ;;
    wrong-key) printf '%s: digests SIGNATURES NOT OK\n' "${*: -1}"; return 1 ;;
  esac
}
expect_fail() { if "$@"; then echo 'Invalid publisher signature result accepted' >&2; exit 1; fi; }
target_prerequisites_verify_rpm_signatures "$task_tmp/rpms" "$task_tmp/keys"
[[ "$(wc -l < "$task_tmp/checked")" == 2 ]]
printf unsigned > "$task_tmp/rpms/two.rpm"
expect_fail target_prerequisites_verify_rpm_signatures "$task_tmp/rpms" "$task_tmp/keys"
printf wrong-key > "$task_tmp/rpms/two.rpm"
expect_fail target_prerequisites_verify_rpm_signatures "$task_tmp/rpms" "$task_tmp/keys"
printf signed > "$task_tmp/rpms/two.rpm"
FAIL_IMPORT=1
expect_fail target_prerequisites_verify_rpm_signatures "$task_tmp/rpms" "$task_tmp/keys"
printf 'target prerequisite publisher-result regression: PASS (mocked RPM tool, no publisher attestation)\n'
