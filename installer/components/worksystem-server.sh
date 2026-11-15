#!/usr/bin/env bash
# worksystem-server.sh - 오피스 웹(worksystem-server) 앱 설치 컴포넌트

WS_APP_HOME="${WS_APP_HOME:-/opt/worksystem}"
WS_APP_USER="${WS_APP_USER:-worksystem}"
WS_SERVICE="worksystem-server"

worksystem_server_status() {
  if unit_exists "${WS_SERVICE}\\.service"; then
    local installed_jar="$WS_APP_HOME/app/worksystem.jar" actual_sha="" matches=1
    [[ -f "$installed_jar" ]] || matches=0
    if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 && "$matches" == 1 ]]; then
      actual_sha="$(sha256sum "$installed_jar" 2>/dev/null | awk '{print $1}')"
      [[ "$actual_sha" == "${LOCK_WORKSYSTEM_SHA256:-<unavailable>}" ]] || matches=0
    fi
    if [[ "$matches" == 1 ]] && systemctl is-active --quiet "$WS_SERVICE" 2>/dev/null; then echo installed; else echo drift; fi
  elif [[ -f "$WS_APP_HOME/app/worksystem.jar" ]]; then echo partial
  else echo missing; fi
}

worksystem_server_install() {
  local force="${1:-}"
  if [[ "$(worksystem_server_status)" == "installed" && "$force" != "force" ]]; then
    ask_confirm FORCE_REINSTALL "오피스 웹이 이미 설치되어 있습니다. 재배포할까요?" n || { info "건너뜀."; return 0; }
  fi
  [[ "$(java_status)" == "installed" ]] || java_install || return 1

  local jar="$BUNDLE_ROOT/$BOM_WORKSYSTEM_JAR" jar_sha
  [[ -f "$jar" ]] || { err "worksystem jar 없음: $BOM_WORKSYSTEM_JAR"; return 1; }
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
  jar_sha="$(sha256sum "$jar" | awk '{print $1}')"
  [[ "$jar_sha" == "${LOCK_WORKSYSTEM_SHA256:-}" ]] \
    || { err "Worksystem JAR SHA-256이 검증된 release lock과 다릅니다."; return 1; }
  fi
  ensure_system_user "$WS_APP_USER" "$WS_APP_HOME"
  mkdir -p "$WS_APP_HOME/app" "$WS_APP_HOME/config" "$WS_APP_HOME/logs"
  install -m 0640 -o "$WS_APP_USER" -g "$WS_APP_USER" "$jar" "$WS_APP_HOME/app/worksystem.jar.new"
  mv -f "$WS_APP_HOME/app/worksystem.jar.new" "$WS_APP_HOME/app/worksystem.jar"

  # 무인 응답 키: WORKSYSTEM_DB_{HOST,PORT,NAME,USER,PASSWORD} / WORKSYSTEM_APP_PORT /
  #   DEPLOY_SYSTEM_URL / SSO_SHARED_SECRET(배포시스템과 공용) / ANONYMITY_SECRET /
  #   WORKSYSTEM_CREATE_DB(y|n, 기본 y) / MARIADB_ROOT_PASSWORD
  local db_name db_user db_pass db_host db_port app_port root_pass
  local deploy_url sso_secret anon_secret heap
  local admin_user admin_pass admin_name
  db_host="$(ask WORKSYSTEM_DB_HOST "DB host [127.0.0.1]: " "127.0.0.1")"
  db_port="$(ask WORKSYSTEM_DB_PORT "DB port [3306]: " "3306")"
  db_name="$(ask WORKSYSTEM_DB_NAME "DB name [worksystem]: " "worksystem")"
  db_user="$(ask WORKSYSTEM_DB_USER "DB user [worksystem]: " "worksystem")"
  db_pass="$(ask_secret WORKSYSTEM_DB_PASSWORD "DB password: ")" || return 1
  app_port="$(ask WORKSYSTEM_APP_PORT "앱 포트 [18080]: " "18080")"
  # 미지정 시 JVM 기본값(물리 RAM 의 1/4)이라 호스트마다 달라진다. 동시 60명 기준 2g 로 충분.
  heap="$(ask WORKSYSTEM_HEAP "앱 최대 힙 [2g]: " "2g")"
  deploy_url="$(ask DEPLOY_SYSTEM_URL "배포시스템 URL (DEPLOY_SYSTEM_URL) [http://127.0.0.1:9090]: " "http://127.0.0.1:9090")"
  sso_secret="$(ask_secret SSO_SHARED_SECRET "DEPLOY_SSO_SHARED_SECRET (배포시스템과 동일하게): ")" || return 1
  anon_secret="$(ask_secret ANONYMITY_SECRET "ANONYMITY_SECRET: ")" || return 1
  # 신규 설치는 로그인할 계정이 없다(Flyway는 스키마만 만든다). 슈퍼관리자 1개를 여기서 받아
  # 앱이 첫 기동 때 만든다. 이미 같은 ID가 있으면 앱이 건너뛰므로 재설치해도 안전하다.
  admin_user="$(ask WORKSYSTEM_ADMIN_USER "슈퍼관리자 ID [demo-admin]: " "demo-admin")"
  admin_name="$(ask WORKSYSTEM_ADMIN_NAME "슈퍼관리자 표시이름 [관리자]: " "관리자")"
  admin_pass="$(ask_secret WORKSYSTEM_ADMIN_PASSWORD "슈퍼관리자 비밀번호: ")" || return 1

  if ask_confirm WORKSYSTEM_CREATE_DB "DB($db_name)/계정($db_user)을 지금 생성할까요? (MariaDB root 필요)" y; then
    root_pass="$(ask_secret MARIADB_ROOT_PASSWORD "MariaDB root 비밀번호 [없으면 엔터, 소켓인증]: " allow_empty)"
    db_ensure_schema_and_user "$db_name" "$db_user" "$db_pass" "$root_pass" \
      || { err "DB 생성 실패"; return 1; }
  fi

  cat > "$WS_APP_HOME/config/worksystem.env" <<EOF
SERVER_PORT=$app_port
SPRING_DATASOURCE_URL=jdbc:mariadb://$db_host:$db_port/$db_name?characterEncoding=UTF-8&serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=$db_user
SPRING_DATASOURCE_PASSWORD=$db_pass
DEPLOY_SYSTEM_URL=$deploy_url
DEPLOY_SSO_SHARED_SECRET=$sso_secret
ANONYMITY_SECRET=$anon_secret
# 로그(일별 롤링 + 월별 tar.gz 아카이브) 위치. prod 프로필의 기본값은 /var/log/worksystem 인데
# 이 설치기는 앱 홈 하위만 만들고 권한을 주므로, 앱 계정이 확실히 쓸 수 있는 경로로 고정한다.
APP_LOG_DIR=$WS_APP_HOME/logs
# 첫 기동 때 슈퍼관리자 1개 생성(SuperAdminBootstrap). 같은 ID가 있으면 앱이 건너뛴다.
SUPER_ADMIN_USERNAME=$admin_user
SUPER_ADMIN_PASSWORD=$admin_pass
SUPER_ADMIN_NAME=$admin_name
EOF
  chmod 0600 "$WS_APP_HOME/config/worksystem.env"
  chown -R "$WS_APP_USER":"$WS_APP_USER" "$WS_APP_HOME"

  write_systemd_service "$WS_SERVICE" "$(cat <<EOF
[Unit]
Description=Office Web (worksystem-server)
After=network-online.target mariadb.service docker.service
Wants=network-online.target

[Service]
Type=simple
User=$WS_APP_USER
WorkingDirectory=$WS_APP_HOME
EnvironmentFile=$WS_APP_HOME/config/worksystem.env
Environment=JAVA_HOME=$JAVA_HOME_DIR
ExecStart=$JAVA_HOME_DIR/bin/java -Xms512m -Xmx$heap -XX:+ExitOnOutOfMemoryError -jar $WS_APP_HOME/app/worksystem.jar --spring.profiles.active=prod
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
)"
  service_enable_now "$WS_SERVICE" || { err "오피스 웹 시작 실패 — journalctl -u $WS_SERVICE"; return 1; }
  app_healthcheck "http://127.0.0.1:$app_port/login" "$WS_SERVICE"
}
