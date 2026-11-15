#!/usr/bin/env bash
# Isolated helper fixtures only: no package/service/registration changes.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task="$(mktemp -d /tmp/maintenance-version-contract.XXXXXX)"
trap '[[ "$task" == /tmp/maintenance-version-contract.* ]] && rm -rf -- "$task"' EXIT
info(){ :; }; err(){ :; }; warn(){ :; }; success(){ :; }
. "$SCRIPT_DIR/lib/os.sh"
for spec in rocky:8.10:rocky8 rocky:9.6:unknown rocky:10.0:unknown rocky:8.9:unknown rhel:9.6:unknown fedora:42:unknown ubuntu:24.04:ubuntu debian:12:ubuntu; do
  IFS=: read -r OS_ID OS_VER expected <<< "$spec"
  select_os_bundle
  [[ "$BUNDLE_OS" == "$expected" ]]
  if [[ "$expected" == unknown ]]; then ! os_supported; else os_supported; fi
done
echo 'PASS OS selection: Rocky 8.10 only for EL8; unsupported versions never use rocky8; existing Debian family preserved'

# Load production definitions without executing main, then exercise its OS gate.
# Only the script-root initializer is adapted to sourcing from /dev/fd.
sed '$d' "$SCRIPT_DIR/install.sh" | \
  sed "s|^SCRIPT_DIR=.*|SCRIPT_DIR=\"$SCRIPT_DIR\"|" > "$task/install-definitions.sh"
for mode in install validate; do
  result=0
  (
    . "$task/install-definitions.sh"
    init_ui(){ :; }; banner(){ :; }; print_env_summary(){ :; }
    detect_os(){ OS_ID=rocky; OS_VER=9.6; PRETTY_OS='Rocky Linux 9.6'; select_os_bundle; }
    require_root(){ printf mutation > "$task/mutation"; return 97; }
    validate_install_plan(){ [[ "$BUNDLE_OS" == rocky8 ]]; }
    if [[ "$mode" == validate ]]; then main --validate --bundle-os rocky8; else main --components nginx; fi
  ) > "$task/$mode.log" 2>&1 || result=$?
  if [[ "$mode" == validate ]]; then [[ "$result" == 0 ]]; else [[ "$result" == 1 ]]; fi
  [[ ! -e "$task/mutation" ]]
done
echo 'PASS production installer rejects unsupported Rocky before host changes and preserves cross-host bundle validation'

BOM_RUNNER_IMAGE=fixture:runner
BOM_RUNNER_HELPER_IMAGE=fixture:helper-v2
BOM_RUNNER_BUILD_IMAGE=fixture:builder
. "$SCRIPT_DIR/components/gitlab-runner.sh"
RUNNER_CONFIG="$task/config"; mkdir "$RUNNER_CONFIG"
cat > "$RUNNER_CONFIG/config.toml" <<'EOF'
[[runners]]
  executor = "docker"
  [runners.docker]
    image = "fixture:builder"
    helper_image = "fixture:helper-v2"
    pull_policy = ["never"]
    allowed_pull_policies = ["never"]
EOF
cp "$RUNNER_CONFIG/config.toml" "$task/good.toml"
gitlab_runner_registered_config_contract
for mode in old missing wrong-table; do
  case "$mode" in
    old) sed 's/helper-v2/helper-v1/' "$task/good.toml" > "$RUNNER_CONFIG/config.toml";;
    missing) sed '/helper_image/d' "$task/good.toml" > "$RUNNER_CONFIG/config.toml";;
    wrong-table) sed '/helper_image/d; /executor/a\  helper_image = "fixture:helper-v2"' "$task/good.toml" > "$RUNNER_CONFIG/config.toml";;
  esac
  before="$(sha256sum "$RUNNER_CONFIG/config.toml")"
  ! gitlab_runner_registered_config_contract
  # Exercise actual installer early refusal; no runtime mutation is permitted.
  docker_available(){ :; }; gitlab_runner_ci_preload(){ :; }
  docker(){ echo 'Unexpected Docker mutation' >&2; return 99; }
  ! gitlab_runner_install force
  [[ "$(sha256sum "$RUNNER_CONFIG/config.toml")" == "$before" ]]
  echo "PASS Runner $mode helper refuses update without rewriting registration"
done
echo 'maintenance-version-contract-regression: PASS (isolated helpers; not full OS upgrade acceptance)'
