#!/usr/bin/env bash
set -euo pipefail
# 상세 인증 호환 경로의 기존 회귀 검사. 기본 설치는 unsigned-install-contract.sh에서 확인.
export OFFLINE_VERIFY_AUTHENTICITY=1
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/components/target-prerequisites.sh"
rpm() { [[ "$*" == "-qa --qf %{NAME}\\n" ]]; printf '%s\n' "$mock_rpm_inventory"; }
verify_rpm_repository() { echo 'Mock unverified repository rejected' >&2; return 1; }
for mock_rpm_inventory in '' $'curl\nlibcurl\nsystemd'; do
  target_prerequisites_replace_curl_variants /unused
done
mock_rpm_inventory=libcurl-minimal
if target_prerequisites_replace_curl_variants /unused; then echo 'Accepted unverified variant repository' >&2; exit 1; fi
rpm() { return 2; }
if target_prerequisites_replace_curl_variants /unused; then echo 'Ignored RPM DB query error' >&2; exit 1; fi
# Plan mode may inspect vendor signatures in its own temporary RPM DB but must
# never bootstrap keys into the host RPM DB. Invalid repo prevents helper work.
[[ ! -e /unused ]] || { echo 'Mock repository path unexpectedly exists' >&2; exit 1; }
rpm() { printf libcurl-minimal; }
verify_rpm_repository() { :; }
target_prerequisites_verify_rpm_signatures() { :; }
bootstrap_calls=0
bootstrap_rpm_signing_keys() { bootstrap_calls=$((bootstrap_calls+1)); }
err() { printf '%s\n' "$*" >&2; }
if target_prerequisites_replace_curl_variants /unused --plan-only; then exit 1; fi
[[ "$bootstrap_calls" == 0 ]]
if target_prerequisites_replace_curl_variants /unused --apply; then exit 1; fi
[[ "$bootstrap_calls" == 1 ]]
echo 'target-curl-routing-regression: PASS (full/absent unchanged, query/verification failure, plan key imports0, apply trust bootstrap retained)'
