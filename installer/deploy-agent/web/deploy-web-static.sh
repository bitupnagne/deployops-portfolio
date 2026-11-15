#!/bin/bash
# deploy-web-static.sh — STATIC_BUNDLE 배포 에이전트 (React/Vue/Angular 등 정적 산출물).
#   번들(tar.gz)을 릴리스 디렉터리에 풀고 current 심볼릭 링크를 원자적으로 교체한 뒤 nginx 를 리로드한다.
#   실패(nginx -t / reload) 시 직전 릴리스로 즉시 되돌린다. 컨테이너를 쓰지 않는다.
set -euo pipefail

CONFIG_FILE="/usr/local/deploy/conf/web-agent.env"
HEALTHCHECK_SCRIPT="/usr/local/deploy/bin/local-healthcheck.sh"
LOCK_FILE="/var/lock/deploy-ops-web-static.lock"
ACTION=""
BUNDLE=""
BUNDLE_SHA256=""
WEB_ROOT=""
HEALTH_PORT=""
HEALTH_PATH=""
RELOAD_NGINX=1
KEEP_RELEASES=5
STATIC_HEALTH_HOST=""
EXPECTED_RELEASE=""
RESTORE_RELEASE=""

usage(){
  cat <<'EOF'
Usage:
  deploy-web-static.sh deploy --bundle PATH [--bundle-sha256 HEX] --web-root DIR
                              [--health-port PORT] [--health-path PATH]
                              [--reload-nginx true|false] [--keep N]
  deploy-web-static.sh rollback --web-root DIR [--reload-nginx true|false]
  deploy-web-static.sh status --web-root DIR
  deploy-web-static.sh restore-exact --web-root DIR --expected-release ABSOLUTE_CURRENT
                                    --restore-release ABSOLUTE_PRIOR_OR_ABSENT
                                    [--health-port PORT] [--health-path PATH]

번들은 배포시스템(배포망)이 이미지에서 정적 산출물만 꺼내 만든 tar.gz 다.
DMZ 는 그 tar.gz 를 풀고 current 심볼릭 링크를 원자 교체하므로 무중단이고,
이 서버에는 docker 가 필요 없다(공개 접점이라 런타임을 두지 않는다).
--bundle-sha256 을 주면 풀기 전에 무결성을 검증한다.
EOF
}

# ★ '..' 를 명시적으로 막는다. 문자 클래스에 '.' 이 들어 있어 정규식만으로는
#   /var/www/../../etc 같은 경로가 통과한다. web-root 아래에서 install -d 와
#   prune 의 rm -rf 가 돌기 때문에 상위로 새어 나가면 안 된다.
safe_path(){
  [[ "$1" =~ ^/[A-Za-z0-9._/+:-]+$ ]] || return 1
  [[ "$1" == /*/* && "$1" != */ ]] || return 1
  [[ "$1" != *//* ]] || return 1
  [[ "$1" =~ (^|/)\.(/|$) ]] && return 1
  [[ "$1" =~ (^|/)\.\.(/|$) ]] && return 1
  return 0
}
require_root(){ [[ "${EUID}" -eq 0 ]] || { echo "Run as root."; exit 1; }; }

load_defaults(){
  if [[ -f "$CONFIG_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
  fi
  HEALTH_PORT="${HEALTH_PORT:-${DEFAULT_HEALTH_PORT:-80}}"
  HEALTH_PATH="${HEALTH_PATH:-${DEFAULT_HEALTH_PATH:-/}}"
}

parse_args(){
  [[ "$#" -gt 0 ]] || { usage; exit 1; }
  ACTION="$1"; shift
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --bundle) BUNDLE="$2"; shift 2 ;;
      --bundle-sha256) BUNDLE_SHA256="$2"; shift 2 ;;
      --web-root) WEB_ROOT="$2"; shift 2 ;;
      --expected-release) EXPECTED_RELEASE="$2"; shift 2 ;;
      --restore-release) RESTORE_RELEASE="$2"; shift 2 ;;
      --health-port) HEALTH_PORT="$2"; shift 2 ;;
      --health-path) HEALTH_PATH="$2"; shift 2 ;;
      --keep) KEEP_RELEASES="$2"; shift 2 ;;
      --reload-nginx) [[ "$2" == "true" ]] && RELOAD_NGINX=1 || RELOAD_NGINX=0; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown argument: $1"; usage; exit 1 ;;
    esac
  done
}

nginx_ready(){
  command -v nginx >/dev/null 2>&1 || { echo "nginx is required for static deploy."; exit 1; }
}

# 배포 경로를 root 지시자로 명시한 활성 Nginx 설정이 있는지 참고용으로 진단한다.
# nginx -T 는 실제 include된 설정만 출력하므로 conf.d의 숨김/비활성 파일 오탐을 피한다.
# 요청 라우팅 전체를 증명하지는 않지만, root 경로가 다르면 안전하게 배포를 중단한다.
warn_if_web_root_not_served(){
  local web_root="$1" found
  # Inspect Nginx's expanded, active configuration. Grepping conf.d directly also
  # sees dotfiles and disabled snippets that Nginx's include globs ignore, which
  # can falsely claim that the release directory is served.
  found="$(nginx -T 2>/dev/null | awk -v expected="${web_root}/current" '
    {
      line = $0
      sub(/[[:space:]]*#.*/, "", line)
      statement = statement " " line
      while ((end = index(statement, ";")) > 0) {
        directive = substr(statement, 1, end - 1)
        statement = substr(statement, end + 1)
        sub(/^.*\{/, "", directive)
        gsub(/}/, " ", directive)
        sub(/^[[:space:]]+/, "", directive)
        if (directive !~ /^root[[:space:]]+/) continue
        sub(/^root[[:space:]]+/, "", directive)
        value = directive
        sub(/[[:space:]]+$/, "", value)
        sub(/^"/, "", value)
        sub(/"$/, "", value)
        quote = sprintf("%c", 39)
        sub("^" quote, "", value)
        sub(quote "$", "", value)
        sub(/\/+$/, "", value)
        sub(/\/+$/, "", expected)
        if (value == expected) found = 1
      }
    }
    END { if (found) print "true" }
  ' | tail -n 1)"
  if [[ "$found" == "true" ]]; then
    echo "ACTIVE_ROOT_DIRECTIVE=true"
    return 0
  fi
  echo "ACTIVE_ROOT_DIRECTIVE=false"
  echo "경고: 활성 Nginx 설정에서 root ${web_root}/current 지시자를 찾지 못했습니다." >&2
  echo "  새 릴리스가 실제 서비스되지 않을 수 있어 배포를 중단합니다." >&2
  echo "  설치기의 정적 사이트 설정(NGINX_STATIC_ROOT)이나 승인형 Nginx 정책으로 server 블록을 먼저 만드세요." >&2
  return 1
}

# Resolve the virtual host that actually serves this release root. A plain curl to
# 127.0.0.1 can hit a different default_server and report a false green response.
# Prefer an exact server_name; for an explicitly configured default_server, use a
# reserved .invalid host so Nginx selects that default block without touching DNS.
active_health_host_for_web_root(){
  local web_root="$1" expected="${1}/current" host
  host="$(nginx -T 2>/dev/null | awk -v expected="$expected" '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    {
      line = $0
      sub(/[[:space:]]*#.*/, "", line)
      if (!inside && line ~ /^[[:space:]]*server[[:space:]]*\{/) {
        inside = 1
        depth = 0
        root_matches = 0
        has_default = 0
        exact_host = ""
      }
      if (!inside) next

      directive = line
      sub(/[[:space:]]*;.*/, "", directive)
      directive = trim(directive)
      if (directive ~ /^listen[[:space:]]+/ && directive ~ /(^|[[:space:]])default_server([[:space:]]|$)/) {
        has_default = 1
      }
      if (directive ~ /^server_name[[:space:]]+/) {
        sub(/^server_name[[:space:]]+/, "", directive)
        count = split(directive, names, /[[:space:]]+/)
        for (i = 1; i <= count; i++) {
          # Wildcard and regex names need a user-provided health host; they cannot
          # be converted to a safe Host header without guessing their semantics.
          if (names[i] ~ /^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$/ || names[i] ~ /^[A-Za-z0-9]$/) {
            exact_host = names[i]
            break
          }
        }
      }
      if (directive ~ /^root[[:space:]]+/) {
        sub(/^root[[:space:]]+/, "", directive)
        gsub(/^\047|\047$/, "", directive)
        gsub(/^\"|\"$/, "", directive)
        if (directive == expected) root_matches = 1
      }

      opens = gsub(/\{/, "{", line)
      closes = gsub(/\}/, "}", line)
      depth += opens - closes
      if (depth == 0) {
        if (root_matches) {
          if (exact_host != "") print exact_host
          else if (has_default) print "deployops-healthcheck.invalid"
          exit
        }
        inside = 0
      }
    }
  ' | tail -n 1)"
  if [[ -n "$host" ]]; then
    printf '%s\n' "$host"
    return 0
  fi
  echo "Cannot find an exact or default Nginx virtual host for ${expected}; refusing health check." >&2
  return 1
}

# 번들은 배포망이 이미지에서 꺼내 만든 것이라 망을 한 번 건너온다. 풀기 전에 무결성을 확인한다.
# ★ 해시는 선택이 아니라 필수다. 예전에는 --bundle-sha256 이 없으면 검증을 건너뛰었는데,
#   그러면 "검증했다"와 "검증을 안 했다"가 로그에서 구분되지 않는다. 3망을 건너온
#   산출물을 root 로 푸는 자리라 조용히 건너뛸 곳이 아니다.
verify_bundle(){
  [[ -n "$BUNDLE_SHA256" ]] || { echo "--bundle-sha256 is required (integrity check is mandatory)."; exit 1; }
  [[ "$BUNDLE_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid --bundle-sha256 (expect 64 hex chars)."; exit 1; }
  command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum is required for bundle verification."; exit 1; }
  local actual
  actual="$(sha256sum "$BUNDLE" | awk '{print $1}')"
  if [[ "$actual" != "$BUNDLE_SHA256" ]]; then
    echo "Bundle checksum mismatch (expected=$BUNDLE_SHA256 actual=$actual)"
    exit 1
  fi
  echo "Bundle checksum verified: $actual"
}

# ★ tar 는 신뢰할 수 없는 입력으로 다룬다. 해시가 맞아도 <배포망에서 만들어진 내용 자체가
#   잘못된> 경우가 있고, 여기는 root 로 푸는 자리다. 절대경로·상위경로 멤버가 있으면
#   release_dir 밖에 파일을 쓴다.
#   herestring 을 쓰는 이유: `tar -tzf | grep -q` 는 grep 이 먼저 끝나며 tar 가 SIGPIPE 를
#   받고, set -o pipefail 아래서 멀쩡한 번들이 실패로 잡힌다.
verify_bundle_members(){
  local members verbose
  members="$(tar -tzf "$BUNDLE")" || { echo "Cannot read bundle: $BUNDLE"; exit 1; }
  if grep -qE '^/|(^|/)\.\.(/|$)' <<<"$members"; then
    echo "Bundle contains absolute or parent-relative paths. Refusing to extract."
    grep -E '^/|(^|/)\.\.(/|$)' <<<"$members" | head -5
    exit 1
  fi
  verbose="$(tar -tvzf "$BUNDLE")" || { echo "Cannot inspect bundle member types."; exit 1; }
  if awk 'substr($0,1,1) !~ /[-d]/ { bad=1 } END { exit !bad }' <<<"$verbose"; then
    echo "Bundle contains links or special files. Only regular files and directories are allowed."
    exit 1
  fi
}

reload_nginx_or_rollback(){
  local current_link="$1" previous_target="$2" failed_release="${3:-}"
  [[ "$RELOAD_NGINX" -eq 1 ]] || return 0
  if ! nginx -t; then
    echo "nginx -t failed. Restoring previous release."
    restore_previous "$current_link" "$previous_target"
    discard_failed_release "$current_link" "$previous_target" "$failed_release"
    nginx -t || true
    exit 1
  fi
  if ! systemctl reload nginx; then
    echo "nginx reload failed. Restoring previous release."
    restore_previous "$current_link" "$previous_target"
    discard_failed_release "$current_link" "$previous_target" "$failed_release"
    nginx -t || true
    systemctl reload nginx || true
    exit 1
  fi
  echo "nginx reloaded for static release."
}

restore_previous(){
  local current_link="$1" previous_target="$2"
  if [[ -n "$previous_target" && -d "$previous_target" ]]; then
    ln -sfn "$previous_target" "$current_link"
    echo "Restored previous release: $current_link -> $previous_target"
    return 0
  fi
  # ★ 최초 배포에는 되돌아갈 릴리스가 없다. 예전에는 여기서 <아무것도 하지 않고>
  #   "Restoring previous release" 만 찍어서, 깨진 릴리스가 그대로 서비스되는데
  #   로그에는 복구한 것처럼 남았다. 방금 건 링크를 걷어 배포 전 상태로 되돌린다.
  rm -f "$current_link"
  echo "No previous release to restore (first deployment) — removed $current_link."
}

# Refuse a web-root whose existing parent path cannot be traversed by an unprivileged
# nginx worker. Only the managed root/releases are normalized below; do not chmod/chown
# arbitrary ancestors just because the installer ran with umask 077.
require_public_parent_traversal(){
  local web_root="$1" parent current component mode
  local -a components=()
  parent="${web_root%/*}"
  [[ -n "$parent" ]] || parent="/"
  IFS='/' read -r -a components <<< "${parent#/}"
  current="/"
  for component in "${components[@]}"; do
    [[ -n "$component" ]] || continue
    current="${current%/}/$component"
    [[ ! -L "$current" ]] || {
      echo "Web-root parent contains a symbolic link; configure its canonical path explicitly: $current" >&2
      return 1
    }
    if [[ ! -e "$current" ]]; then
      install -d -o root -g root -m 0755 "$current" || {
        echo "Cannot create missing Web-root parent with root:root 0755: $current" >&2
        return 1
      }
    fi
    [[ -d "$current" ]] || {
      echo "Web-root parent is not a directory: $current" >&2
      return 1
    }
    mode="$(stat -c '%a' -- "$current")" || {
      echo "Cannot inspect Web-root parent permissions: $current" >&2
      return 1
    }
    # Conservative by design: require the other-execute bit on every pre-existing
    # ancestor so nginx can traverse it regardless of service-group membership.
    (( (8#$mode & 0001) != 0 )) || {
      echo "Web-root parent is not publicly traversable (other-execute bit required): $current (mode $mode)" >&2
      return 1
    }
  done
}

# Remove an unservable candidate only after current has safely returned to its prior
# release (or was removed for a first-deployment failure). This prevents a failed,
# newer directory from being selected by run_rollback's newest-release logic.
discard_failed_release(){
  local current_link="$1" previous_target="$2" failed_release="$3"
  local expected_root active_target
  [[ -n "$failed_release" && -d "$failed_release" && ! -L "$failed_release" ]] || return 0
  expected_root="${current_link%/current}/releases/"
  [[ "$failed_release" == "$expected_root"* ]] || {
    echo "Refusing to remove failed release outside the configured releases directory: $failed_release" >&2
    return 1
  }
  if [[ -n "$previous_target" ]]; then
    active_target="$(readlink -f "$current_link" 2>/dev/null || true)"
    [[ "$active_target" == "$previous_target" ]] || {
      echo "Refusing to remove failed release until the previous current target is restored." >&2
      return 1
    }
  elif [[ -e "$current_link" || -L "$current_link" ]]; then
    echo "Refusing to remove failed first release while current still exists." >&2
    return 1
  fi
  rm -rf -- "$failed_release"
  echo "Removed failed release after restoring current: $failed_release"
}

prune_releases(){
  local releases_dir="$1" protected_previous="${2:-}"
  # 최신 KEEP_RELEASES 개만 남기고 오래된 릴리스 삭제
  ls -1dt "$releases_dir"/*/ 2>/dev/null | tail -n "+$((KEEP_RELEASES + 1))" | while read -r old; do
    # Group rollback may still need the exact prior release even with --keep 1.
    [[ "${old%/}" != "$protected_previous" ]] || continue
    rm -rf "$old"
  done
}

run_deploy(){
  [[ -n "$WEB_ROOT" ]] || { echo "--web-root is required."; exit 1; }
  safe_path "$WEB_ROOT" || { echo "Invalid web root: $WEB_ROOT"; exit 1; }
  [[ ! -L "$WEB_ROOT" && ! -L "$WEB_ROOT/releases" ]] || {
    echo 'Web root and releases must be real directories, not symbolic links.' >&2
    exit 1
  }

  [[ -n "$BUNDLE" ]] || { echo "--bundle is required."; exit 1; }
  [[ -f "$BUNDLE" ]] || { echo "Bundle not found: $BUNDLE"; exit 1; }
  verify_bundle
  verify_bundle_members
  nginx_ready
  warn_if_web_root_not_served "$WEB_ROOT" || {
    echo 'Active Nginx root does not match the static release path; refusing deployment.' >&2
    return 1
  }
  STATIC_HEALTH_HOST="$(active_health_host_for_web_root "$WEB_ROOT")" || return 1
  require_public_parent_traversal "$WEB_ROOT" || exit 1

  local releases_dir="$WEB_ROOT/releases"
  local current_link="$WEB_ROOT/current"
  local stamp release_dir previous_target
  stamp="$(date +%Y%m%d%H%M%S)"
  previous_target=""
  [[ -L "$current_link" ]] && previous_target="$(readlink -f "$current_link" 2>/dev/null || true)"

  # install 스크립트는 umask 077 로 실행될 수 있다. nginx worker 가 current 의
  # 심볼릭 링크를 따라 releases 까지 접근할 수 있도록 공개 경로 디렉터리만 고정한다.
  # 배포 에이전트는 root 로만 실행되므로 root 소유를 명시하고 파일/상위 경로는 건드리지 않는다.
  install -d -o root -g root -m 0755 "$WEB_ROOT" "$releases_dir"
  # Deployments can be approved and dispatched within the same second. A timestamp-only
  # directory reused the first release and destroyed the rollback target. mktemp reserves a
  # unique directory atomically even when two deployments share the same timestamp.
  release_dir="$(mktemp -d "$releases_dir/${stamp}-XXXXXXXX")"
  chmod 755 "$release_dir"
  if ! tar --no-same-owner -xzf "$BUNDLE" -C "$release_dir"; then
    echo "Bundle extraction failed. Removing incomplete release and keeping current unchanged: $release_dir" >&2
    discard_failed_release "$current_link" "$previous_target" "$release_dir"
    exit 1
  fi
  # Image extraction may create the bundle's top-level directory with mode 0700
  # and restore build-container UID/GID values. Normalize the immutable static
  # release so the unprivileged Nginx worker can traverse and read it.
  if ! chown -R root:root -- "$release_dir" \
      || ! find "$release_dir" -type d -exec chmod 0755 {} + \
      || ! find "$release_dir" -type f -exec chmod 0644 {} +; then
    echo "Cannot normalize static release ownership or permissions: $release_dir" >&2
    discard_failed_release "$current_link" "$previous_target" "$release_dir"
    exit 1
  fi
  # 번들은 전송용 임시 파일이다. 풀었으면 지워 디스크가 차지 않게 한다.
  rm -f "$BUNDLE"

  # current 심볼릭 링크를 원자적으로 교체 (temp 링크 후 rename)
  if ! ln -sfn "$release_dir" "${current_link}.tmp"; then
    echo 'Cannot prepare new current symlink. Removing the incomplete release.' >&2
    rm -f "${current_link}.tmp"
    discard_failed_release "$current_link" "$previous_target" "$release_dir"
    exit 1
  fi
  if ! mv -Tf "${current_link}.tmp" "$current_link"; then
    echo 'Cannot activate new current symlink. Restoring previous release and removing the candidate.' >&2
    rm -f "${current_link}.tmp"
    restore_previous "$current_link" "$previous_target"
    discard_failed_release "$current_link" "$previous_target" "$release_dir"
    exit 1
  fi
  echo "Static release activated: $current_link -> $release_dir"

  reload_nginx_or_rollback "$current_link" "$previous_target" "$release_dir"

  if [[ -x "$HEALTHCHECK_SCRIPT" ]]; then
    "$HEALTHCHECK_SCRIPT" --port "$HEALTH_PORT" --path "$HEALTH_PATH" --host "$STATIC_HEALTH_HOST" || {
      # 왜 실패했는지 남긴다 — nginx 설정 오류인지, current 링크가 엉뚱한 곳인지,
      # 아니면 배포판 기본 server 블록이 포트를 선점했는지가 여기서 갈린다.
      echo
      echo "===== 배포 실패 진단 ====="
      echo "--- nginx 설정 검사 ---"; nginx -t 2>&1 || true
      echo "--- current 링크 ---"; ls -l "$current_link" 2>&1 || true
      echo "--- ${HEALTH_PORT} 포트를 잡은 server 블록 ---"
      grep -rlE "listen[[:space:]]+(\\[::\\]:)?${HEALTH_PORT}\\b" /etc/nginx/conf.d/ 2>/dev/null || echo "(없음)"
      echo "--- nginx 오류 로그 (마지막 40줄) ---"
      tail -n 40 /var/log/nginx/error.log 2>/dev/null || echo "(오류 로그 없음)"
      echo "=========================="
      echo "Health check failed. Restoring previous release."
      restore_previous "$current_link" "$previous_target"
      discard_failed_release "$current_link" "$previous_target" "$release_dir"
      [[ "$RELOAD_NGINX" -eq 1 ]] && systemctl reload nginx || true
      exit 1
    }
  fi

  prune_releases "$releases_dir" "$previous_target"
}

require_exact_release_root(){
  safe_path "$WEB_ROOT" && [[ -d "$WEB_ROOT" && ! -L "$WEB_ROOT" ]] \
    && [[ "$(readlink -e -- "$WEB_ROOT")" == "$WEB_ROOT" ]] \
    || { echo "정확 복원의 web-root는 실제 절대 디렉터리여야 합니다."; return 1; }
  local directory mode ancestor="$WEB_ROOT"
  while :; do
    [[ "$(stat -c '%u' -- "$ancestor")" == 0 ]] || { echo "복원 경로의 상위 디렉터리가 root 소유가 아닙니다."; return 1; }
    mode="$(stat -c '%a' -- "$ancestor")" || return 1
    (( (8#$mode & 0022) == 0 || (8#$mode & 01000) != 0 )) \
      || { echo "복원 경로의 상위 디렉터리에 안전하지 않은 쓰기 권한이 있습니다."; return 1; }
    [[ "$ancestor" != / ]] || break
    ancestor="${ancestor%/*}"; ancestor="${ancestor:-/}"
  done
  for directory in "$WEB_ROOT" "$WEB_ROOT/releases"; do
    [[ -d "$directory" && ! -L "$directory" && "$(stat -c '%u' -- "$directory")" == 0 ]] \
      || { echo "정확 복원 경로는 root 소유 실제 디렉터리여야 합니다."; return 1; }
    mode="$(stat -c '%a' -- "$directory")" || return 1
    (( (8#$mode & 0022) == 0 )) || { echo "정확 복원 경로가 다른 계정에 쓰기 허용되어 있습니다."; return 1; }
  done
}

validate_exact_release(){
  local release="$1" unsafe
  [[ "$release" == ABSENT ]] && return 0
  safe_path "$release" && [[ "${release%/*}" == "$WEB_ROOT/releases" ]] \
    && [[ -d "$release" && ! -L "$release" && "$(readlink -e -- "$release")" == "$release" ]] \
    || { echo "복원 릴리스는 이 web-root의 releases 바로 아래에 있어야 합니다."; return 1; }
  unsafe="$(find "$release" -xdev \( ! -user root -o -perm /022 -o -type l \) -print -quit)" || return 1
  [[ -z "$unsafe" ]] || { echo "릴리스에 소유권·쓰기 권한·심볼릭 링크 문제가 있습니다."; return 1; }
}

exact_current_release(){
  local current="$WEB_ROOT/current" target
  if [[ ! -e "$current" && ! -L "$current" ]]; then printf 'ABSENT\n'; return 0; fi
  [[ -L "$current" ]] || { echo "current가 심볼릭 링크가 아닙니다." >&2; return 1; }
  target="$(readlink -e -- "$current")" || return 1
  validate_exact_release "$target" >&2 || return 1
  printf '%s\n' "$target"
}

set_exact_current(){
  local release="$1" candidate
  if [[ "$release" == ABSENT ]]; then
    [[ ! -e "$WEB_ROOT/current" || -L "$WEB_ROOT/current" ]] || return 1
    rm -f -- "$WEB_ROOT/current"
    return
  fi
  candidate="$(mktemp "$WEB_ROOT/.restore-link.XXXXXXXX")" || return 1
  rm -f -- "$candidate"
  if ! ln -s -- "$release" "$candidate" || ! mv -Tf -- "$candidate" "$WEB_ROOT/current"; then
    rm -f -- "$candidate"
    return 1
  fi
}

run_restore_exact(){
  require_exact_release_root || return 1
  [[ -n "$EXPECTED_RELEASE" && -n "$RESTORE_RELEASE" ]] \
    || { echo "현재 릴리스와 복원할 릴리스를 지정해 주세요."; return 1; }
  validate_exact_release "$EXPECTED_RELEASE" && validate_exact_release "$RESTORE_RELEASE" || return 1
  local before
  before="$(exact_current_release)" || return 1
  [[ "$before" == "$EXPECTED_RELEASE" ]] \
    || { echo "현재 릴리스가 배포 후 기록과 다릅니다. 다른 배포는 변경하지 않습니다."; return 1; }
  nginx_ready
  warn_if_web_root_not_served "$WEB_ROOT" || return 1
  STATIC_HEALTH_HOST="$(active_health_host_for_web_root "$WEB_ROOT")" || return 1
  if [[ "$RESTORE_RELEASE" != ABSENT && ! -x "$HEALTHCHECK_SCRIPT" ]]; then
    echo "정확 복원 검증에 필요한 상태 검사기가 없습니다."
    return 1
  fi
  nginx -t || return 1
  set_exact_current "$RESTORE_RELEASE" || return 1
  local restored=true
  if [[ "$RELOAD_NGINX" -eq 1 ]]; then
    if ! nginx -t || ! systemctl reload nginx; then restored=false; fi
  fi
  if [[ "$restored" == true && "$RESTORE_RELEASE" != ABSENT ]]; then
    "$HEALTHCHECK_SCRIPT" --port "$HEALTH_PORT" --path "$HEALTH_PATH" --host "$STATIC_HEALTH_HOST" || restored=false
  fi
  if [[ "$restored" != true ]]; then
    echo "정확 복원의 재적용 또는 상태 검사가 실패했습니다. 복원 직전 상태로 되돌립니다."
    if ! set_exact_current "$before"; then
      echo "EXACT_RESTORE_COMPENSATION_FAILED=true"
    elif [[ "$RELOAD_NGINX" -eq 1 ]] && { ! nginx -t || ! systemctl reload nginx; }; then
      echo "EXACT_RESTORE_COMPENSATION_FAILED=true"
    fi
    return 1
  fi
  printf 'RESTORED_RELEASE=%s\n' "$RESTORE_RELEASE"
}

run_rollback(){
  [[ -n "$WEB_ROOT" ]] || { echo "--web-root is required."; exit 1; }
  safe_path "$WEB_ROOT" || { echo "Invalid web root: $WEB_ROOT"; exit 1; }
  nginx_ready
  local releases_dir="$WEB_ROOT/releases"
  local current_link="$WEB_ROOT/current"
  local current_target previous
  current_target="$(readlink -f "$current_link" 2>/dev/null || true)"
  # -F: 경로를 정규식이 아니라 리터럴로 다룬다. 경로의 '.' 이 임의 문자로 해석되면
  #     엉뚱한 릴리스가 "현재와 다른 것"으로 잡혀 잘못된 곳으로 롤백한다.
  previous="$(ls -1dt "$releases_dir"/*/ 2>/dev/null | sed 's:/*$::' | grep -Fvx "$current_target" | head -n 1)"
  [[ -n "$previous" && -d "$previous" ]] || { echo "No previous release to roll back to."; exit 1; }
  ln -sfn "$previous" "$current_link"
  echo "Rolled back: $current_link -> $previous"
  reload_nginx_or_rollback "$current_link" "$current_target"
}

# 상태 조회는 lock 파일도 만들지 않으며 설정 파일 source나 nginx reload를 하지 않는다.
# current가 이 WebRoot의 실제 releases 하위 디렉터리를 가리키는지만 확인한다.
run_status(){
  [[ -n "$WEB_ROOT" ]] || { echo "--web-root is required."; exit 1; }
  safe_path "$WEB_ROOT" || { echo "Invalid web root: $WEB_ROOT"; exit 1; }
  local root current exists=false nginx_state nginx_active=unknown
  root="$(readlink -e -- "$WEB_ROOT" 2>/dev/null || true)"
  current=""
  if [[ -n "$root" && -L "$root/current" ]]; then
    current="$(readlink -e -- "$root/current" 2>/dev/null || true)"
    if [[ -n "$current" && "$current" == "$root/releases/"* && -d "$current" ]] && safe_path "$current"; then
      exists=true
    else
      current=""
    fi
  fi
  nginx_state="$(systemctl is-active nginx 2>/dev/null || true)"
  case "$nginx_state" in
    active) nginx_active=true ;;
    inactive|failed|deactivating) nginx_active=false ;;
  esac
  printf 'ARTIFACT_TYPE=STATIC_BUNDLE\nRELEASE_EXISTS=%s\nCURRENT_RELEASE=%s\nNGINX_ACTIVE=%s\n' \
    "$exists" "$current" "$nginx_active"
}

main(){
  require_root
  parse_args "$@"
  if [[ "$ACTION" == "status" ]]; then
    run_status
    return
  fi
  if [[ "$ACTION" == "capabilities" ]]; then printf 'EXACT_RESTORE_VERSION=1\n'; return; fi
  case "$ACTION" in
    deploy|rollback|restore-exact)
      [[ -n "$WEB_ROOT" ]] || { echo "--web-root is required."; exit 1; }
      safe_path "$WEB_ROOT" || { echo "Invalid web root: $WEB_ROOT"; exit 1; }
      ;;
  esac
  command -v flock >/dev/null 2>&1 || { echo "flock is required for serialized WEB deployments."; exit 1; }
  exec 8>/var/lock/deploy-ops-nginx.lock
  flock -n 8 || { echo "Another WEB nginx operation is in progress."; exit 1; }
  exec 9>"$LOCK_FILE"
  flock -n 9 || { echo "Another WEB static deployment or rollback is in progress."; exit 1; }
  load_defaults
  case "$ACTION" in
    deploy) run_deploy ;;
    rollback) run_rollback ;;
    restore-exact) run_restore_exact ;;
    *) echo "Unsupported action: $ACTION"; usage; exit 1 ;;
  esac
}

main "$@"
