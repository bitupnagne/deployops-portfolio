#!/usr/bin/env bash
# Select the exact server/client family from the offline lock; never infer a version from the OS.

mariadb_rpm_package_names() {
  local dir="$1" lock="$1/PACKAGES.lock" upper lower package row name version arch sha filename extra expected_version=""
  local packages=() incompatible=()
  [[ -f "$lock" && ! -L "$lock" ]] || { printf 'MariaDB PACKAGES.lock is missing or linked: %s\n' "$dir" >&2; return 1; }
  upper="$(awk -F'|' '$1=="MariaDB-server" {n++} END {print n+0}' "$lock")" || return 1
  lower="$(awk -F'|' '$1=="mariadb-server" {n++} END {print n+0}' "$lock")" || return 1
  if [[ "$upper:$lower" == 1:0 ]]; then
    packages=(MariaDB-server MariaDB-client MariaDB-shared MariaDB-common)
    incompatible=(mariadb-server mariadb mariadb-common)
  elif [[ "$upper:$lower" == 0:1 ]]; then
    packages=(mariadb-server mariadb mariadb-common)
    incompatible=(MariaDB-server MariaDB-client MariaDB-shared MariaDB-common)
  else
    printf 'MariaDB lock must contain exactly one server package family, without duplicates.\n' >&2; return 1
  fi
  for package in "${incompatible[@]}"; do
    if awk -F'|' -v p="$package" '$1==p {found=1} END {exit !found}' "$lock"; then
      printf 'MariaDB lock mixes vendor and OS server/client families: %s\n' "$package" >&2; return 1
    fi
  done
  for package in "${packages[@]}"; do
    row="$(awk -F'|' -v p="$package" '$1==p {if(NF!=5) exit 1; print; n++} END {if(n!=1) exit 1}' "$lock")" \
      || { printf 'Required MariaDB lock row missing or duplicated: %s\n' "$package" >&2; return 1; }
    row="${row%$'\r'}"
    IFS='|' read -r name version arch sha filename extra <<< "$row"
    [[ "$name" == "$package" && "$version" =~ ^[0-9]+:[A-Za-z0-9.+~_-]+$ \
       && "$arch" =~ ^(x86_64|noarch)$ && "$sha" =~ ^[0-9a-f]{64}$ \
       && "$filename" =~ ^[A-Za-z0-9._+~-]+\.rpm$ && -z "$extra" ]] \
      || { printf 'Invalid required MariaDB package row: %s\n' "$package" >&2; return 1; }
    [[ -n "$expected_version" ]] || expected_version="$version"
    [[ "$version" == "$expected_version" ]] \
      || { printf 'MariaDB server/client/common packages must have the same locked EVR.\n' >&2; return 1; }
  done
  printf '%s\n' "${packages[@]}"
}

mariadb_rpm_state() {
  local dir="$1" names package row name version arch sha filename installed present=0 expected=0 matching=0
  command -v rpm >/dev/null 2>&1 || { printf 'invalid\n'; return 0; }
  names="$(mariadb_rpm_package_names "$dir")" || { printf 'invalid\n'; return 0; }
  # Detect a partial or foreign family as well as a full server installation.
  for package in MariaDB-server MariaDB-client MariaDB-shared MariaDB-common mariadb-server mariadb mariadb-common; do
    if rpm -q "$package" >/dev/null 2>&1; then
      present=$((present + 1))
      if ! grep -Fx -- "$package" <<< "$names" >/dev/null; then printf 'mismatch\n'; return 0; fi
    fi
  done
  while IFS= read -r package; do
    expected=$((expected + 1))
    row="$(awk -F'|' -v p="$package" '$1==p {print}' "$dir/PACKAGES.lock")" || return 1
    row="${row%$'\r'}"; IFS='|' read -r name version arch sha filename <<< "$row"
    if installed="$(rpm -q --qf '%{EPOCHNUM}:%{VERSION}-%{RELEASE}|%{ARCH}' "$name" 2>/dev/null)"; then
      [[ "$installed" != "$version|$arch" ]] || matching=$((matching + 1))
    fi
  done <<< "$names"
  if (( present == 0 )); then printf 'missing\n'
  elif (( present == expected && matching == expected )); then printf 'installed\n'
  else printf 'mismatch\n'; fi
}

mariadb_rpm_install_guard() {
  local dir="$1" data_dir="${2:-/var/lib/mysql}" state
  state="$(mariadb_rpm_state "$dir")" || return 1
  case "$state" in
    installed) return 0 ;;
    missing)
      if [[ -d "$data_dir/mysql" || -f "$data_dir/ibdata1" || -f "$data_dir/aria_log_control" ]]; then
        printf '기존 MariaDB 데이터가 남아 있어 자동 설치를 중단했습니다. 원본을 보존하고 논리 백업을 새 데이터 디렉터리에 복원하는 이관 절차를 진행하세요.\n' >&2
        return 1
      fi
      return 0 ;;
    mismatch)
      printf '설치된 MariaDB 패키지 family/EVR이 반입 LOCK과 다릅니다. 자동 업그레이드·다운그레이드는 하지 않습니다. DB·설정·키를 백업하고 별도 이관 절차를 진행하세요. FORCE_REINSTALL도 이 검사를 우회하지 않습니다.\n' >&2
      return 1 ;;
    *) printf 'MariaDB 반입 LOCK을 확인할 수 없습니다. 패키지 family·필수 client·동일 EVR 구성을 확인하세요.\n' >&2; return 1 ;;
  esac
}
