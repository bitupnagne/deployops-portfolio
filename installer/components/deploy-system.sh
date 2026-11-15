#!/usr/bin/env bash
# deploy-system.sh - 배포시스템(deploy-ops) 앱 설치 컴포넌트

DEPLOY_APP_HOME="${DEPLOY_APP_HOME:-/opt/deploy-system}"
DEPLOY_APP_USER="${DEPLOY_APP_USER:-deployops}"
DEPLOY_SERVICE="deploy-system"
. "$SCRIPT_DIR/lib/nexus-backup.sh" || return 1

deploy_system_validate_embedded_config() {
  local py=/usr/libexec/platform-python
  [[ -x "$py" ]] || py="$(command -v python3)" || return 1
  "$py" -I -B "$SCRIPT_DIR/deploy-system/embedded-config.py" validate "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" >/dev/null || return 1
  local tls_args=(--jar "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" --mode preflight)
  [[ -z "${DEPLOY_TLS_KEYSTORE_FILE:-}" ]] || tls_args+=(--source "$DEPLOY_TLS_KEYSTORE_FILE")
  "$py" -I -B "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" "${tls_args[@]}" >/dev/null || return 1
  deploy_system_validate_health_plan "$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" "$py" >/dev/null || return 1
}

deploy_system_validate_health_plan() {
  local jar="$1" py="$2" args=(--jar "$1" --mode validate-health)
  [[ -z "${DEPLOY_HEALTHCHECK_URL:-}" ]] || args+=(--health-url "$DEPLOY_HEALTHCHECK_URL")
  [[ -z "${DEPLOY_TLS_CA_FILE:-}" ]] || args+=(--ca-file "$DEPLOY_TLS_CA_FILE")
  "$py" -I -B "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" "${args[@]}"
}

deploy_system_healthcheck() {
  local jar="$1" port="$2" py="$3" url health_file i
  url="$(deploy_system_validate_health_plan "$jar" "$py")" || return 1
  [[ -n "$url" ]] || url="http://127.0.0.1:$port/health"
  local args=(-fsS --connect-timeout 3 --max-time 5 --max-redirs 0)
  if [[ "$url" == https://* ]]; then
    args+=(--proto '=https')
    [[ -z "${DEPLOY_TLS_CA_FILE:-}" ]] || args+=(--cacert "$DEPLOY_TLS_CA_FILE")
  fi
  health_file="$(mktemp)" || return 1
  chmod 600 "$health_file" || { rm -f -- "$health_file"; return 1; }
  info "Deploy 애플리케이션·DB 상태 확인: $url"
  for i in $(seq 1 30); do
    if curl "${args[@]}" -o "$health_file" "$url" 2>/dev/null \
      && "$py" -I -B "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" --jar "$jar" --mode check-health-body < "$health_file" >/dev/null 2>&1; then
      rm -f -- "$health_file"
      success 'Deploy 애플리케이션과 DB가 정상 응답했습니다.'
      return 0
    fi
    sleep 2
  done
  rm -f -- "$health_file"
  err 'Deploy 상태 확인 실패. HTTPS 주소·사내 CA·인증서 이름과 서비스 로그를 확인하세요. HTTP로 우회하지 않았습니다.'
  return 1
}

deploy_system_status() {
  if unit_exists "${DEPLOY_SERVICE}\\.service"; then
    local installed_jar="$DEPLOY_APP_HOME/app/deploy-ops.jar"
    if [[ -f "$installed_jar" ]] \
        && systemctl is-active --quiet "$DEPLOY_SERVICE" 2>/dev/null \
        && systemctl show "$DEPLOY_SERVICE" --property=SupplementaryGroups --value 2>/dev/null | grep -qw docker; then
      echo installed
    else
      echo drift
    fi
  elif [[ -f "$DEPLOY_APP_HOME/app/deploy-ops.jar" ]]; then echo partial
  else echo missing; fi
}

deploy_system_docker_cli() {
  local cli
  cli="$(command -v docker)" \
    || { err "DeployOps 이미지 전송에 Docker가 필요합니다. docker 컴포넌트를 먼저 설치하세요."; return 1; }
  [[ "$cli" =~ ^/[A-Za-z0-9._/-]+$ && -x "$cli" ]] \
    || { err "실행 가능한 Docker CLI 경로를 확인할 수 없습니다."; return 1; }
  getent group docker >/dev/null \
    || { err "Docker 소켓 접근 그룹이 없습니다. docker 컴포넌트를 먼저 설치하세요."; return 1; }
  printf '%s' "$cli"
}

deploy_system_preserve_current_install() {
  local home="$1" preimage="$2" unit="$3"
  install -d -m 700 "$preimage" || return 1
  [[ ! -d "$home/config" ]] || cp -a "$home/config" "$preimage/" || return 1
  [[ ! -f "$home/app/deploy-ops.jar" ]] || install -m 600 "$home/app/deploy-ops.jar" "$preimage/deploy-ops.jar" || return 1
  [[ ! -f "$unit" ]] || cp -a "$unit" "$preimage/" || return 1
  [[ ! -d /etc/deploy-system/tls ]] || cp -a /etc/deploy-system/tls "$preimage/tls" || return 1
  [[ ! -f /etc/deploy-system/tls-health.json ]] || cp -a /etc/deploy-system/tls-health.json "$preimage/tls-health.json" || return 1
}

deploy_system_provision_local_db() {
  local host="$1" port="$2"; shift 2
  if [[ ( "$host" != 127.0.0.1 && "$host" != localhost ) || "$port" != 3306 ]]; then
    err "설치기의 DB 생성은 로컬 3306만 지원합니다. 해당 DB를 먼저 준비하고 DEPLOYOPS_CREATE_DB=n을 사용하세요."
    return 1
  fi
  db_ensure_schema_and_user "$@" || return 1
}

deploy_system_install() {
  local force="${1:-}"
  if [[ "$(deploy_system_status)" == "installed" && "$force" != "force" ]]; then
    ask_confirm FORCE_REINSTALL "배포시스템이 이미 설치되어 있습니다. 재배포할까요?" n || { info "건너뜀."; return 0; }
  fi
  local docker_cli jar="$BUNDLE_ROOT/$BOM_DEPLOYOPS_JAR" helper="$SCRIPT_DIR/deploy-system/embedded-config.py"
  local py="/usr/libexec/platform-python" values old_env="$DEPLOY_APP_HOME/config/deploy-ops.env"
  command -v "$py" >/dev/null 2>&1 || py="$(command -v python3)" || return 1
  [[ -f "$jar" ]] || { err "deploy-ops jar 없음: $BOM_DEPLOYOPS_JAR"; return 1; }
  "$py" "$helper" validate "$jar" >/dev/null || { err "운영 YAML을 포함한 JAR를 먼저 빌드하세요."; return 1; }
  deploy_system_validate_embedded_config || return 1
  # 기존 데이터의 DB/암호화 키를 새 JAR가 그대로 사용해야 한다.
  if [[ -f "$old_env" ]]; then
    "$py" "$helper" compare-env "$old_env" --candidate "$jar" >/dev/null || return 1
  elif [[ -f "$DEPLOY_APP_HOME/app/deploy-ops.jar" ]]; then
    "$py" "$helper" compare "$DEPLOY_APP_HOME/app/deploy-ops.jar" --candidate "$jar" >/dev/null || return 1
  fi
  local fields_file
  fields_file="$(mktemp)" || return 1
  chmod 600 "$fields_file" || { rm -f "$fields_file"; return 1; }
  "$py" "$helper" fields "$jar" > "$fields_file" || { rm -f "$fields_file"; return 1; }
  mapfile -d '' -t values < "$fields_file" || { rm -f "$fields_file"; return 1; }
  rm -f "$fields_file" || return 1
  local db_url="${values[0]}" db_user="${values[1]}" db_pass="${values[2]}" app_port="${values[3]}"
  local db_name db_host db_port rest root_pass heap
  rest="${db_url#*//}"; db_host="${rest%%:*}"; rest="${rest#*:}"
  db_port="${rest%%/*}"; rest="${rest#*/}"; db_name="${rest%%\?*}"
  docker_cli="$(deploy_system_docker_cli)" || return 1
  [[ "$(java_status)" == "installed" ]] || java_install || return 1
  heap="$(ask DEPLOYOPS_HEAP "앱 최대 힙 [2g]: " "2g")"
  # 이전 설정/JAR/unit 보존 실패시 DB 또는 앱 파일 변경 단계로 진행하지 않는다.
  if [[ -f "$old_env" || -f "$DEPLOY_APP_HOME/app/deploy-ops.jar" ]]; then
    local preimage="/var/backups/deploy-system/pre-embedded-$(date +%Y%m%d-%H%M%S)-$$"
    deploy_system_preserve_current_install "$DEPLOY_APP_HOME" "$preimage" "/etc/systemd/system/$DEPLOY_SERVICE.service" || return 1
  fi
  if ask_confirm DEPLOYOPS_CREATE_DB "DB($db_name)/계정($db_user)을 지금 생성할까요? (MariaDB root 필요)" y; then
    root_pass="$(ask_secret MARIADB_ROOT_PASSWORD "MariaDB root 비밀번호 [없으면 엔터, 소켓인증]: " allow_empty)" || return 1
    deploy_system_provision_local_db "$db_host" "$db_port" "$db_name" "$db_user" "$db_pass" "$root_pass" || return 1
  fi
  ensure_system_user "$DEPLOY_APP_USER" "$DEPLOY_APP_HOME" || return 1
  local tls_args=(--jar "$jar" --app-group "$DEPLOY_APP_USER")
  [[ -z "${DEPLOY_TLS_KEYSTORE_FILE:-}" ]] || tls_args+=(--source "$DEPLOY_TLS_KEYSTORE_FILE")
  "$py" -I -B "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" "${tls_args[@]}" || return 1
  local health_args=(--jar "$jar" --mode configure-health --app-group "$DEPLOY_APP_USER")
  [[ -z "${DEPLOY_HEALTHCHECK_URL:-}" ]] || health_args+=(--health-url "$DEPLOY_HEALTHCHECK_URL")
  [[ -z "${DEPLOY_TLS_CA_FILE:-}" ]] || health_args+=(--ca-file "$DEPLOY_TLS_CA_FILE")
  "$py" -I -B "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" "${health_args[@]}" || return 1
  mkdir -p "$DEPLOY_APP_HOME/app" "$DEPLOY_APP_HOME/config" "$DEPLOY_APP_HOME/logs" "${values[9]}" || return 1
  install -m 0640 -o "$DEPLOY_APP_USER" -g "$DEPLOY_APP_USER" "$jar" "$DEPLOY_APP_HOME/app/deploy-ops.jar.new" || return 1
  mv -f "$DEPLOY_APP_HOME/app/deploy-ops.jar.new" "$DEPLOY_APP_HOME/app/deploy-ops.jar" || return 1
  install -d -m 755 /usr/local/deploy/bin || return 1
  install -m 750 -o root -g root "$helper" /usr/local/deploy/bin/deployops-embedded-config.py || return 1
  install -m 750 -o root -g root "$SCRIPT_DIR/deploy-system/maintenance.py" /usr/local/deploy/bin/deployops-maintenance.py || return 1
  install -m 750 -o root -g root "$SCRIPT_DIR/deploy-system/patch-deployops.sh" /usr/local/deploy/bin/patch-deployops.sh || return 1
  install -m 750 -o root -g root "$SCRIPT_DIR/deploy-system/install-tls-keystore.py" /usr/local/deploy/bin/deployops-tls.py || return 1
  install -m 750 -o root -g root "$SCRIPT_DIR/deploy-system/deployops-state.py" /usr/local/deploy/bin/deployops-state.py || return 1
  chown -R "$DEPLOY_APP_USER":"$DEPLOY_APP_USER" "$DEPLOY_APP_HOME" || return 1
  write_systemd_service "$DEPLOY_SERVICE" "$(cat <<EOF
[Unit]
Description=Deploy System (embedded YAML)
After=network-online.target mariadb.service docker.service containerd.service
Wants=network-online.target docker.service

[Service]
Type=simple
User=$DEPLOY_APP_USER
SupplementaryGroups=docker
WorkingDirectory=$DEPLOY_APP_HOME
UnsetEnvironment=SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD DB_USER DB_PASS SERVER_PORT APP_CRYPTO_SECRET_KEY APP_CRYPTO_KEY_ID APP_CRYPTO_PREVIOUS_KEYS SECRET_ENCRYPTION_KEY SECRET_ENCRYPTION_KEY_ID SECRET_PREVIOUS_KEYS SSO_SHARED_SECRET APP_SSO_SHARED_SECRET APP_SSO_BASE_URL APP_BOOTSTRAP_ADMIN_PASSWORD APP_BOOTSTRAP_IMAGE_TRANSFER_MODE BOOTSTRAP_SAMPLE_DATA_ENABLED DEPLOY_BOOT_RECOVERY_ENABLED APP_NGINX_UPLOAD_DIR NGINX_UPLOAD_DIR DEPLOY_BASE_URL SESSION_COOKIE_SECURE SPRING_APPLICATION_JSON SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_IMPORT SPRING_PROFILES_ACTIVE
Environment=JAVA_HOME=$JAVA_HOME_DIR
ExecStartPre=$docker_cli info --format {{.ServerVersion}}
ExecStart=$JAVA_HOME_DIR/bin/java -Xms512m -Xmx$heap -XX:+ExitOnOutOfMemoryError -jar $DEPLOY_APP_HOME/app/deploy-ops.jar --spring.config.location=optional:classpath:/application-install.yml --spring.config.additional-location= --spring.profiles.active=
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
)" || return 1
  systemctl enable "$DEPLOY_SERVICE" >/dev/null 2>&1 || return 1
  systemctl restart "$DEPLOY_SERVICE" || return 1
  deploy_system_healthcheck "$jar" "$app_port" "$py" || return 1
  deploy_system_install_nexus_backup_broker || return 1
  _deploy_system_install_backup "$app_port" || return 1
  # 복호화 키는 이제 JAR에 있다. 이전 env는 보호된 이전 이미지에만 남긴다.
  [[ ! -f "$old_env" ]] || rm -f "$old_env" || return 1
  _deploy_system_write_credentials "$app_port" || return 1
}

# 백업 스크립트 + 매일 타이머.
#   DB 와 설정(=복호화 키)을 함께 받는다. 하나만 있으면 복원해도 시크릿을 읽을 수 없다.
#   "일 1회 논리 덤프"는 서버개설신청서에 약속한 항목이라, 사람이 cron 을 짜게 두지 않는다.
_deploy_system_install_backup() {
  local port="$1"
  local src="$SCRIPT_DIR/deploy-system/deployops-backup.sh"
  local bin="/usr/local/deploy/bin/deployops-backup.sh"
  local backup_dir keep_daily

  [[ -f "$src" ]] || { err "백업 스크립트가 없습니다: $src"; return 1; }

  backup_dir="$(ask DEPLOYOPS_BACKUP_DIR "백업 보관 경로 [/var/backups/deploy-system]: " "/var/backups/deploy-system")"
  keep_daily="$(ask DEPLOYOPS_KEEP_DAILY "일 단위 보관 개수 [7]: " "7")"

  install -d -m 755 /usr/local/deploy/bin || return 1
  install -m 750 -o root -g root "$src" "$bin" || return 1
  install -d -m 700 "$backup_dir" || return 1

  cat > /etc/systemd/system/deployops-backup.service <<EOF || return 1
[Unit]
Description=Deploy System backup (DB + config/key)
After=mariadb.service $DEPLOY_SERVICE.service

[Service]
Type=oneshot
Environment=DEPLOY_APP_HOME=$DEPLOY_APP_HOME
Environment=DEPLOYOPS_BACKUP_DIR=$backup_dir
Environment=DEPLOYOPS_KEEP_DAILY=$keep_daily
ExecStart=$bin
EOF

  cat > /etc/systemd/system/deployops-backup.timer <<'EOF' || return 1
[Unit]
Description=Daily Deploy System backup

[Timer]
OnCalendar=*-*-* 03:20:00
RandomizedDelaySec=600
Persistent=true

[Install]
WantedBy=timers.target
EOF

  systemctl daemon-reload || return 1
  if systemctl enable --now deployops-backup.timer >/dev/null 2>&1; then
    success "백업 타이머 등록: 매일 03:20 → $backup_dir"
  else
    warn "백업 타이머 등록 실패 — systemctl status deployops-backup.timer 확인"
    return 1
  fi

  # 설치 직후 1회 돌려 실제로 되는지 확인한다. 여기서 실패하면 운영 중에도 실패한다.
  info "  · 첫 백업을 지금 한 번 실행합니다..."
  if "$bin" >/dev/null 2>&1; then
    success "  · 첫 백업 성공 — $(ls -1dt "$backup_dir"/*/*/ 2>/dev/null | head -1)"
  else
    warn "  · 첫 백업 실패 — 수동 확인: sudo $bin"
    return 1
  fi
}

# 설치 결과 요약을 파일 하나로 남긴다.
#   시크릿을 자동 생성해 주는 이상, 그 값을 어딘가에는 안전하게 적어 둬야 한다.
#   특히 암호화 키는 잃으면 저장된 GitLab 토큰·레지스트리 비밀번호를 다시 볼 수 없다.
_deploy_system_write_credentials() {
  local port="$1" f="$DEPLOY_APP_HOME/config/설치결과.txt"
  local access_url="${DEPLOY_HEALTHCHECK_URL:-http://<서버 주소>:$port/health}"
  access_url="${access_url%/health}"
  umask 077
  cat > "$f" <<EOF || return 1
배포시스템 설치 결과
접속: $access_url
관리자: admin (초기 비밀번호는 빌드한 private YAML 참조)
설정·DB 접속정보·복호화 키: app/deploy-ops.jar 내부 application-install.yml
정기 백업: DB + 동일 JAR + config를 함께 보관
패치: sudo /usr/local/deploy/bin/patch-deployops.sh /반입경로/deploy-ops.jar
JAR에 운영 접속정보가 있으므로 접근 권한을 제한하고 비공개로 반입하세요.
EOF
  chown "$DEPLOY_APP_USER":"$DEPLOY_APP_USER" "$f" || return 1
  chmod 600 "$f" || return 1
  success "설치 완료: $f (비밀번호·키는 별도 파일에 기록하지 않음)"
}

# 공용 헬스체크 (최대 60초 대기)
app_healthcheck() {
  local url="$1" svc="$2" i
  info "헬스체크 대기: $url"
  for i in $(seq 1 30); do
    if curl -fsS -o /dev/null --max-time 3 "$url" 2>/dev/null; then
      success "$svc 정상 응답 ($url)"; return 0
    fi
    sleep 2
  done
  warn "$svc 헬스체크 미응답 — journalctl -u $svc 로 로그 확인 필요."
  return 1
}
