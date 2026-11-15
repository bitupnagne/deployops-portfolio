#!/usr/bin/env bash
# Standalone recovery scripts also work with the offline bundle's jq.
gitlab_recovery_jq() {
  local candidate bundled=0 bundle_root expected actual
  candidate="$(type -P jq || true)"
  if [[ -z "$candidate" ]]; then
    bundle_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)/bundles"
    candidate="$bundle_root/common/jq/jq-linux-amd64"
    bundled=1
  fi
  [[ -f "$candidate" ]] \
    || { echo '[ERROR] jq 실행 파일이 없습니다. 설치 패키지의 bundles/common/jq/jq-linux-amd64 실행 권한을 확인하세요.' >&2; return 1; }
  if [[ "$bundled" == 1 && "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
    expected="$(sed -n 's/^BOM_JQ_SHA256=//p' "$bundle_root/RELEASE-BOM.env" | tr -d '\r')" || return 1
    [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
    actual="$(sha256sum "$candidate" | awk '{print $1}')" || return 1
    [[ "$actual" == "$expected" ]] \
      || { echo '[ERROR] 번들 jq SHA-256이 release BOM과 다릅니다.' >&2; return 1; }
  fi
  [[ -x "$candidate" ]] || chmod +x "$candidate" 2>/dev/null \
    || { echo '[ERROR] 번들 jq 실행 권한을 준비할 수 없습니다.' >&2; return 1; }
  "$candidate" --version >/dev/null 2>&1 \
    || { echo '[ERROR] jq를 실행할 수 없습니다. 대상 OS와 반입 바이너리를 확인하세요.' >&2; return 1; }
  printf '%s\n' "$candidate"
}
