#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
. "$SCRIPT_DIR/lib/ui.sh"
BOM_NEXUS_IMAGE=fixture
. "$SCRIPT_DIR/components/nexus.sh"
err() { printf '%s\n' "$*" >&2; }
success() { :; }
unset NEXUS_MAVEN_SETUP NEXUS_MAVEN_REPAIR_DRIFT
nexus_validate_maven_options
answer_key_allowed NEXUS_MAVEN_SETUP
answer_key_allowed NEXUS_MAVEN_REPAIR_DRIFT
nexus_setup_maven_if_selected http://fixture "$(command -v jq)"
if NEXUS_MAVEN_REPAIR_DRIFT=y nexus_validate_maven_options; then exit 1; fi
if NEXUS_MAVEN_SETUP=invalid nexus_validate_maven_options; then exit 1; fi
if NEXUS_MAVEN_SETUP=y NEXUS_AUTO_SETUP=n nexus_validate_maven_options; then exit 1; fi
if (SCRIPT_DIR="$task_tmp"; NEXUS_MAVEN_SETUP=y; nexus_validate_maven_options); then exit 1; fi
mkdir -p "$task_tmp/planbundles/common/nexus"
cp "$SCRIPT_DIR/bundles/RELEASE-BOM.env" "$task_tmp/planbundles/RELEASE-BOM.env"
touch "$task_tmp/planbundles/common/nexus/fixture.tar"
printf 'NEXUS_MAVEN_SETUP=y\nNEXUS_MAVEN_REPAIR_DRIFT=n\n' > "$task_tmp/valid.env"
env BUNDLE_ROOT="$task_tmp/planbundles" bash "$SCRIPT_DIR/install.sh" --validate \
  --bundle-os rocky8 --components nexus --answers "$task_tmp/valid.env" >/dev/null
printf 'NEXUS_MAVEN_SETUP=n\nNEXUS_MAVEN_REPAIR_DRIFT=y\n' > "$task_tmp/invalid.env"
if env BUNDLE_ROOT="$task_tmp/planbundles" bash "$SCRIPT_DIR/install.sh" --validate \
    --bundle-os rocky8 --components nexus --answers "$task_tmp/invalid.env" >/dev/null 2>&1; then
  echo 'Invalid Maven repair option passed real installer plan validation' >&2; exit 1
fi

NEXUS_MAVEN_SETUP=y
NEXUS_MAVEN_REPAIR_DRIFT=n
NEXUS_ADMIN_PASSWORD=fixture-password
nexus_validate_maven_options
fixture_jq="$(command -v jq)"
curl() {
  local method=GET outfile='' code='' url='' auth='' data=''
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -X) method="$2"; shift 2 ;;
      -o) outfile="$2"; shift 2 ;;
      -w) code="$2"; shift 2 ;;
      --config) auth="$2"; shift 2 ;;
      --data-binary) data="$2"; shift 2 ;;
      --max-time|-H|-u) shift 2 ;;
      http*) url="$1"; shift ;;
      *) shift ;;
    esac
  done
  printf '%s %s\n' "$method" "$url" >> "$task_tmp/calls"
  [[ "$method" != DELETE ]] || exit 90
  local name="${url##*/}" kind=hosted payload
  if [[ "$url" == */repositories/maven/* ]]; then
    [[ -f "$auth" && "$(stat -c %a "$auth")" == 600 ]] || return 1
    if [[ "$method" != GET ]]; then
      [[ "$method" == PUT && ( "$name" == maven-public || "$name" == maven-releases ) ]] || return 1
      [[ "$data" == @* ]] || return 1
      "$fixture_jq" -e --argjson want "$(nexus_maven_payload "$fixture_jq" "$name")" ' . == $want ' "${data#@}" >/dev/null || return 1
      touch "$task_tmp/repaired" "$task_tmp/repaired-$name"
      return 0
    fi
    [[ "$name" != maven-public ]] || kind=group
    payload="$(nexus_maven_payload "$fixture_jq" "$name")"
    printf '%s' "$payload" | "$fixture_jq" --arg kind "$kind" '. + {format:"maven2",type:$kind}' > "$outfile"
    if [[ "$name" == maven-public && -f "$task_tmp/drift" && ! -f "$task_tmp/repaired-$name" ]]; then
      "$fixture_jq" '.group.memberNames=["existing-proxy"]' "$outfile" > "$task_tmp/drift.json"
      cp "$task_tmp/drift.json" "$outfile"
    fi
    if [[ -f "$task_tmp/stock-defaults" && ! -f "$task_tmp/repaired-$name" ]]; then
      if [[ "$name" == maven-releases ]]; then
        "$fixture_jq" '.storage.strictContentTypeValidation=false' "$outfile" > "$task_tmp/stock.json"
        cp "$task_tmp/stock.json" "$outfile"
      elif [[ "$name" == maven-public ]]; then
        "$fixture_jq" '.storage.strictContentTypeValidation=false | .group.memberNames=["maven-releases","maven-snapshots","maven-central"]' "$outfile" > "$task_tmp/stock.json"
        cp "$task_tmp/stock.json" "$outfile"
      fi
    fi
    [[ -z "$code" ]] || printf 200
  elif [[ "$url" == */system/eula ]]; then printf '{"accepted":true}' > "$outfile"
  elif [[ "$url" == */security/realms/active ]]; then printf '["DockerToken"]' > "$outfile"
  elif [[ "$url" == */status/check ]]; then printf '{"Default Secret Encryption Key":{"healthy":true},"Re-encryption required":{"healthy":true}}' > "$outfile"
  else printf '{}' > "$outfile"
  fi
}
nexus_runtime_contract_base() { :; }
nexus_admin_password() { printf '%s' "$NEXUS_ADMIN_PASSWORD"; }
docker_jq_binary() { printf '%s' "$fixture_jq"; }
nexus_repository_contract_json() { :; }
container_exists() { :; }
[[ "$(nexus_status)" == installed ]]
if grep -E '^(PUT|POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
touch "$task_tmp/drift"
[[ "$(nexus_status)" == drift ]]
if nexus_setup_maven_if_selected http://fixture "$fixture_jq"; then exit 1; fi
if grep -E '^(PUT|POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
NEXUS_MAVEN_REPAIR_DRIFT=y
nexus_setup_maven_if_selected http://fixture "$fixture_jq"
[[ -f "$task_tmp/repaired" ]]
[[ "$(nexus_status)" == installed ]]
[[ "$(grep -c '^PUT ' "$task_tmp/calls")" == 1 ]]
if grep -E '^(POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
# Stock Nexus defaults: refuse without repair, explain only allowed fields, preserve content.
rm -f "$task_tmp/drift" "$task_tmp/repaired" "$task_tmp/repaired-maven-public" "$task_tmp/repaired-maven-releases"
touch "$task_tmp/stock-defaults"
printf 'existing Maven artifact bytes\n' > "$task_tmp/existing-content"
content_before="$(sha256sum "$task_tmp/existing-content")"
: > "$task_tmp/calls"
NEXUS_MAVEN_REPAIR_DRIFT=n
if nexus_setup_maven_if_selected http://fixture "$fixture_jq" 2> "$task_tmp/release-guidance"; then exit 1; fi
grep -F 'storage.strictContentTypeValidation: actual=false expected=true' "$task_tmp/release-guidance" >/dev/null
grep -F 'installer/README.md' "$task_tmp/release-guidance" >/dev/null
grep -F '정상 Nexus UI' "$task_tmp/release-guidance" >/dev/null
grep -F 'NEXUS_MAVEN_REPAIR_DRIFT=y' "$task_tmp/release-guidance" >/dev/null
if grep -E '^(PUT|POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
# With release already compliant, stock public members must independently refuse.
touch "$task_tmp/repaired-maven-releases"
if nexus_setup_maven_if_selected http://fixture "$fixture_jq" 2> "$task_tmp/public-guidance"; then exit 1; fi
grep -F 'group.memberNames: actual=["maven-releases","maven-snapshots","maven-central"] expected=["maven-releases","maven-snapshots","maven-offline-seed"]' "$task_tmp/public-guidance" >/dev/null
if grep -E '^(PUT|POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
if grep -F "$NEXUS_ADMIN_PASSWORD" "$task_tmp/release-guidance" "$task_tmp/public-guidance"; then exit 1; fi
[[ "$(sha256sum "$task_tmp/existing-content")" == "$content_before" ]]
# Explicit repair performs only the intended two PUTs, followed by successful GET validation.
rm "$task_tmp/repaired-maven-releases"
NEXUS_MAVEN_REPAIR_DRIFT=y
nexus_setup_maven_if_selected http://fixture "$fixture_jq"
[[ "$(grep -c '^PUT ' "$task_tmp/calls")" == 2 ]]
[[ "$(grep -c '^GET .*maven-releases$' "$task_tmp/calls")" -ge 3 ]]
[[ "$(grep -c '^GET .*maven-public$' "$task_tmp/calls")" -ge 3 ]]
nexus_setup_maven_if_selected http://fixture "$fixture_jq"
[[ "$(grep -c '^PUT ' "$task_tmp/calls")" == 2 ]]
if grep -E '^(POST|DELETE) ' "$task_tmp/calls"; then exit 1; fi
[[ "$(sha256sum "$task_tmp/existing-content")" == "$content_before" ]]
# setup=n retains the existing early-return behavior, regardless of stock state.
NEXUS_MAVEN_SETUP=n
NEXUS_MAVEN_REPAIR_DRIFT=n
calls_before="$(wc -l < "$task_tmp/calls")"
nexus_setup_maven_if_selected http://fixture "$fixture_jq"
[[ "$(wc -l < "$task_tmp/calls")" == "$calls_before" ]]
echo 'nexus-maven-integration-regression: PASS (opt-in, stock-default refusal/guidance, reviewed repair/reGET/idempotence, setup=n)'

