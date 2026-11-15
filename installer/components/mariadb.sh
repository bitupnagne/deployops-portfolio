#!/usr/bin/env bash
# mariadb.sh - MariaDB 설치 컴포넌트 (OS무관: rpm/deb 번들 localinstall)

MARIADB_SERVICE="mariadb"

mariadb_status() {
  if [[ "$OS_FAMILY" == rhel ]]; then
    local dir
    dir="$(bundle_dir mariadb)" || { echo invalid; return 0; }
    mariadb_rpm_state "$dir"
    return
  fi
  local n
  for n in MariaDB-server mariadb-server mariadb-server-10.11 mariadb; do
    pkg_is_installed "$n" && { echo installed; return 0; }
  done
  echo missing
}

mariadb_install() {
  local force="${1:-}"
  if [[ "$OS_FAMILY" == rhel ]]; then
    local guarded_dir
    guarded_dir="$(bundle_dir mariadb)" || { err 'MariaDB 오프라인 번들을 찾을 수 없습니다.'; return 1; }
    mariadb_rpm_install_guard "$guarded_dir" || return 1
  fi
  if [[ "$(mariadb_status)" == "installed" && "$force" != "force" ]]; then
    info "MariaDB 이미 설치됨."
  else
    local dir; dir="$(bundle_dir mariadb)" || { err "MariaDB 번들 없음: $dir"; return 1; }
    info "MariaDB 패키지 설치: $dir"
    install_local_packages "$dir" || return 1
  fi

  # 오프라인 dpkg 설치 시 postinst 의 데이터디렉터리 초기화가 누락될 수 있다.
  # 시스템 DB(mysql 스키마)가 없으면 mariadb-install-db 로 초기화한다.
  if [[ "$OS_FAMILY" == "debian" && ! -d /var/lib/mysql/mysql ]]; then
    info "MariaDB 데이터디렉터리 초기화(mariadb-install-db)..."
    # 시스템 스키마가 없다는 이유만으로 기존 데이터가 비어 있다고 판단하지 않습니다.
    # 손상·중단된 기존 DB 또는 별도 마운트의 파일은 운영자가 복구해야 합니다.
    if [[ -L /var/lib/mysql || ( -e /var/lib/mysql && ! -d /var/lib/mysql ) ]]; then
      err 'MariaDB 데이터 경로가 일반 디렉터리가 아닙니다. 기존 경로를 보존하고 중단합니다.'
      return 1
    fi
    if [[ -d /var/lib/mysql ]]; then
      local existing_entry
      existing_entry="$(find /var/lib/mysql -mindepth 1 -maxdepth 1 -print -quit)" || {
        err 'MariaDB 데이터 디렉터리를 검사하지 못했습니다. 초기화하지 않습니다.'; return 1;
      }
      if [[ -n "$existing_entry" ]]; then
        err 'MariaDB 시스템 스키마는 없지만 기존 파일이 있습니다. 삭제하지 않습니다. 백업·복구 상태를 확인하세요.'
        return 1
      fi
    fi
    install -d -o mysql -g mysql /var/lib/mysql || return 1
    mariadb-install-db --user=mysql --datadir=/var/lib/mysql --skip-test-db >/dev/null 2>&1 \
      || mysql_install_db --user=mysql --datadir=/var/lib/mysql >/dev/null 2>&1 \
      || { err 'MariaDB 시스템 DB 초기화 실패'; return 1; }
    chown -R mysql:mysql /var/lib/mysql || return 1
  fi

  # alias 이름을 enable하면 실패하므로 로드된 canonical unit을 사용합니다.
  local candidate unit_properties unit_id load_state property value
  MARIADB_SERVICE=""
  for candidate in mariadb mysql mysqld; do
    unit_properties="$(systemctl show "$candidate.service" --property=LoadState --property=Id 2>/dev/null)" || continue
    unit_id=""; load_state=""
    while IFS='=' read -r property value; do
      case "$property" in
        Id) unit_id="$value" ;;
        LoadState) load_state="$value" ;;
      esac
    done <<< "$unit_properties"
    if [[ "$load_state" == loaded && "$unit_id" =~ ^(mariadb|mysql|mysqld)\.service$ ]]; then
      MARIADB_SERVICE="$unit_id"
      break
    fi
  done
  [[ -n "$MARIADB_SERVICE" ]] || { err "MariaDB 서비스 유닛을 찾을 수 없습니다."; return 1; }
  systemctl daemon-reload 2>/dev/null || true

  # bind-address 설정 (기본 127.0.0.1, 다중 호스트 접속 필요시 0.0.0.0) — 무인: MARIADB_BIND_ADDRESS
  local bind_address
  bind_address="$(ask MARIADB_BIND_ADDRESS "MariaDB bind-address [127.0.0.1]: " "127.0.0.1")"
  # debian 계열: my.cnf 가 !includedir /etc/mysql/conf.d 를 참조하므로 비어있어도 존재해야 한다
  # (없으면 'Fatal error in defaults handling' 으로 mariadbd 가 기동 실패).
  [[ "$OS_FAMILY" == "debian" ]] && mkdir -p /etc/mysql/conf.d /etc/mysql/mariadb.conf.d
  local cnf_dir
  if [[ -d /etc/my.cnf.d ]]; then cnf_dir=/etc/my.cnf.d
  elif [[ -d /etc/mysql/mariadb.conf.d ]]; then cnf_dir=/etc/mysql/mariadb.conf.d
  else cnf_dir=/etc/mysql; mkdir -p "$cnf_dir"; fi
  local tmp_cnf
  tmp_cnf="$(mktemp "$cnf_dir/.zz-office-system.XXXXXX")" || return 1
  cat > "$tmp_cnf" <<EOF || { rm -f "$tmp_cnf"; return 1; }
[mysqld]
bind-address = $bind_address
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
EOF
  # 서버 설정에는 비밀 값이 없으며 mysql 서비스 계정도 읽을 수 있어야 합니다.
  if ! install -m 0644 -o root -g root "$tmp_cnf" "$cnf_dir/zz-office-system.cnf"; then
    rm -f "$tmp_cnf"
    err "MariaDB 서버 설정 설치 실패"
    return 1
  fi
  rm -f "$tmp_cnf"
  service_enable_now "$MARIADB_SERVICE" || { err "MariaDB 시작 실패"; return 1; }
  systemctl restart "$MARIADB_SERVICE" || { err "MariaDB 설정 적용 재시작 실패"; return 1; }

  # root 비밀번호 설정 (선택) — 무인: MARIADB_ROOT_PASSWORD 가 있으면 설정, 없으면 건너뜀
  local rp="${MARIADB_ROOT_PASSWORD:-}"
  if [[ -z "$rp" && "${UNATTENDED:-0}" != "1" ]] && confirm "MariaDB root 비밀번호를 지금 설정할까요?"; then
    rp="$(prompt_secret "새 root 비밀번호: ")"
  fi
  if [[ -n "$rp" ]]; then
    mariadb_configure_root_password "$rp" || return 1
  fi
  success "MariaDB 설치/기동 완료 (service: $MARIADB_SERVICE, bind: $bind_address)."
  info "각 앱(deploy-system/worksystem-server) 설치 시 해당 DB/계정이 생성됩니다."
}

mariadb_configure_root_password() {
  local password="$1" quoted sql auth_password
  [[ "$password" != *$'\n'* && "$password" != *$'\r'* ]] || {
    err 'MariaDB root 비밀번호에는 줄바꿈을 사용할 수 없습니다.'; return 1;
  }
  # Socket authentication can accept root regardless of supplied password.
  # Always apply and verify, using the credential that authenticated successfully.
  if db_root_exec 'SELECT 1;' "$password" >/dev/null 2>&1; then
    auth_password="$password"
  elif db_root_exec 'SELECT 1;' '' >/dev/null 2>&1; then
    auth_password=''
  else
    err 'MariaDB root 인증 실패: 기존 root 비밀번호를 확인한 후 재개하세요.'; return 1
  fi
  quoted="${password//\'/\'\'}"
  sql="SET SESSION sql_mode=CONCAT_WS(',', @@SESSION.sql_mode, 'NO_BACKSLASH_ESCAPES');
ALTER USER 'root'@'localhost' IDENTIFIED BY '$quoted';"
  db_root_exec "$sql" "$auth_password" >/dev/null 2>&1 || {
    err 'MariaDB root 비밀번호 설정 실패. 설치를 중단합니다.'; return 1;
  }
  db_root_exec 'SELECT 1;' "$password" >/dev/null 2>&1 || {
    err 'MariaDB root 비밀번호 설정 후 인증 검증 실패.'; return 1;
  }
}
