#!/usr/bin/env bash
# DeployOps 통합설정에 넣을 GitLab 시스템 PAT를 저장 전에 읽기 전용으로 검증한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/release-metadata.sh"
load_release_bom "$SCRIPT_DIR/bundles/RELEASE-BOM.env" || exit 1
url=""; project_id=""; runner_id=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) url="${2:-}"; shift 2 ;;
    --project-id) project_id="${2:-}"; shift 2 ;;
    --runner-id) runner_id="${2:-}"; shift 2 ;;
    -h|--help)
      printf 'Usage: GITLAB_SYSTEM_PAT=... %s --url URL --project-id ID [--runner-id ID]\n' "$0"
      exit 0
      ;;
    *) printf '[ERROR] unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ "$url" =~ ^https?://[A-Za-z0-9._:-]+(/.*)?$ ]] || { printf '[ERROR] valid --url is required\n' >&2; exit 1; }
[[ "$project_id" =~ ^[1-9][0-9]*$ ]] || { printf '[ERROR] positive --project-id is required\n' >&2; exit 1; }
[[ -z "$runner_id" || "$runner_id" =~ ^[1-9][0-9]*$ ]] || { printf '[ERROR] --runner-id must be positive\n' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { printf '[ERROR] curl is required\n' >&2; exit 1; }
if command -v jq >/dev/null 2>&1; then
  jq_bin="$(command -v jq)"
elif [[ -x "$SCRIPT_DIR/bundles/common/jq/jq-linux-amd64" ]]; then
  jq_bin="$SCRIPT_DIR/bundles/common/jq/jq-linux-amd64"
  jq_sha="$(sha256sum "$jq_bin" | awk '{print $1}')"
  [[ "$jq_sha" == "$BOM_JQ_SHA256" ]] \
    || { printf '[ERROR] bundled jq SHA-256 does not match release BOM\n' >&2; exit 1; }
else
  printf '[ERROR] jq is required\n' >&2; exit 1
fi

token="${GITLAB_SYSTEM_PAT:-}"
if [[ -z "$token" && -t 0 ]]; then
  read -r -s -p 'DeployOps GitLab system PAT: ' token; printf '\n' >&2
fi
[[ -n "$token" ]] || { printf '[ERROR] GITLAB_SYSTEM_PAT is required\n' >&2; exit 1; }

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
api="${url%/}/api/v4"
curl_args=(-fsS --connect-timeout 5 --max-time 20 --header "PRIVATE-TOKEN: $token")
curl "${curl_args[@]}" -o "$tmpdir/token.json" "$api/personal_access_tokens/self" \
  || { printf '[ERROR] PAT self inspection failed (expired/revoked/URL)\n' >&2; exit 1; }
"$jq_bin" -e '
  .active == true and .revoked == false
  and ((.scopes // []) | index("api") != null)
  and ((.scopes // []) | index("manage_runner") != null)
' "$tmpdir/token.json" >/dev/null \
  || { printf '[ERROR] system PAT requires active api + manage_runner scopes\n' >&2; exit 1; }

curl "${curl_args[@]}" -o "$tmpdir/user.json" "$api/user" \
  || { printf '[ERROR] service account lookup failed\n' >&2; exit 1; }
"$jq_bin" -e '
  (.username | type == "string" and length > 0 and ascii_downcase != "root")
  and ((has("is_admin") | not) or .is_admin == false)
' "$tmpdir/user.json" >/dev/null \
  || { printf '[ERROR] system PAT must belong to a non-admin service account (root/admin rejected)\n' >&2; exit 1; }
user_id="$("$jq_bin" -er '.id | select(type == "number")' "$tmpdir/user.json")" \
  || { printf '[ERROR] service account ID missing\n' >&2; exit 1; }
curl "${curl_args[@]}" -o "$tmpdir/member.json" "$api/projects/$project_id/members/all/$user_id" \
  || { printf '[ERROR] project membership lookup failed\n' >&2; exit 1; }
access_level="$("$jq_bin" -er '.access_level | select(type == "number")' "$tmpdir/member.json")" \
  || { printf '[ERROR] project role missing\n' >&2; exit 1; }
(( access_level >= 40 )) \
  || { printf '[ERROR] DeployOps system account requires project Maintainer or Owner role\n' >&2; exit 1; }

if [[ -n "$runner_id" ]]; then
  curl "${curl_args[@]}" -o "$tmpdir/runner.json" "$api/runners/$runner_id" \
    || { printf '[ERROR] runner detail API failed; check manage_runner scope and project role\n' >&2; exit 1; }
  "$jq_bin" -e '.id | type == "number"' "$tmpdir/runner.json" >/dev/null \
    || { printf '[ERROR] runner detail response is invalid\n' >&2; exit 1; }
fi

printf '[OK] DeployOps GitLab system PAT: active, api+manage_runner, project access_level=%s' "$access_level"
[[ -z "$runner_id" ]] || printf ', runner detail API verified'
printf '\n'
