#!/usr/bin/env bash
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rpm-signing-keys.sh" || return 1
# repodata는 PACKAGES.lock과 별도로 잠그며 상위 MANIFEST가 두 lock을 함께 결속한다.
write_rpm_repository() (
  set -euo pipefail
  local dir="$1" tool task_repo_tmp preserve_stage=0
  for tool in createrepo_c find xargs sort sha256sum mktemp mv; do
    command -v "$tool" >/dev/null 2>&1 || { printf '%s is required on the online collector\n' "$tool" >&2; return 1; }
  done
  task_repo_tmp="$(mktemp -d "$dir/.repodata-stage.XXXXXX")" || return 1
  trap 'if (( preserve_stage )); then printf "Repository recovery files preserved: %s (previous metadata: %s/previous-repodata)\n" "$task_repo_tmp" "$task_repo_tmp" >&2; else rm -rf "$task_repo_tmp"; fi' EXIT
  createrepo_c --outputdir "$task_repo_tmp" "$dir" || return 1
  [[ -s "$task_repo_tmp/repodata/repomd.xml" ]] || return 1
  (cd "$task_repo_tmp" && find repodata -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > REPODATA.sha256) || return 1
  verify_rpm_repository "$task_repo_tmp" || return 1
  if [[ -d "$dir/repodata" ]]; then
    if ! mv "$dir/repodata" "$task_repo_tmp/previous-repodata"; then
      [[ ! -e "$task_repo_tmp/previous-repodata" ]] || preserve_stage=1
      printf 'Cannot back up repository metadata: %s -> %s\n' "$dir/repodata" "$task_repo_tmp/previous-repodata" >&2
      return 1
    fi
  fi
  if ! mv "$task_repo_tmp/repodata" "$dir/repodata"; then
    printf 'Cannot publish repository metadata: %s -> %s\n' "$task_repo_tmp/repodata" "$dir/repodata" >&2
    if [[ -d "$task_repo_tmp/previous-repodata" ]] && ! mv "$task_repo_tmp/previous-repodata" "$dir/repodata"; then
      preserve_stage=1
      printf 'Cannot restore repository metadata: %s -> %s\n' "$task_repo_tmp/previous-repodata" "$dir/repodata" >&2
    fi
    return 1
  fi
  if ! mv "$task_repo_tmp/REPODATA.sha256" "$dir/REPODATA.sha256"; then
    printf 'Cannot publish repository lock: %s -> %s\n' "$task_repo_tmp/REPODATA.sha256" "$dir/REPODATA.sha256" >&2
    if ! mv "$dir/repodata" "$task_repo_tmp/failed-repodata"; then
      preserve_stage=1
      printf 'Cannot evacuate new repository metadata: %s -> %s\n' "$dir/repodata" "$task_repo_tmp/failed-repodata" >&2
      return 1
    fi
    if [[ -d "$task_repo_tmp/previous-repodata" ]] && ! mv "$task_repo_tmp/previous-repodata" "$dir/repodata"; then
      preserve_stage=1
      printf 'Cannot restore repository metadata: %s -> %s\n' "$task_repo_tmp/previous-repodata" "$dir/repodata" >&2
    fi
    return 1
  fi
)

verify_rpm_repository() {
  local dir="$1" lock="$1/REPODATA.sha256" expected actual
  [[ -s "$dir/repodata/repomd.xml" ]] || return 1
  [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]] || return 0
  [[ -s "$lock" && -s "$dir/repodata/repomd.xml" ]] || { printf 'RPM repository metadata/lock missing: %s\n' "$dir" >&2; return 1; }
  if find "$dir/repodata" -type l -print | grep . >/dev/null; then return 1; fi
  if sed 's/\r$//' "$lock" | grep -Ev '^[0-9a-f]{64} [ *]repodata/[A-Za-z0-9._-]+$' >/dev/null; then return 1; fi
  expected="$(sed 's/\r$//' "$lock" | sed 's/^[0-9a-f]\{64\} [ *]//' | LC_ALL=C sort)"
  actual="$(cd "$dir" && find repodata -type f | LC_ALL=C sort)"
  [[ "$expected" == "$actual" ]] || { printf 'RPM repository file list differs from lock: %s\n' "$dir" >&2; return 1; }
  (cd "$dir" && sed 's/\r$//' REPODATA.sha256 | sha256sum -c --quiet)
}

install_rpm_repository_packages() {
  local dir="$1"; shift
  local package row name version arch sha filename installed
  local specs=() requested_rows=()
  [[ $# -gt 0 && -f "$dir/PACKAGES.lock" ]] || return 1
  verify_rpm_repository "$dir" || return 1
  dir="$(cd "$dir" && pwd)" || return 1
  # DNF baseurl 변수 확장/URL 해석에 모호한 경로는 사전에 거부한다.
  [[ "$dir" =~ ^/[A-Za-z0-9._/-]+$ ]] || { printf 'Unsupported RPM repository path: %s\n' "$dir" >&2; return 1; }
  local gpgcheck=0
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
    gpgcheck=1
  fi
  local dependency_dir="" dependency_args=() package_lock
  if [[ "${dir##*/}" == nginx ]]; then
    # nginx.org provides nginx; the helper's required openssl CLI and OS
    # dependencies ship in this release's shared prerequisite repo.
    # Expose candidates to DNF without requesting the whole prerequisite list.
    dependency_dir="${dir%/*}/target-prerequisites"
    [[ -f "$dependency_dir/PACKAGES.lock" ]] && verify_rpm_repository "$dependency_dir" \
      || { printf 'Nginx offline dependency repository is missing or invalid: %s\n' "$dependency_dir" >&2; return 1; }
    dependency_args=(--repofrompath="offline-prerequisites,file://$dependency_dir" \
      --enablerepo=offline-prerequisites --setopt=offline-prerequisites.module_hotfixes=true \
      --setopt=offline-prerequisites.gpgcheck="$gpgcheck" --setopt=offline-prerequisites.skip_if_unavailable=false)
  fi
  for package in "$@"; do
    package_lock="$dir/PACKAGES.lock"
    # Only this explicit WEB helper prerequisite is selected from the sibling
    # lock. Do not turn the whole shared closure into installation requests.
    if [[ "${dir##*/}" == nginx && "$package" == openssl ]]; then
      package_lock="$dependency_dir/PACKAGES.lock"
    fi
    row="$(awk -F'|' -v p="$package" '$1==p {print; count++} END {if(count!=1) exit 1}' "$package_lock")" || return 1
    IFS='|' read -r name version arch sha filename <<< "$row"
    [[ "$name" =~ ^[A-Za-z0-9._+-]+$ && "$version" =~ ^[A-Za-z0-9.+:~_-]+$ && "$arch" =~ ^[A-Za-z0-9_+-]+$ ]] || return 1
    specs+=("$name-$version.$arch")
    requested_rows+=("$row")
  done
  if (( gpgcheck )); then
    bootstrap_rpm_signing_keys "$dir/../../common/rpm-keys" || return 1
  fi
  # closure 전체를 설치 요청하지 않는다. 기존 OS 의존성은 solver가 재사용한다.
  # 기본 설치는 별도 RPM 신뢰키를 요구하지 않는다. 상세 인증은 명시적으로 선택한다.
  "$PKG" -y --refresh --disablerepo='*' --repofrompath="offline-bundle,file://$dir" \
    --enablerepo=offline-bundle --setopt=offline-bundle.module_hotfixes=true \
    --setopt=offline-bundle.gpgcheck="$gpgcheck" --setopt=localpkg_gpgcheck="$gpgcheck" --setopt=offline-bundle.skip_if_unavailable=false \
    "${dependency_args[@]}" install "${specs[@]}" || return 1
  for row in "${requested_rows[@]}"; do
    IFS='|' read -r name version arch sha filename <<< "$row"
    installed="$(rpm -q --qf '%{EPOCHNUM}:%{VERSION}-%{RELEASE}|%{ARCH}' "$name")" || return 1
    [[ "$installed" == "$version|$arch" ]] || { printf 'Installed RPM differs from lock: %s\n' "$name" >&2; return 1; }
  done
}
