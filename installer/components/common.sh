#!/usr/bin/env bash
# common.sh - 컴포넌트 공용 헬퍼 (install.sh 에서 source)
# Java(Temurin tarball, OS무관), MariaDB 접속, systemd 유닛 작성 등.

JAVA_HOME_DIR="${JAVA_HOME_DIR:-/usr/lib/jvm/temurin-21}"
JAVA_PROFILE="/etc/profile.d/temurin21.sh"

java_parent_ready() {
  local parent mode
  [[ "$JAVA_HOME_DIR" == /* ]] || return 1
  parent="$(dirname -- "$JAVA_HOME_DIR")"
  while :; do
    [[ -d "$parent" && ! -L "$parent" && "$(stat -c %u -- "$parent")" == 0 ]] || return 1
    mode="$(stat -c %a -- "$parent")" || return 1
    (( (8#$mode & 0001) != 0 )) || return 1
    [[ "$parent" != /usr/lib/jvm || "$mode" == 755 ]] || return 1
    [[ "$parent" != / ]] || break
    parent="$(dirname -- "$parent")"
  done
}

java_prepare_parent() {
  local ancestor mode
  # 서비스 사용자가 Java에 접근해야 한다. 임의 custom 부모의 권한은 바꾸지 않는다.
  if [[ "$JAVA_HOME_DIR" == /usr/lib/jvm/* ]]; then
    for ancestor in / /usr /usr/lib; do
      [[ -d "$ancestor" && ! -L "$ancestor" && "$(stat -c %u -- "$ancestor")" == 0 ]] \
        || { err "Java 표준 부모의 상위 경로를 확인하세요."; return 1; }
      mode="$(stat -c %a -- "$ancestor")" || return 1
      (( (8#$mode & 0001) != 0 )) \
        || { err "Java 표준 부모의 상위 탐색 권한을 확인하세요."; return 1; }
    done
    [[ ! -L /usr/lib/jvm && ( ! -e /usr/lib/jvm || -d /usr/lib/jvm ) ]] \
      || { err "Java 표준 부모는 실제 디렉터리여야 합니다: /usr/lib/jvm"; return 1; }
    mkdir -p -- /usr/lib/jvm || return 1
    [[ "$(stat -c %u -- /usr/lib/jvm)" == 0 ]] \
      || { err "Java 표준 부모의 root 소유권을 확인하세요: /usr/lib/jvm"; return 1; }
    chmod 0755 -- /usr/lib/jvm || return 1
  fi
  java_parent_ready \
    || { err "Java 부모 경로의 실제 디렉터리·root 소유권·서비스 사용자 탐색 권한을 확인하세요."; return 1; }
}

java_status() {
  if [[ ! -x "$JAVA_HOME_DIR/bin/java" ]]; then echo missing
  elif java_parent_ready; then echo installed
  else echo drift; fi
}

# 공용 번들의 OpenJDK21 tarball 을 /usr/lib/jvm 에 풀고 PATH 등록 (OS무관)
java_install() {
  java_prepare_parent || return 1
  if [[ "$(java_status)" == "installed" ]] && [[ "${1:-}" != "force" ]]; then
    info "Java 21 이미 설치됨: $JAVA_HOME_DIR"; return 0
  fi
  local tarball actual_sha
  tarball="$(bundle_file java "$BOM_JAVA_ARCHIVE")" || true
  if [[ -z "${tarball:-}" ]]; then
    err "승인된 Java archive를 번들에서 찾을 수 없습니다: $BOM_JAVA_ARCHIVE"
    return 1
  fi
  if [[ "${OFFLINE_VERIFY_AUTHENTICITY:-0}" == 1 ]]; then
  actual_sha="$(sha256sum "$tarball" | awk '{print $1}')"
  [[ "$actual_sha" == "$BOM_JAVA_SHA256" ]] \
    || { err "Java archive SHA-256이 release BOM과 다릅니다."; return 1; }
  fi
  info "Java 21 설치: $tarball"
  local tmp extracted version_text previous java_parent
  java_parent="$(dirname -- "$JAVA_HOME_DIR")"
  # /tmp is commonly mounted noexec on production hosts. Validate the JDK on
  # its destination filesystem, then publish by rename without crossing mounts.
  tmp="$(mktemp -d -- "$java_parent/.temurin21-install.XXXXXXXX")" || return 1
  chmod 0700 -- "$tmp" || { rm -rf -- "$tmp"; return 1; }
  if ! tar -xzf "$tarball" -C "$tmp"; then
    rm -rf -- "$tmp"; err "Java archive를 풀 수 없습니다."; return 1
  fi
  local roots=()
  mapfile -t roots < <(find "$tmp" -maxdepth 1 -mindepth 1 -type d)
  if [[ "${#roots[@]}" != 1 || ! -x "${roots[0]}/bin/java" ]]; then
    rm -rf -- "$tmp"; err "Java archive에 실행 가능한 단일 JDK가 없습니다."; return 1
  fi
  extracted="${roots[0]}"
  if ! version_text="$("$extracted/bin/java" -version 2>&1)" \
      || [[ ! "$version_text" =~ version[[:space:]]+\"21([.\"]|$) ]]; then
    rm -rf -- "$tmp"; err "Java 21 실행 검증에 실패했습니다."; return 1
  fi
  previous="${JAVA_HOME_DIR}.previous-$(basename "$tmp")"
  if [[ -e "$previous" || -L "$previous" ]]; then rm -rf -- "$tmp"; return 1; fi
  if [[ -e "$JAVA_HOME_DIR" || -L "$JAVA_HOME_DIR" ]]; then
    mv -- "$JAVA_HOME_DIR" "$previous" || { rm -rf -- "$tmp"; return 1; }
  fi
  if ! mv -- "$extracted" "$JAVA_HOME_DIR"; then
    [[ ! -e "$previous" && ! -L "$previous" ]] || mv -- "$previous" "$JAVA_HOME_DIR"
    rm -rf -- "$tmp"; return 1
  fi
  rm -rf -- "$tmp" "$previous"
  cat > "$JAVA_PROFILE" <<EOF
export JAVA_HOME=$JAVA_HOME_DIR
export PATH=\$JAVA_HOME/bin:\$PATH
EOF
  chmod 0644 "$JAVA_PROFILE"
  "$JAVA_HOME_DIR/bin/java" -version || { err "Java 검증 실패"; return 1; }
  success "Java 21 설치 완료."
}

# mysql/mariadb 클라이언트 바이너리명
mysql_cli() { command -v mariadb >/dev/null 2>&1 && echo mariadb || echo mysql; }

# root 로 SQL 실행 (소켓 인증 우선, 실패 시 비밀번호)
db_root_exec() {
  local sql="$1" root_pass="${2:-}"
  local cli; cli="$(mysql_cli)"
  # stdin carries SQL; the protected option file keeps credentials out of argv.
  (
    umask 077
    local options escaped cleanup
    options="$(mktemp)" || exit 1
    printf -v cleanup 'rm -f -- %q' "$options"
    trap "$cleanup" EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    [[ "$root_pass" != *$'\n'* && "$root_pass" != *$'\r'* ]] || exit 1
    escaped="${root_pass//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    printf '[client]\nuser=root\npassword="%s"\n' "$escaped" > "$options" || exit 1
    "$cli" --defaults-file="$options" --protocol=socket <<< "$sql"
  )
}

# 앱용 DB/계정 생성 (멱등·결정적)
#   기존 사용자가 있어도 ALTER USER 로 비밀번호를 입력값에 맞춘다 —
#   CREATE IF NOT EXISTS 만 쓰면 재설치 시 옛 비번이 남아 앱이 Access denied 로 기동 실패한다.
db_ensure_schema_and_user() {
  local db="$1" user="$2" pass="$3" root_pass="${4:-}"
  [[ "$db" =~ ^[A-Za-z0-9_]+$ && "$user" =~ ^[A-Za-z0-9_.@-]+$ ]] \
    || { err "DB명/계정명에 지원하지 않는 문자가 있습니다."; return 1; }
  [[ "$pass" != *$'\n'* && "$pass" != *$'\r'* ]] \
    || { err "DB 비밀번호에는 줄바꿈을 사용할 수 없습니다."; return 1; }
  local sql quoted_pass="${pass//\'/\'\'}"
  # 이 SQL 세션에만 표준 따옴표 규칙을 적용해 비밀번호의 역슬래시도 그대로 보존한다.
  # https://mariadb.com/docs/server/reference/sql-structure/sql-language-structure/string-literals
  sql="SET SESSION sql_mode=CONCAT_WS(',', @@SESSION.sql_mode, 'NO_BACKSLASH_ESCAPES');
CREATE DATABASE IF NOT EXISTS \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$user'@'localhost' IDENTIFIED BY '$quoted_pass';
CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$quoted_pass';
ALTER USER '$user'@'localhost' IDENTIFIED BY '$quoted_pass';
ALTER USER '$user'@'%' IDENTIFIED BY '$quoted_pass';
GRANT ALL PRIVILEGES ON \`$db\`.* TO '$user'@'localhost';
GRANT ALL PRIVILEGES ON \`$db\`.* TO '$user'@'%';
FLUSH PRIVILEGES;"
  db_root_exec "$sql" "$root_pass"
}

# systemd 서비스 유닛 작성 후 enable --now (멱등)
write_systemd_service() {
  local name="$1" content="$2" stage
  [[ "$name" =~ ^[A-Za-z0-9_.@-]+$ ]] || return 1
  stage="$(mktemp "/etc/systemd/system/.${name}.XXXXXX")" || return 1
  if ! printf '%s\n' "$content" > "$stage" || ! chmod 0644 "$stage" \
      || ! mv -f -- "$stage" "/etc/systemd/system/${name}.service"; then
    rm -f -- "$stage"; return 1
  fi
  systemctl daemon-reload || return 1
}

# ---- Docker 이미지 컴포넌트 공용 헬퍼 ----
docker_available()  { command -v docker >/dev/null 2>&1; }
image_present()     { docker image inspect "$1" >/dev/null 2>&1; }
container_exists()  { docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$1"; }
container_running() { docker ps    --format '{{.Names}}' 2>/dev/null | grep -qx "$1"; }

image_id_of() {
  docker image inspect --format '{{.Id}}' "$1" 2>/dev/null
}

image_matches_archive_id() {
  local image="$1" expected_id="$2" prefix config_key platform_key platform_digest=''
  release_image_id_valid "$expected_id" || return 1
  for prefix in GITLAB RUNNER RUNNER_HELPER RUNNER_BUILD NEXUS; do
    config_key="LOCK_${prefix}_CONFIG_DIGEST"
    platform_key="LOCK_${prefix}_PLATFORM_DIGEST"
    if [[ "${!config_key:-}" == "$expected_id" ]]; then
      platform_digest="${!platform_key:-}"
      break
    fi
  done
  docker_image_matches_config "$image" "$expected_id" "$platform_digest"
}

container_matches_archive_id() {
  local container="$1" expected_id="$2" actual
  release_image_id_valid "$expected_id" || return 1
  actual="$(docker inspect --format '{{.Image}}' "$container" 2>/dev/null)" || return 1
  release_image_id_valid "$actual" || return 1
  image_matches_archive_id "$actual" "$expected_id"
}

container_runtime_healthy() {
  local container="$1" state
  container_running "$container" || return 1
  state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null)" \
    || return 1
  [[ "$state" == "none" || "$state" == "healthy" ]]
}

container_port_binding_matches() {
  local container="$1" container_port="$2" expected_host_port="$3" bindings
  bindings="$(docker port "$container" "$container_port" 2>/dev/null)" || return 1
  printf '%s\n' "$bindings" | awk -F: -v expected="$expected_host_port" '$NF == expected { found=1 } END { exit !found }'
}

container_mount_destination_present() {
  local container="$1" destination="$2"
  docker inspect --format '{{range .Mounts}}{{println .Destination}}{{end}}' "$container" 2>/dev/null \
    | grep -Fxq "$destination"
}

container_mount_source_matches() {
  local container="$1" destination="$2" expected_source="$3"
  docker inspect --format '{{range .Mounts}}{{printf "%s|%s\n" .Source .Destination}}{{end}}' \
    "$container" 2>/dev/null \
    | awk -F'|' -v destination="$destination" -v source="$expected_source" \
      '$2 == destination && $1 == source { found=1 } END { exit !found }'
}

container_inspect_value_matches() {
  local container="$1" format="$2" expected="$3" actual
  actual="$(docker inspect --format "$format" "$container" 2>/dev/null)" || return 1
  [[ "$actual" == "$expected" ]]
}

container_environment_value() {
  local container="$1" name="$2"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" 2>/dev/null \
    | sed -n "s/^${name}=//p" | head -1
}

wait_container_contract() {
  local container="$1" url="${2:-}" attempts="${3:-60}" i
  for i in $(seq 1 "$attempts"); do
    if container_runtime_healthy "$container"; then
      if [[ -z "$url" ]] || curl -fsS -o /dev/null --max-time 3 "$url" 2>/dev/null; then
        return 0
      fi
    fi
    sleep 5
  done
  return 1
}

# 번들(component)에 있는 *.tar / *.tar.gz 이미지를 모두 docker load
docker_load_bundle() {
  local component="$1" dir f loaded=0
  dir="$(bundle_dir "$component")" || { err "이미지 번들 없음: $component"; return 1; }
  shopt -s nullglob
  for f in "$dir"/*.tar.gz "$dir"/*.tgz "$dir"/*.tar; do
    info "docker load < $(basename "$f")"
    if [[ "$f" == *.gz || "$f" == *.tgz ]]; then
      gunzip -c "$f" | docker load
    else
      docker load -i "$f"
    fi
    loaded=1
  done
  shopt -u nullglob
  [[ "$loaded" == 1 ]] || { err "로드할 이미지 tar가 없습니다: $dir"; return 1; }
}

# 컨테이너가 이미 있으면 재생성 여부 확인 후 제거 — 무인: FORCE_REINSTALL=y 일 때만 재생성
recreate_container_if_confirmed() {
  local name="$1"
  if container_exists "$name"; then
    ask_confirm FORCE_REINSTALL "$name 컨테이너가 이미 있습니다. 제거 후 재생성할까요?" n || return 1
    docker rm -f "$name" >/dev/null 2>&1 || true
  fi
  return 0
}

# 전용 시스템 계정 보장
ensure_system_user() {
  local user="$1" home="${2:-}"
  if ! id "$user" >/dev/null 2>&1; then
    if [[ -n "$home" ]]; then
      useradd --system --create-home --home-dir "$home" --shell /usr/sbin/nologin "$user" 2>/dev/null \
        || useradd --system --create-home --home-dir "$home" --shell /sbin/nologin "$user"
    else
      useradd --system --shell /usr/sbin/nologin "$user" 2>/dev/null \
        || useradd --system --shell /sbin/nologin "$user"
    fi
  fi
}
