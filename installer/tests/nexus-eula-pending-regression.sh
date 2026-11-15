#!/usr/bin/env bash
# Executes installer control flow with fake Docker/network operations; no real services touched.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOM_NEXUS_IMAGE=fixture:nexus
. "$SCRIPT_DIR/components/nexus.sh"
task="$(mktemp -d)"; trap 'rm -rf "$task"' EXIT
info() { :; }; warn() { :; }; err() { :; }; success() { :; }
docker_available() { :; }; image_matches_archive_id() { :; }
nexus_secrets_prepare() { :; }; wait_container_contract() { :; }
ask() { printf '%s' "$3"; }
ask_confirm() { [[ "$1" == NEXUS_AUTO_SETUP ]]; }
container_exists() { [[ -f "$task/container" ]]; }
docker() {
  printf '%s\n' "$*" >> "$task/docker"
  case "$1" in run) touch "$task/container";; rm) rm "$task/container";; *) return 1;; esac
}
recreate_container_if_confirmed() { echo unexpected-recreate >> "$task/docker"; return 1; }
# Preserve the real base-contract body to test heap and independent mount/key refusal below.
eval "$(declare -f nexus_runtime_contract_base | sed '1s/nexus_runtime_contract_base/real_base_contract/')"
nexus_runtime_contract_base() {
  checks=$((checks+1))
  [[ "$drift" != before && !( "$drift" == after && "$checks" -ge 2 ) ]]
}
nexus_auto_setup() { echo setup >> "$task/docker"; return "$result"; }
reset_case() { rm -f "$task/container" "$task/docker"; checks=0; drift=''; result=0; }
run_install() { exit_result=0; nexus_install > "$task/output" 2>&1 || exit_result=$?; }
reset_case; result=20; run_install
[[ "$exit_result" == 20 && -f "$task/container" ]]
! grep -q '^rm ' "$task/docker"
# A known pending runtime resumes without replacement; setup succeeds only after human consent.
checks=0; result=0; run_install
[[ "$exit_result" == 0 && -f "$task/container" ]]
[[ "$(grep -c '^run ' "$task/docker")" == 1 ]]
! grep -q 'unexpected-recreate\|^rm ' "$task/docker"
reset_case; result=1; run_install
[[ "$exit_result" == 1 && ! -f "$task/container" ]]
grep -q '^rm -f ' "$task/docker"
# A pending response with runtime drift after setup does not retain a new container.
reset_case; result=20; drift=after; run_install
[[ "$exit_result" == 1 && ! -f "$task/container" ]]
grep -q '^rm -f ' "$task/docker"
# Existing drift refuses setup/replacement with FORCE_REINSTALL=n; existing data is preserved.
reset_case; touch "$task/container"; drift=before; run_install
[[ "$exit_result" == 1 && -f "$task/container" ]]
! grep -q '^setup\|^run \|^rm ' "$task/docker"
# Generic setup failure on an existing container never deletes it.
reset_case; touch "$task/container"; result=1; run_install
[[ "$exit_result" == 1 && -f "$task/container" ]]
! grep -q '^rm ' "$task/docker"
container_matches_archive_id() { [[ "$contract_drift" != image ]]; }
nexus_secrets_runtime_contract() { [[ "$contract_drift" != mount && "$contract_drift" != encryption ]]; }
container_runtime_healthy() { [[ "$contract_drift" != health ]]; }
container_port_binding_matches() { [[ "$contract_drift" != port ]]; }
container_environment_value() {
  [[ "$contract_drift" != heap ]] || { printf wrong; return; }
  nexus_expected_heap "${NEXUS_HEAP:-1g}"
}
curl() { [[ "$contract_drift" != http ]]; }
contract_drift=''; real_base_contract 8081 5000
for contract_drift in image mount encryption health port heap http; do
  if real_base_contract 8081 5000; then echo "accepted $contract_drift drift" >&2; exit 1; fi
done
echo 'nexus-eula-pending-regression: PASS (new pending retain, consent resume, generic cleanup, pending recheck, existing drift refusal, existing failure preservation, seven base-contract drift cases)'
