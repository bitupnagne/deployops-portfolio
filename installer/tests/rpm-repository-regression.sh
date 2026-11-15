#!/usr/bin/env bash
set -euo pipefail
# 상세 인증 호환 경로의 기존 회귀 검사. 기본 설치는 unsigned-install-contract.sh에서 확인.
export OFFLINE_VERIFY_AUTHENTICITY=1
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$SCRIPT_DIR/lib/rpm-repository.sh"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT
mkdir -p "$task_tmp/docker/repodata"
printf metadata > "$task_tmp/docker/repodata/repomd.xml"
(cd "$task_tmp/docker" && sha256sum repodata/repomd.xml > REPODATA.sha256)
for package in docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin coreutils; do
  printf '%s|0:1.0-1|x86_64|%064d|%s.rpm\n' "$package" 0 "$package" >> "$task_tmp/docker/PACKAGES.lock"
done
PKG=mock_dnf
bootstrap_rpm_signing_keys() { printf '%s' "$1" > "$task_tmp/keydir"; }
mock_dnf() {
  printf '%s\n' "$@" > "$task_tmp/args"
  return "${solver_rc:-0}"
}
rpm() { printf '%s' "${mock_installed:-0:1.0-1|x86_64}"; }
install_rpm_repository_packages "$task_tmp/docker" docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
grep -Fx -- '--disablerepo=*' "$task_tmp/args"
grep -Fx -- "--repofrompath=offline-bundle,file://$task_tmp/docker" "$task_tmp/args"
grep -Fx -- '--enablerepo=offline-bundle' "$task_tmp/args"
grep -Fx -- '--setopt=offline-bundle.gpgcheck=1' "$task_tmp/args"
grep -Fx -- '--setopt=offline-bundle.module_hotfixes=true' "$task_tmp/args"
[[ "$(sed -n '/^install$/,$p' "$task_tmp/args" | tail -n +2 | wc -l)" == 5 ]]
if grep -E 'coreutils|\.rpm$|allowerasing|skip-broken|nobest|nogpgcheck|gpgcheck=0' "$task_tmp/args"; then exit 1; fi
solver_rc=1
if install_rpm_repository_packages "$task_tmp/docker" docker-ce; then exit 1; fi
unset solver_rc
mock_installed='0:0.9-1|x86_64'
if install_rpm_repository_packages "$task_tmp/docker" docker-ce; then exit 1; fi
unset mock_installed
if install_rpm_repository_packages "$task_tmp/docker" missing; then exit 1; fi
printf extra > "$task_tmp/docker/repodata/extra.xml"
if verify_rpm_repository "$task_tmp/docker"; then exit 1; fi
rm "$task_tmp/docker/repodata/extra.xml"
printf tamper >> "$task_tmp/docker/repodata/repomd.xml"
if verify_rpm_repository "$task_tmp/docker"; then exit 1; fi
printf '%064d  ../escape\n' 0 > "$task_tmp/docker/REPODATA.sha256"
if verify_rpm_repository "$task_tmp/docker"; then exit 1; fi
createrepo_c() {
  [[ "$1" == --outputdir ]]
  [[ "${metadata_rc:-0}" == 0 ]] || return 1
  mkdir -p "$2/repodata"
  printf 'new metadata' > "$2/repodata/repomd.xml"
}
write_rpm_repository "$task_tmp/docker"
verify_rpm_repository "$task_tmp/docker"
before="$(sha256sum "$task_tmp/docker/REPODATA.sha256" "$task_tmp/docker/repodata/repomd.xml")"
metadata_rc=1
if write_rpm_repository "$task_tmp/docker"; then exit 1; fi
after="$(sha256sum "$task_tmp/docker/REPODATA.sha256" "$task_tmp/docker/repodata/repomd.xml")"
[[ "$before" == "$after" ]]
unset metadata_rc
# if 호출에서는 errexit가 비활성화되므로 rename 각 단계의 명시적 실패 처리를 검증한다.
mv() {
  mv_calls=$((mv_calls + 1))
  if [[ " $fail_moves " == *" $mv_calls "* ]]; then return 1; fi
  command mv "$@"
}
for fail_moves in '1' '2' '3' '2 3' '3 4' '3 5'; do
  case_dir="$task_tmp/rename-${fail_moves// /-}"
  mkdir -p "$case_dir/repodata"
  printf 'previous metadata' > "$case_dir/repodata/repomd.xml"
  (cd "$case_dir" && sha256sum repodata/repomd.xml > REPODATA.sha256)
  cp "$case_dir/REPODATA.sha256" "$case_dir/lock.before"
  mv_calls=0
  if write_rpm_repository "$case_dir" 2> "$case_dir/error.log"; then
    echo "Rename failure incorrectly succeeded: $fail_moves" >&2; exit 1
  fi
  cmp "$case_dir/lock.before" "$case_dir/REPODATA.sha256"
  [[ ! -e "$case_dir/repodata/repodata" ]]
  if [[ "$fail_moves" == *' '* ]]; then
    preserved="$(find "$case_dir" -path '*/previous-repodata/repomd.xml')"
    [[ -f "$preserved" && "$(< "$preserved")" == 'previous metadata' ]]
    grep -F 'Repository recovery files preserved:' "$case_dir/error.log" >/dev/null
    grep -F "${preserved%/repomd.xml}" "$case_dir/error.log" >/dev/null
  else
    [[ "$(< "$case_dir/repodata/repomd.xml")" == 'previous metadata' ]]
    verify_rpm_repository "$case_dir"
  fi
done
unset -f mv
echo 'rpm-repository-regression: PASS'
