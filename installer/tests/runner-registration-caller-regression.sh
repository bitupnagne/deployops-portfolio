#!/usr/bin/env bash
# Exercise the production installer caller, not just its argument builder.
# All service/network operations are fakes; no Runner is actually registered.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOM_RUNNER_IMAGE=fixture:runner
BOM_RUNNER_HELPER_IMAGE=fixture:helper
BOM_RUNNER_BUILD_IMAGE=fixture:builder
. "$SCRIPT_DIR/components/gitlab-runner.sh"
task="$(mktemp -d)"; trap 'rm -rf "$task"' EXIT
RUNNER_CONFIG="$task/config"; mkdir "$RUNNER_CONFIG"
info() { :; }; warn() { :; }; err() { :; }; success() { :; }
docker_available() { :; }; gitlab_runner_ci_preload() { :; }
image_present() { :; }; image_matches_archive_id() { :; }
container_exists() { :; }; gitlab_runner_runtime_contract() { :; }
verify_and_persist_existing_runner_policy() { :; }
ask_confirm() { [[ "$1" == GITLAB_RUNNER_REGISTER ]]; }
ask() {
  case "$1" in
    GITLAB_RUNNER_URL) printf '%s' 'http://gitlab:8929';;
    GITLAB_RUNNER_CLONE_URL) printf '%s' "$fixture_clone";;
    GITLAB_RUNNER_DOCKER_NETWORK) printf '%s' 'fixture-ci-network';;
    GITLAB_RUNNER_ID) printf '1';;
    *) printf '%s' "$3";;
  esac
}
ask_secret() { printf '%s' 'glrt-fixture'; }
# Inspect the exact argv and file produced by gitlab_runner_install.
docker() {
  [[ "$1" == exec && "$2" == -i && "$3" == "$RUNNER_CONTAINER" && "$4" == sh && "$5" == -c && "$7" == runner-register && "$8" == register ]] || return 91
  [[ "$*" != *glrt-fixture* ]] || return 90
  local secret
  IFS= read -r secret
  [[ "$secret" == glrt-fixture ]] || return 89
  shift 7
  printf '%s\n' "$@" > "$task/captured-args"
  captured_args=("$@")
  local index template='' template_count=0
  for (( index=0; index<${#captured_args[@]}; index++ )); do
    if [[ "${captured_args[index]}" == --template-config ]]; then
      template="${captured_args[index+1]}"; template_count=$((template_count+1))
    fi
  done
  [[ "$template_count" == 1 && "$template" == /etc/gitlab-runner/registration-template.* ]] || return 92
  local local_template="$RUNNER_CONFIG/${template##*/}"
  [[ -f "$local_template" && "$(stat -c '%a' "$local_template")" == 600 ]] || return 93
  [[ "$(grep -c '^\[\[runners\]\]$' "$local_template")" == 1 ]] || return 94
  cp "$local_template" "$task/captured-template"
  if [[ -n "$fixture_clone" ]]; then
    grep -Fxq "  clone_url = \"$fixture_clone\"" "$local_template" || return 95
  else
    ! grep -q 'clone_url' "$local_template" || return 96
  fi
  grep -Fxq '  pull_policy = ["never"]' "$local_template" || return 97
  grep -Fxq '  allowed_pull_policies = ["never"]' "$local_template" || return 98
  (( cli_result == 0 )) || return "$cli_result"
  # Simulate only the CLI's written config, then use the real post-registration contract.
  awk '/^\[runners.docker\]$/ { print "  executor = \"docker\"" }
       { print }
       /^\[runners.docker\]$/ { print "  image = \"fixture:builder\""; print "  helper_image = \"fixture:helper\"" }' "$local_template" > "$RUNNER_CONFIG/config.toml"
}
run_case() {
  fixture_clone="$1"; cli_result="$2"; GITLAB_RUNNER_REGISTER=y
  rm -f "$RUNNER_CONFIG/config.toml" "$task/captured-template"
  captured_args=(); result=0
  gitlab_runner_install > "$task/output" 2>&1 || result=$?
  [[ "$result" == "$cli_result" ]] || { cat "$task/output" >&2; echo "wrong registration result: $result" >&2; exit 1; }
  [[ -s "$task/captured-template" ]] || { cat "$task/output" >&2; echo 'production caller failed before correct template consumption' >&2; exit 1; }
  [[ -z "$(find "$RUNNER_CONFIG" -name 'registration-template.*' -print)" ]] || { echo 'temporary template leaked' >&2; exit 1; }
  mapfile -t captured_args < "$task/captured-args"
  local joined=" ${captured_args[*]} "
  [[ "$joined" != *glrt-fixture* && "$joined" == *' --docker-network-mode fixture-ci-network '* ]]
  [[ "$joined" == *' --docker-helper-image fixture:helper '* && "$joined" == *' --docker-pull-policy never '* ]]
  local forbidden
  for forbidden in --clone-url --tag-list --run-untagged --locked --access-level --registration-token; do
    [[ "$joined" != *" $forbidden"* ]] || { echo "reserved/unsupported option $forbidden" >&2; exit 1; }
  done
}
run_case 'http://198.18.7.20:8929' 0
run_case '' 0
run_case 'http://198.18.7.20:8929' 37
# The formerly broken 12-argument caller must now fail rather than treating URL as template.
out_args=(sentinel)
if build_runner_registration_args out_args http://gitlab:8929 glrt-fixture name fixture:builder '' docker,offline false true true http://clone:8929 /etc/gitlab-runner/template.toml; then
  echo 'extra clone argument was silently accepted' >&2; exit 1
fi
[[ "${out_args[*]}" == sentinel ]]
if build_runner_registration_args out_args http://gitlab:8929 glrt-fixture name fixture:builder '' docker,offline false true true; then
  echo 'missing template argument was silently accepted' >&2; exit 1
fi
[[ "${out_args[*]}" == sentinel ]]
echo 'runner-registration-caller-regression: PASS (production caller custom/default clone, glrt options, real local config contract, failure cleanup, arity refusal)'
