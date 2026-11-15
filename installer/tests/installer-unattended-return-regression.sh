#!/usr/bin/env bash
# Exercise the real public installer main/run_unattended wrapper. External
# validation/host operations and component installers are fixture functions.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d)"; trap 'rm -rf "$task_tmp"' EXIT
fixture="$task_tmp/installer"
mkdir -p "$fixture/"{lib,components,bundles}
cp "$root/lib/"*.sh "$fixture/lib/"
cp "$root/components/"*.sh "$fixture/components/"
cp "$root/bundles/"{RELEASE-BOM.env,RELEASE-LOCK.env} "$fixture/bundles/"
# The only modification is a fixture hook immediately before the real main
# invocation; the complete public main and unattended bodies stay byte-for-byte.
awk '$0 == "main \"$@\"" {print ". \"$RETURN_CODE_FIXTURE\""} {print}' "$root/install.sh" > "$fixture/install.sh"
[[ "$(grep -c '^\. "\$RETURN_CODE_FIXTURE"$' "$fixture/install.sh")" == 1 ]]
cat > "$fixture/verify-offline-bundle.sh" <<'SH'
#!/usr/bin/env bash
printf 'verify\n' >> "$RETURN_CODE_EVENTS"
exit 0
SH
cat > "$task_tmp/fixture-functions.sh" <<'SH'
detect_os() { OS_ID=rocky; OS_VER=8.10; OS_FAMILY=rhel; BUNDLE_OS=rocky8; PRETTY_OS='Fixture Rocky'; PKG=forbidden_package_manager; }
os_supported() { return 0; }
require_root() { :; }
# Baseline upgrade safety is tested separately; this fixture owns no services.
protect_baseline() { BASELINE_VERIFIED=""; }
assert_trusted_staging_tree() { :; }
validate_install_plan() { printf 'plan\n' >> "$RETURN_CODE_EVENTS"; return 0; }
nexus_status() { printf '%s\n' "${FIXTURE_NEXUS_STATUS:-eula-pending}"; }
docker_status() { printf 'missing\n'; }
nexus_install() { printf 'nexus\n' >> "$RETURN_CODE_EVENTS"; return "${FIXTURE_NEXUS_RC:-0}"; }
docker_install() { printf 'docker\n' >> "$RETURN_CODE_EVENTS"; return "${FIXTURE_DOCKER_RC:-0}"; }
SH
export RETURN_CODE_FIXTURE="$task_tmp/fixture-functions.sh" RETURN_CODE_EVENTS="$task_tmp/events"

run_case() {
  rm -f "$RETURN_CODE_EVENTS"
  result=0
  bash "$fixture/install.sh" --unattended --components "$1" > "$task_tmp/output" 2>&1 || result=$?
}
export FIXTURE_NEXUS_RC=20 FIXTURE_DOCKER_RC=0
run_case nexus,docker
[[ "$result" == 20 ]]
grep -Fx 'nexus' "$RETURN_CODE_EVENTS" >/dev/null
if grep -Fx 'docker' "$RETURN_CODE_EVENTS"; then echo 'Ran subsequent component while Nexus was pending' >&2; exit 1; fi
grep -F 'Nexus 약관 처리 대기' "$task_tmp/output" >/dev/null
if grep -F '무인 설치 완료' "$task_tmp/output"; then exit 1; fi

export FIXTURE_NEXUS_RC=0
run_case nexus,docker
[[ "$result" == 0 ]]
[[ "$(grep -E '^(nexus|docker)$' "$RETURN_CODE_EVENTS" | tr '\n' ',')" == 'nexus,docker,' ]]
grep -F '무인 설치 완료' "$task_tmp/output" >/dev/null

export FIXTURE_NEXUS_RC=7
run_case nexus,docker
[[ "$result" == 1 ]]
if grep -Fx 'docker' "$RETURN_CODE_EVENTS"; then exit 1; fi
grep -F '무인 설치 실패: nexus' "$task_tmp/output" >/dev/null

# 20 is an explicit Nexus pending state, not a success code for other components.
export FIXTURE_DOCKER_RC=20
run_case docker,nexus
[[ "$result" == 1 ]]
if grep -Fx 'nexus' "$RETURN_CODE_EVENTS"; then exit 1; fi
grep -F '무인 설치 실패: docker' "$task_tmp/output" >/dev/null

# An installed Nexus still skips normally, while a pending Nexus is retried.
export FIXTURE_NEXUS_STATUS=installed FIXTURE_NEXUS_RC=20 FIXTURE_DOCKER_RC=0
run_case nexus,docker
[[ "$result" == 0 ]]
if grep -Fx 'nexus' "$RETURN_CODE_EVENTS"; then exit 1; fi
grep -Fx 'docker' "$RETURN_CODE_EVENTS" >/dev/null
echo 'installer unattended return codes: PASS (public CLI pending20/stop, success0/continue, generic1, other20fails, installed skip; no host changes)'
