#!/usr/bin/env bash
# os.sh - OS/패키지매니저 추상화 레이어 (install.sh 에서 source)
# 목적: 같은 스크립트가 Rocky(rpm/dnf) 와 Ubuntu(deb/apt) 양쪽에서 동작하도록
#       OS 차이를 함수로 흡수한다. 바이너리 번들만 bundles/<BUNDLE_OS>/ 에 맞게 두면 된다.

# 전역: OS_ID OS_VER OS_FAMILY PKG BUNDLE_OS PRETTY_OS
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rpm-repository.sh" || return 1
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mariadb-packages.sh" || return 1
detect_os() {
  OS_ID="unknown"; OS_VER=""; PRETTY_OS="unknown"
  if [[ -r /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID="${ID:-unknown}"; OS_VER="${VERSION_ID:-}"; PRETTY_OS="${PRETTY_NAME:-$OS_ID}"
  fi
  select_os_bundle
}

select_os_bundle() {
  case "$OS_ID" in
    rocky|rhel|centos|almalinux|ol|fedora)
      # EL8 RPMs must never be selected merely because a future OS uses dnf.
      # Only Rocky 8.10 is the validated RPM target of this release.
      if [[ "$OS_ID" == rocky && "$OS_VER" == 8.10 ]]; then
        OS_FAMILY="rhel"; PKG="dnf"; BUNDLE_OS="rocky8"
      else
        OS_FAMILY="unknown"; PKG=""; BUNDLE_OS="unknown"
      fi ;;
    ubuntu|debian|linuxmint)
      OS_FAMILY="debian"; PKG="apt"; BUNDLE_OS="ubuntu" ;;
    *)
      OS_FAMILY="unknown"; PKG=""; BUNDLE_OS="unknown" ;;
  esac
  # dnf 없으면 yum 으로
  if [[ "$OS_FAMILY" == "rhel" ]] && ! command -v dnf >/dev/null 2>&1; then
    PKG="yum"
  fi
}

require_root() {
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    err "root 권한으로 실행해야 합니다: sudo $0"
    exit 1
  fi
}

os_supported() { [[ "$OS_FAMILY" == "rhel" || "$OS_FAMILY" == "debian" ]]; }

# 설치된 패키지인지 (이름 1개)
pkg_is_installed() {
  local name="$1"
  case "$OS_FAMILY" in
    rhel)   rpm -q "$name" >/dev/null 2>&1 ;;
    # dpkg -s 는 제거 후 config-files(rc) 상태도 0을 반환하므로 실제 설치 상태만 인정.
    debian) dpkg -s "$name" 2>/dev/null | grep -q '^Status: install ok installed' ;;
    *)      return 1 ;;
  esac
}

# 로컬 패키지 파일(디렉터리 + glob)을 OS에 맞게 설치.
# 사용: install_local_packages "/path/to/dir" "*.rpm|*.deb 는 OS가 결정"
install_local_packages() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then
    err "번들 디렉터리가 없습니다: $dir"
    return 1
  fi
  case "$OS_FAMILY" in
    rhel)
      if ! compgen -G "$dir/*.rpm" >/dev/null; then
        err "rpm 파일이 없습니다: $dir/*.rpm"; return 1
      fi
      case "${dir##*/}" in
        docker) install_rpm_repository_packages "$dir" docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin ;;
        mariadb)
          local mariadb_names mariadb_packages=()
          mariadb_names="$(mariadb_rpm_package_names "$dir")" || return 1
          mapfile -t mariadb_packages <<< "$mariadb_names"
          install_rpm_repository_packages "$dir" "${mariadb_packages[@]}"
          ;;
        nginx) install_rpm_repository_packages "$dir" nginx openssl ;;
        target-prerequisites)
          local target_packages=()
          mapfile -t target_packages < <(target_prerequisites_packages)
          install_rpm_repository_packages "$dir" "${target_packages[@]}"
          ;;
        *) err "RPM 설치 대상 component를 판정할 수 없습니다: $dir"; return 1 ;;
      esac
      ;;
    debian)
      if ! compgen -G "$dir/*.deb" >/dev/null; then
        err "deb 파일이 없습니다: $dir/*.deb"; return 1
      fi
      # 의존성 순서로 인한 초기 실패만 복구한다. 다운로드와 패키지 제거는 금지한다.
      local needs_repair=0 audit_output package_file package_name package_version package_arch installed
      dpkg -i "$dir"/*.deb || needs_repair=1
      dpkg --configure -a || needs_repair=1
      if (( needs_repair )); then
        if ! apt-get -y -f --no-download --no-remove install; then
          err "오프라인 의존성 복구에 실패했습니다. 누락된 패키지를 번들에 추가해 주세요."
          return 1
        fi
        dpkg --configure -a || { err "패키지 설정에 실패했습니다."; return 1; }
      fi
      if ! audit_output="$(dpkg --audit)"; then
        err "패키지 상태를 검사할 수 없습니다."; return 1
      fi
      if [[ -n "$audit_output" ]]; then
        err "일부 패키지 의존성이 미해결 상태입니다(dpkg --audit 확인)."; return 1
      fi
      apt-get --no-download check || { err "패키지 의존성 검증에 실패했습니다."; return 1; }
      # 복구 명령 성공만으로 설치 성공을 간주하지 않는다.
      for package_file in "$dir"/*.deb; do
        package_name="$(dpkg-deb -f "$package_file" Package)" || return 1
        package_version="$(dpkg-deb -f "$package_file" Version)" || return 1
        package_arch="$(dpkg-deb -f "$package_file" Architecture)" || return 1
        installed="$(dpkg-query -W -f='${Status}|${Version}|${Architecture}' "$package_name")" || return 1
        if [[ "$installed" != "install ok installed|$package_version|$package_arch" ]]; then
          err "번들 패키지가 요청한 버전으로 설치되지 않았습니다: $package_name"; return 1
        fi
      done
      ;;
    *)
      err "지원하지 않는 OS 입니다."; return 1 ;;
  esac
}

service_enable_now() { systemctl enable --now "$1"; }
service_state()      { systemctl is-active "$1" 2>/dev/null || echo unknown; }
service_enabled()    { systemctl is-enabled "$1" 2>/dev/null || echo unknown; }
unit_exists()        { systemctl list-unit-files 2>/dev/null | grep -q "^$1"; }

# TCP 포트 점유 여부 (사용중이면 0)
port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]"
  else
    return 1
  fi
}

# 번들 루트(bundles/) 기준 컴포넌트 디렉터리 경로 결정.
# 1순위 OS별 번들, 2순위 공용(common) 번들.
bundle_dir() {
  local component="$1"
  local osdir="$BUNDLE_ROOT/$BUNDLE_OS/$component"
  local common="$BUNDLE_ROOT/common/$component"
  # 실제 파일(.gitkeep 등 dotfile 제외)이 있는 디렉터리만 채택 — 빈 골격 디렉터리가
  # common 번들을 가리지 않도록.
  if [[ -d "$osdir" ]] && compgen -G "$osdir/*" >/dev/null 2>&1; then printf '%s' "$osdir"; return 0; fi
  if [[ -d "$common" ]] && compgen -G "$common/*" >/dev/null 2>&1; then printf '%s' "$common"; return 0; fi
  printf '%s' "$osdir"  # 둘 다 비었으면 기대 경로(OS 우선) 반환(에러 메시지용)
  return 1
}

# 해당 컴포넌트 번들에 실제 OS 패키지 파일(*.rpm/*.deb)이 들어있는지
bundle_has_packages() {
  local component="$1" dir
  dir="$(bundle_dir "$component" 2>/dev/null)" || return 1
  compgen -G "$dir/*.rpm" >/dev/null 2>&1 || compgen -G "$dir/*.deb" >/dev/null 2>&1
}

# 해당 컴포넌트 번들에 docker 이미지 tar(*.tar/*.tar.gz/*.tgz)가 들어있는지
bundle_has_image() {
  local component="$1" dir
  dir="$(bundle_dir "$component" 2>/dev/null)" || return 1
  compgen -G "$dir/*.tar" >/dev/null 2>&1 \
    || compgen -G "$dir/*.tar.gz" >/dev/null 2>&1 \
    || compgen -G "$dir/*.tgz" >/dev/null 2>&1
}

# 공용 번들에서 단일 파일(glob) 1개 경로 반환
bundle_file() {
  local component="$1" pattern="$2" hit
  for base in "$BUNDLE_ROOT/$BUNDLE_OS/$component" "$BUNDLE_ROOT/common/$component"; do
    hit="$(compgen -G "$base/$pattern" 2>/dev/null | head -n1 || true)"
    [[ -n "$hit" ]] && { printf '%s' "$hit"; return 0; }
  done
  return 1
}
