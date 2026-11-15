#!/usr/bin/env bash
# Nexus 3.96.0 실제 REST schema에 맞춘 폐쇄망 Maven 저장소 계약.
# 기존 저장소 데이터는 삭제하지 않는다. drift 변경은 명시적 opt-in만 허용한다.
nexus_maven_payload() {
  local jq_bin="$1" name="$2"
  case "$name" in
    maven-offline-seed|maven-releases)
      "$jq_bin" -cn --arg name "$name" '{name:$name,online:true,storage:{blobStoreName:"default",strictContentTypeValidation:true,writePolicy:"ALLOW_ONCE"},maven:{versionPolicy:"RELEASE",layoutPolicy:"STRICT",contentDisposition:"ATTACHMENT"}}' ;;
    maven-snapshots)
      "$jq_bin" -cn '{name:"maven-snapshots",online:true,storage:{blobStoreName:"default",strictContentTypeValidation:true,writePolicy:"ALLOW"},maven:{versionPolicy:"SNAPSHOT",layoutPolicy:"STRICT",contentDisposition:"ATTACHMENT"}}' ;;
    maven-public)
      "$jq_bin" -cn '{name:"maven-public",online:true,storage:{blobStoreName:"default",strictContentTypeValidation:true},group:{memberNames:["maven-releases","maven-snapshots","maven-offline-seed"]}}' ;;
    *) return 1 ;;
  esac
}

nexus_maven_contract_json() {
  local jq_bin="$1" actual="$2" expected="$3" kind="$4"
  "$jq_bin" -e --slurpfile expected "$expected" --arg kind "$kind" '
    . as $actual | $expected[0] as $want |
    .name == $want.name and .format == "maven2" and .type == $kind and
    .online == true and .storage.blobStoreName == $want.storage.blobStoreName and
    .storage.strictContentTypeValidation == true and
    (if $kind == "hosted" then
      .storage.writePolicy == $want.storage.writePolicy and
      .maven.versionPolicy == $want.maven.versionPolicy and
      .maven.layoutPolicy == "STRICT"
    else .group.memberNames == $want.group.memberNames end)
  ' "$actual" >/dev/null
}

nexus_maven_drift_guidance() {
  local jq_bin="$1" actual="$2" expected="$3" kind="$4" name="$5"
  printf 'Maven repository drift (explicit repair required): %s\n' "$name" >&2
  "$jq_bin" -r --slurpfile expected "$expected" --arg kind "$kind" '
    . as $actual | $expected[0] as $want |
    [ {field:"online",actual:$actual.online,expected:$want.online},
      {field:"storage.blobStoreName",actual:$actual.storage.blobStoreName,expected:$want.storage.blobStoreName},
      {field:"storage.strictContentTypeValidation",actual:$actual.storage.strictContentTypeValidation,expected:$want.storage.strictContentTypeValidation} ] +
    (if $kind == "hosted" then
      [ {field:"storage.writePolicy",actual:$actual.storage.writePolicy,expected:$want.storage.writePolicy},
        {field:"maven.versionPolicy",actual:$actual.maven.versionPolicy,expected:$want.maven.versionPolicy},
        {field:"maven.layoutPolicy",actual:$actual.maven.layoutPolicy,expected:$want.maven.layoutPolicy} ]
    else [ {field:"group.memberNames",actual:$actual.group.memberNames,expected:$want.group.memberNames} ] end) |
    .[] | select(.actual != .expected) |
    "  \(.field): actual=\(.actual | tojson) expected=\(.expected | tojson)"
  ' "$actual" >&2 || return 1
  printf '%s\n' \
    '기존 데이터는 변경하지 않았습니다. installer/README.md의 "Nexus Maven 저장소 구성(선택)"을 확인하세요.' \
    '정상 Nexus UI에서 위 차이를 검토·수정한 후 재실행하거나, 변경을 명시적으로 검토한 경우에만 NEXUS_MAVEN_REPAIR_DRIFT=y로 재실행하세요. 기본값 n은 유지됩니다.' >&2
}

nexus_maven_setup() (
  set -euo pipefail
  local base="$1" jq_bin="$2" name kind code method
  : "${NEXUS_ADMIN_PASSWORD:?Nexus admin credential is required}"
  [[ "$NEXUS_ADMIN_PASSWORD" != *$'\n'* && "$NEXUS_ADMIN_PASSWORD" != *$'\r'* ]] || return 1
  local task_dir escaped
  task_dir="$(mktemp -d)"
  trap 'rm -rf "$task_dir"' EXIT
  chmod 700 "$task_dir"
  escaped="${NEXUS_ADMIN_PASSWORD//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  (umask 077; printf 'user = "admin:%s"\n' "$escaped" > "$task_dir/auth")
  for name in maven-offline-seed maven-releases maven-snapshots maven-public; do
    kind=hosted
    [[ "$name" != maven-public ]] || kind=group
    nexus_maven_payload "$jq_bin" "$name" > "$task_dir/expected"
    code="$(curl --config "$task_dir/auth" -sS --max-time 30 -o "$task_dir/actual" -w '%{http_code}' \
      "$base/service/rest/v1/repositories/maven/$kind/$name")" || return 1
    case "$code" in
      200)
        if nexus_maven_contract_json "$jq_bin" "$task_dir/actual" "$task_dir/expected" "$kind"; then
          printf 'Maven repository verified: %s\n' "$name"
          continue
        fi
        [[ "${NEXUS_MAVEN_REPAIR_DRIFT:-n}" == y ]] || {
          nexus_maven_drift_guidance "$jq_bin" "$task_dir/actual" "$task_dir/expected" "$kind" "$name"
          return 1;
        }
        method=PUT ;;
      404) method=POST ;;
      *) printf 'Maven repository lookup failed: %s HTTP %s\n' "$name" "$code" >&2; return 1 ;;
    esac
    local endpoint="$base/service/rest/v1/repositories/maven/$kind"
    [[ "$method" != PUT ]] || endpoint="$endpoint/$name"
    curl --config "$task_dir/auth" -fsS --max-time 30 -X "$method" -H 'Content-Type: application/json' \
      --data-binary "@$task_dir/expected" -o /dev/null "$endpoint" || return 1
    curl --config "$task_dir/auth" -fsS --max-time 30 -o "$task_dir/actual" \
      "$base/service/rest/v1/repositories/maven/$kind/$name" || return 1
    nexus_maven_contract_json "$jq_bin" "$task_dir/actual" "$task_dir/expected" "$kind" || return 1
    printf 'Maven repository configured and verified: %s\n' "$name"
  done
)
