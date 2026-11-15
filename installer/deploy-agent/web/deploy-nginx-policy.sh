#!/bin/bash
# 구조화 템플릿 또는 승인된 관리 사이트 원문을 검증하여 적용한다.
set -Eeuo pipefail

ACTION="${1:-}"
[[ -n "$ACTION" ]] && shift || true
REQUEST_ID="" POLICY_NAME="" POLICY_TYPE="REVERSE_PROXY" SERVER_NAME="" LISTEN_PORT="443"
UPSTREAM_HOST="" UPSTREAM_PORT="" WEBSOCKET="false" TLS="false"
ENDPOINT_HOSTS=() ENDPOINT_PORTS=() UPSTREAM_PURPOSE="" LEGACY_UPSTREAM="false"
SECONDARY_HOST="" SECONDARY_PORT="" MAX_FAILS="1" FAIL_TIMEOUT="10" CONNECT_TIMEOUT="3"
WEB_ROOT=""
API_PROXY="false" API_PREFIX="" API_UPSTREAM_HOST="" API_UPSTREAM_PORT="1"
TLS_PROTOCOLS="TLSv1.2 TLSv1.3" HTTP2="true" REDIRECT_HTTP="true" HSTS="true"
CLIENT_MAX_BODY_MB="50" READ_TIMEOUT="300" SEND_TIMEOUT="300"
PROXY_BUFFERING="true" SECURITY_HEADERS="true" ALLOWED_CIDRS=""
CERTIFICATE_NAME="certificate" CERTIFICATE="" PRIVATE_KEY="" BACKUP_DIR=""
EXPECTED_CURRENT_REQUEST_ID="" EXPECTED_SNAPSHOT="" EXPECTED_CURRENT_SNAPSHOT=""
BACKUP_ROOT="/var/backups/deploy-system/nginx"
RESTORE_ON_ERROR=""
TEMP_CONFIG="" STAGED_CERT_DIR="" RAW_BODY="" CONFIG_FILE="" CONFIG_SHA256=""
LOCK_FILE="/var/lock/deploy-ops-nginx-policy.lock"
ROLLOUT_GENERATION="" ROLLOUT_CONFIG_SHA="" ROLLOUT_HOST="" ROLLOUT_PORT=""
ROLLOUT_ALLOW_EMPTY="false" ROLLOUT_ALLOW_EMPTY_SEEN="false"

fail(){ echo "$*" >&2; exit 1; }
bool(){ [[ "$1" == "true" || "$1" == "false" ]]; }
safe_name(){ [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; }
canonical_host(){
  local host="${1,,}" part; local -a left=() right=() groups=()
  if [[ "$host" == *:* ]]; then
    [[ "$host" =~ ^[0-9a-f:]+$ && "$host" != *:::* ]] || return 1
    [[ "$host" != :* || "$host" == ::* ]] || return 1
    [[ "$host" != *: || "$host" == *:: ]] || return 1
    if [[ "$host" == *::* ]]; then
      local prefix="${host%%::*}" suffix="${host#*::}"
      [[ "$suffix" != *::* ]] || return 1
      [[ -z "$prefix" ]] || IFS=: read -ra left <<< "$prefix"
      [[ -z "$suffix" ]] || IFS=: read -ra right <<< "$suffix"
      (( ${#left[@]} + ${#right[@]} < 8 )) || return 1
      groups=("${left[@]}")
      while (( ${#groups[@]} + ${#right[@]} < 8 )); do groups+=(0); done
      groups+=("${right[@]}")
    else
      [[ "$host" != :* && "$host" != *: ]] || return 1
      IFS=: read -ra groups <<< "$host"
      [[ "${#groups[@]}" == 8 ]] || return 1
    fi
    local result=""
    for part in "${groups[@]}";do
      [[ "$part" =~ ^[0-9a-f]{1,4}$ ]] || return 1
      printf -v part '%x' "$((16#$part))";result+="${result:+:}$part"
    done
    printf '%s' "$result"
  elif [[ "$host" =~ ^[0-9.]+$ ]]; then
    [[ "$host" != .* && "$host" != *. && "$host" != *..* ]] || return 1
    IFS=. read -ra groups <<< "$host";[[ "${#groups[@]}" == 4 ]] || return 1
    local result=""
    for part in "${groups[@]}";do [[ "$part" =~ ^[0-9]{1,3}$ ]] && (( 10#$part <= 255 )) || return 1;result+="${result:+.}$((10#$part))";done
    printf '%s' "$result"
  else
    [[ "$host" =~ ^[a-z0-9][a-z0-9._-]{0,253}[a-z0-9]$ || "$host" =~ ^[a-z0-9]$ ]] || return 1
    printf '%s' "$host"
  fi
}
safe_host(){ canonical_host "$1" >/dev/null; }

safe_server_name(){ [[ "$1" =~ ^(_|\*\.)?[A-Za-z0-9][A-Za-z0-9.-]{0,253}[A-Za-z0-9]$ || "$1" =~ ^[A-Za-z0-9]$ ]]; }
safe_port(){ [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }
safe_api_prefix(){ [[ ${#1} -le 80 && "$1" =~ ^/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*/$ ]]; }
safe_number(){ [[ "$1" =~ ^[0-9]{1,5}$ ]]; }
safe_stage_file(){ [[ "$1" =~ ^/var/tmp/deploy-ops/nginx-change-[0-9]+/(certificate|private-key)\.pem$ ]]; }
safe_backup(){ [[ "$1" =~ ^/var/backups/deploy-system/nginx/[A-Za-z0-9._-]+$ ]]; }
safe_web_root(){
  [[ "$1" =~ ^/(var/www|srv/web)/[A-Za-z0-9._/+:-]+$ ]] || return 1
  [[ "$1" =~ (^|/)\.\.(/|$) || "$1" == */current ]] && return 1
  local resolved
  resolved="$(readlink -m -- "$1")" || return 1
  [[ "$resolved" =~ ^/(var/www|srv/web)/[A-Za-z0-9._/+:-]+$ ]] || return 1
  return 0
}

parse(){
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --request-id) REQUEST_ID="$2"; shift 2;;
      --policy-name) POLICY_NAME="$2"; shift 2;;
      --rollout-generation) ROLLOUT_GENERATION="$2"; shift 2;;
      --rollout-config-sha) ROLLOUT_CONFIG_SHA="$2"; shift 2;;
      --rollout-host) ROLLOUT_HOST="$2"; shift 2;;
      --rollout-port) ROLLOUT_PORT="$2"; shift 2;;
      --rollout-allow-empty)
        [[ "$#" -ge 2 && "$ROLLOUT_ALLOW_EMPTY_SEEN" == false ]] && bool "$2" \
          || fail "--rollout-allow-empty는 true 또는 false를 한 번만 지정하세요."
        ROLLOUT_ALLOW_EMPTY="$2"; ROLLOUT_ALLOW_EMPTY_SEEN=true; shift 2;;
      --policy-type) POLICY_TYPE="$2"; shift 2;;
      --server-name) SERVER_NAME="$2"; shift 2;;
      --listen) LISTEN_PORT="$2"; shift 2;;
      --upstream-purpose) [[ "$#" -ge 2 ]] || fail "upstream purpose가 필요합니다."; UPSTREAM_PURPOSE="$2"; shift 2;;
      --upstream-endpoint) [[ "$#" -ge 3 && "${#ENDPOINT_HOSTS[@]}" -lt 32 ]] || fail "WAS 목록은 1~32개 주소와 포트가 필요합니다."; ENDPOINT_HOSTS+=("$2"); ENDPOINT_PORTS+=("$3"); shift 3;;
      --upstream-host) LEGACY_UPSTREAM="true"; UPSTREAM_HOST="$2"; shift 2;;
      --upstream-port) LEGACY_UPSTREAM="true"; UPSTREAM_PORT="$2"; shift 2;;
      --secondary-upstream-host) LEGACY_UPSTREAM="true"; SECONDARY_HOST="$2"; shift 2;;
      --secondary-upstream-port) LEGACY_UPSTREAM="true"; SECONDARY_PORT="$2"; shift 2;;
      --upstream-max-fails) MAX_FAILS="$2"; shift 2;;
      --upstream-fail-timeout) FAIL_TIMEOUT="$2"; shift 2;;
      --proxy-connect-timeout) CONNECT_TIMEOUT="$2"; shift 2;;
      --web-root) WEB_ROOT="$2"; shift 2;;
      --api-proxy) API_PROXY="$2"; shift 2;;
      --api-prefix) API_PREFIX="$2"; shift 2;;
      --api-upstream-host) LEGACY_UPSTREAM="true"; API_UPSTREAM_HOST="$2"; shift 2;;
      --api-upstream-port) LEGACY_UPSTREAM="true"; API_UPSTREAM_PORT="$2"; shift 2;;
      --websocket) WEBSOCKET="$2"; shift 2;;
      --tls) TLS="$2"; shift 2;;
      --tls-protocols) TLS_PROTOCOLS="$2"; shift 2;;
      --http2) HTTP2="$2"; shift 2;;
      --redirect-http) REDIRECT_HTTP="$2"; shift 2;;
      --hsts) HSTS="$2"; shift 2;;
      --client-max-body-mb) CLIENT_MAX_BODY_MB="$2"; shift 2;;
      --proxy-read-timeout) READ_TIMEOUT="$2"; shift 2;;
      --proxy-send-timeout) SEND_TIMEOUT="$2"; shift 2;;
      --proxy-buffering) PROXY_BUFFERING="$2"; shift 2;;
      --security-headers) SECURITY_HEADERS="$2"; shift 2;;
      --allowed-cidrs) ALLOWED_CIDRS="$2"; shift 2;;
      --certificate-name) CERTIFICATE_NAME="$2"; shift 2;;
      --certificate) CERTIFICATE="$2"; shift 2;;
      --private-key) PRIVATE_KEY="$2"; shift 2;;
      --config-file) CONFIG_FILE="$2"; shift 2;;
      --config-sha256) CONFIG_SHA256="$2"; shift 2;;
      --backup-dir) BACKUP_DIR="$2"; shift 2;;
      --expected-snapshot) EXPECTED_SNAPSHOT="$2"; shift 2;;
      --expected-current-snapshot) EXPECTED_CURRENT_SNAPSHOT="$2"; shift 2;;
      --expected-current-request-id) EXPECTED_CURRENT_REQUEST_ID="$2"; shift 2;;
      *) fail "허용되지 않은 옵션입니다: $1";;
    esac
  done
}

validate_apply(){
  if [[ -n "$UPSTREAM_PURPOSE" || "${#ENDPOINT_HOSTS[@]}" -gt 0 ]]; then
    [[ "$LEGACY_UPSTREAM" == "false" && "${#ENDPOINT_HOSTS[@]}" -gt 0 && "${#ENDPOINT_HOSTS[@]}" -le 32 ]] || fail "이전 upstream 옵션과 WAS 목록을 함께 사용할 수 없습니다."
    [[ ( "$POLICY_TYPE" == "REVERSE_PROXY" && "$UPSTREAM_PURPOSE" == "PROXY" ) || ( "$POLICY_TYPE" != "REVERSE_PROXY" && "$API_PROXY" == "true" && "$UPSTREAM_PURPOSE" == "API" ) ]] || fail "정책과 WAS 목록 용도가 일치하지 않습니다."
    local i key; local -A seen=()
    for ((i=0;i<${#ENDPOINT_HOSTS[@]};i++)); do
      safe_host "${ENDPOINT_HOSTS[i]}" && safe_port "${ENDPOINT_PORTS[i]}" || fail "WAS 목록의 주소 또는 포트가 올바르지 않습니다."
      ENDPOINT_HOSTS[i]="$(canonical_host "${ENDPOINT_HOSTS[i]}")"
      key="${ENDPOINT_HOSTS[i]}:$((10#${ENDPOINT_PORTS[i]}))"
      [[ -z "${seen[$key]:-}" ]] || fail "WAS 목록에 중복 주소와 포트가 있습니다."
      seen[$key]=1
    done
    if [[ "$UPSTREAM_PURPOSE" == "PROXY" ]]; then UPSTREAM_HOST="${ENDPOINT_HOSTS[0]}"; UPSTREAM_PORT="${ENDPOINT_PORTS[0]}";
    else API_UPSTREAM_HOST="${ENDPOINT_HOSTS[0]}"; API_UPSTREAM_PORT="${ENDPOINT_PORTS[0]}"; fi
  fi
  [[ "$REQUEST_ID" =~ ^[0-9]+$ ]] || fail "request id가 올바르지 않습니다."
  if [[ -n "$CONFIG_FILE" || -n "$CONFIG_SHA256" ]]; then
    [[ "$CONFIG_FILE" == "/var/tmp/deploy-ops/nginx-change-${REQUEST_ID}/policy.conf" \
      && "$CONFIG_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "설정문 staging 경로 또는 해시가 올바르지 않습니다."
  fi
  safe_name "$POLICY_NAME" || fail "policy name이 올바르지 않습니다."
  safe_name "$CERTIFICATE_NAME" || fail "certificate name이 올바르지 않습니다."
  safe_server_name "$SERVER_NAME" || fail "server name이 올바르지 않습니다."
  safe_port "$LISTEN_PORT" || fail "listen port가 올바르지 않습니다."
  [[ "$POLICY_TYPE" == "REVERSE_PROXY" || "$POLICY_TYPE" == "STATIC_SPA" || "$POLICY_TYPE" == "STATIC_FILES" ]] || fail "policy type이 올바르지 않습니다."
  if [[ "$POLICY_TYPE" == "REVERSE_PROXY" ]]; then
    safe_host "$UPSTREAM_HOST" || fail "upstream host가 올바르지 않습니다."
    safe_port "$UPSTREAM_PORT" || fail "upstream port가 올바르지 않습니다."
    [[ -z "$WEB_ROOT" ]] || fail "리버스 프록시에는 web root를 지정할 수 없습니다."
  else
    safe_web_root "$WEB_ROOT" || fail "web root는 /var/www 또는 /srv/web 아래의 배포 루트여야 합니다."
  fi
  safe_number "$MAX_FAILS" && (( 10#$MAX_FAILS >= 1 && 10#$MAX_FAILS <= 10 )) || fail "실패 횟수가 올바르지 않습니다."
  safe_number "$FAIL_TIMEOUT" && (( 10#$FAIL_TIMEOUT >= 1 && 10#$FAIL_TIMEOUT <= 300 )) || fail "실패 제외시간이 올바르지 않습니다."
  safe_number "$CONNECT_TIMEOUT" && (( 10#$CONNECT_TIMEOUT >= 1 && 10#$CONNECT_TIMEOUT <= 30 )) || fail "연결 제한시간이 올바르지 않습니다."
  bool "$API_PROXY" || fail "API 프록시 옵션이 올바르지 않습니다."
  if [[ "$API_PROXY" == "true" ]]; then
    [[ "$POLICY_TYPE" != "REVERSE_PROXY" ]] || fail "API 프록시는 정적 정책에서만 사용할 수 있습니다."
    safe_api_prefix "$API_PREFIX" || fail "API 경로 형식이 올바르지 않습니다."
    safe_host "$API_UPSTREAM_HOST" || fail "API upstream host가 올바르지 않습니다."
    safe_port "$API_UPSTREAM_PORT" || fail "API upstream port가 올바르지 않습니다."
  else
    [[ -z "$API_PREFIX" && -z "$API_UPSTREAM_HOST" && "$API_UPSTREAM_PORT" == "1" ]] || fail "사용하지 않는 API 프록시 필드는 비워야 합니다."
  fi
  if [[ -n "$SECONDARY_HOST" || -n "$SECONDARY_PORT" ]]; then
    [[ "$POLICY_TYPE" == "REVERSE_PROXY" || "$API_PROXY" == "true" ]] || fail "두 번째 upstream은 프록시 또는 정적 API 경로에서만 사용할 수 있습니다."
    safe_host "$SECONDARY_HOST" && safe_port "$SECONDARY_PORT" || fail "두 번째 upstream이 올바르지 않습니다."
    local primary_host="$UPSTREAM_HOST" primary_port="$UPSTREAM_PORT"
    if [[ "$POLICY_TYPE" != "REVERSE_PROXY" ]]; then primary_host="$API_UPSTREAM_HOST"; primary_port="$API_UPSTREAM_PORT"; fi
    [[ "${primary_host,,}:$((10#$primary_port))" != "${SECONDARY_HOST,,}:$((10#$SECONDARY_PORT))" ]] || fail "두 upstream은 서로 다른 주소여야 합니다."
  fi
  safe_number "$CLIENT_MAX_BODY_MB" && (( 10#$CLIENT_MAX_BODY_MB <= 10240 )) || fail "body size가 올바르지 않습니다."
  safe_number "$READ_TIMEOUT" && (( 10#$READ_TIMEOUT >= 1 && 10#$READ_TIMEOUT <= 3600 )) || fail "read timeout이 올바르지 않습니다."
  safe_number "$SEND_TIMEOUT" && (( 10#$SEND_TIMEOUT >= 1 && 10#$SEND_TIMEOUT <= 3600 )) || fail "send timeout이 올바르지 않습니다."
  for value in "$WEBSOCKET" "$TLS" "$HTTP2" "$REDIRECT_HTTP" "$HSTS" "$PROXY_BUFFERING" "$SECURITY_HEADERS"; do bool "$value" || fail "boolean 옵션이 올바르지 않습니다."; done
  if [[ "$TLS" == "true" ]]; then
    [[ "$TLS_PROTOCOLS" == "TLSv1.2" || "$TLS_PROTOCOLS" == "TLSv1.3" || "$TLS_PROTOCOLS" == "TLSv1.2 TLSv1.3" ]] || fail "허용되지 않은 TLS 버전입니다."
  fi
  if [[ -n "$CERTIFICATE" || -n "$PRIVATE_KEY" ]]; then
    [[ -n "$CERTIFICATE" && -n "$PRIVATE_KEY" ]] || fail "인증서와 개인키를 함께 제공해야 합니다."
    safe_stage_file "$CERTIFICATE" && safe_stage_file "$PRIVATE_KEY" || fail "인증서 staging 경로가 올바르지 않습니다."
    local expected_staging_dir="/var/tmp/deploy-ops/nginx-change-${REQUEST_ID}"
    [[ "$(dirname -- "$CERTIFICATE")" == "$expected_staging_dir" \
      && "$(dirname -- "$PRIVATE_KEY")" == "$expected_staging_dir" ]] \
      || fail "인증서와 개인키는 현재 요청의 staging 디렉터리에 있어야 합니다."
    [[ -f "$CERTIFICATE" && -f "$PRIVATE_KEY" ]] || fail "인증서 staging 파일이 없습니다."
  fi
  if [[ -n "$ALLOWED_CIDRS" ]]; then
    local cidr oldifs="$IFS"; IFS=','
    for cidr in $ALLOWED_CIDRS; do
      [[ "$cidr" =~ ^[0-9A-Fa-f:.]+(/[0-9]{1,3})?$ ]] || fail "허용 IP/CIDR 형식이 올바르지 않습니다: $cidr"
    done
    IFS="$oldifs"
  fi
}

validate_certificate(){
  local cert="$1" key="$2" cert_pub key_pub
  openssl x509 -in "$cert" -noout -subject -issuer -dates >/dev/null || fail "X.509 인증서를 읽을 수 없습니다."
  openssl x509 -in "$cert" -checkend 0 -noout >/dev/null || fail "만료된 인증서입니다."
  openssl pkey -in "$key" -passin pass: -noout >/dev/null || fail "암호화되지 않은 개인키를 읽을 수 없습니다."
  cert_pub="$(openssl x509 -in "$cert" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum | awk '{print $1}')"
  key_pub="$(openssl pkey -in "$key" -passin pass: -pubout -outform DER | sha256sum | awk '{print $1}')"
  [[ "$cert_pub" == "$key_pub" ]] || fail "인증서와 개인키가 일치하지 않습니다."
}

# A bounded public descriptor; key bytes never leave this root-only process.
snapshot_tree(){
  local config="$1" tls="$2" entry relative digest mode size line manifest="CONFIG_ABSENT" count=0
  CONFIG_HASH=absent CONFIG_HEADER=absent CONFIG_PRESENT=false
  [[ ! -L "$config" && ! -L "$tls" ]] || fail "snapshot symlink refused"
  if [[ -e "$config" ]]; then
    [[ -f "$config" && "$(stat -c '%u:%g:%a' "$config")" == 0:0:644 ]] || fail "config metadata drift"
    size="$(stat -c %s "$config")"; (( size <= 65536 )) || fail "config exceeds descriptor bound"
    line="$(head -n 1 "$config")"
    [[ "$line" =~ ^#\ Generated\ by\ Deploy\ System\.\ Request\ ([1-9][0-9]{0,17})\.$ ]] || fail "unknown legacy config cannot be verified"
    CONFIG_HEADER="${BASH_REMATCH[1]}" CONFIG_PRESENT=true
    CONFIG_HASH="$(sha256sum "$config" | cut -d' ' -f1)"
    manifest="CONFIG:$size:$CONFIG_HASH"
  fi
  if [[ -e "$tls" ]]; then
    mode="$(stat -c '%u:%g:%a' "$tls")"
    # Existing install -d -m700 policy/current creates the public parent as755.
    # Both supported parent modes are bound into the digest; current/key stay700/600.
    [[ -d "$tls" && ( "$mode" == 0:0:700 || "$mode" == 0:0:755 ) ]] || fail "TLS directory metadata drift"
    local -a entries=(); mapfile -d '' entries < <(find "$tls" -mindepth 1 -print0 | LC_ALL=C sort -z)
    (( ${#entries[@]} <= 8 )) || fail "TLS descriptor count exceeded"
    manifest+=$'\n'"TLS_PRESENT:$mode"
    for entry in "${entries[@]}"; do
      relative="${entry#"$tls"/}"
      [[ "$relative" == current || "$relative" == current/fullchain.pem || "$relative" == current/privkey.pem ]] || fail "unexpected TLS entry"
      [[ ! -L "$entry" ]] || fail "TLS symlink refused"
      mode="$(stat -c '%u:%g:%a' "$entry")"
      if [[ "$relative" == current ]]; then
        [[ -d "$entry" && "$mode" == 0:0:700 ]] || fail "TLS current metadata drift"
        manifest+=$'\n'"D:$relative:$mode"
      else
        [[ -f "$entry" ]] || fail "TLS file type refused"
        if [[ "$relative" == current/privkey.pem ]]; then [[ "$mode" == 0:0:600 ]] || fail "private key metadata drift";
        else [[ "$mode" == 0:0:644 ]] || fail "certificate metadata drift"; fi
        size="$(stat -c %s "$entry")"; (( size > 0 && size <= 4194304 )) || fail "TLS file size refused"
        digest="$(sha256sum "$entry" | cut -d' ' -f1)"
        manifest+=$'\n'"F:$relative:$mode:$size:$digest"
      fi
    done
    [[ -f "$tls/current/fullchain.pem" && -f "$tls/current/privkey.pem" ]] || fail "incomplete TLS snapshot"
  else manifest+=$'\nTLS_ABSENT'; fi
  SNAPSHOT_HASH="$(printf '%s\n' "$manifest" | sha256sum | cut -d' ' -f1)"
}

validate_backup(){
  safe_backup "$BACKUP_DIR" || fail "backup path refused"
  [[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" && "$(readlink -f "$BACKUP_DIR")" == "$BACKUP_DIR" ]] || fail "backup directory refused"
  [[ "$(stat -c '%u:%g:%a' "$BACKUP_DIR")" == 0:0:700 ]] || fail "backup metadata drift"
  safe_name "$POLICY_NAME" || fail "policy identity required"
  [[ -f "$BACKUP_DIR/policy-name" && ! -L "$BACKUP_DIR/policy-name" && "$(cat "$BACKUP_DIR/policy-name")" == "$POLICY_NAME" ]] || fail "backup policy identity drift"
  local entry name
  [[ "$(stat -c '%u:%g' "$BACKUP_DIR/policy-name")" == 0:0 && "$(stat -c %s "$BACKUP_DIR/policy-name")" -le 82 ]] || fail "policy metadata drift"
  local -a top_entries=(); mapfile -d '' top_entries < <(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -print0)
  for entry in "${top_entries[@]}"; do
    name="${entry##*/}"
    [[ "$name" == policy-name || "$name" == config.conf || "$name" == config.absent || "$name" == tls || "$name" == tls.absent ]] || fail "unexpected backup entry"
    [[ ! -L "$entry" ]] || fail "backup symlink refused"
  done
  if [[ -f "$BACKUP_DIR/config.conf" ]]; then [[ ! -e "$BACKUP_DIR/config.absent" ]] || fail "ambiguous config state";
  else [[ -f "$BACKUP_DIR/config.absent" && ! -s "$BACKUP_DIR/config.absent" ]] || fail "missing config absence marker"; fi
  if [[ -d "$BACKUP_DIR/tls" ]]; then [[ ! -e "$BACKUP_DIR/tls.absent" ]] || fail "ambiguous TLS state";
  else [[ -f "$BACKUP_DIR/tls.absent" && ! -s "$BACKUP_DIR/tls.absent" ]] || fail "missing TLS absence marker"; fi
  snapshot_tree "$BACKUP_DIR/config.conf" "$BACKUP_DIR/tls"
}

emit_snapshot(){
  local prefix="$1"
  printf '%sSNAPSHOT_SHA=%s\n%sCONFIG_SHA=%s\n%sREQUEST_ID=%s\n%sCONFIG_PRESENT=%s\n' "$prefix" "$SNAPSHOT_HASH" "$prefix" "$CONFIG_HASH" "$prefix" "$CONFIG_HEADER" "$prefix" "$CONFIG_PRESENT"
}

verify_current(){
  safe_name "$POLICY_NAME" || fail "policy identity required"
  [[ "$EXPECTED_CURRENT_REQUEST_ID" == absent || "$EXPECTED_CURRENT_REQUEST_ID" =~ ^[1-9][0-9]{0,17}$ ]] || fail "current native generation required"
  snapshot_tree "/etc/nginx/conf.d/deploy-${POLICY_NAME}.conf" "/etc/nginx/tls/${POLICY_NAME}"
  [[ "$CONFIG_HEADER" == "$EXPECTED_CURRENT_REQUEST_ID" ]] || fail "current generation differs from approved source"
  if [[ -n "$EXPECTED_CURRENT_SNAPSHOT" ]]; then
    [[ "$EXPECTED_CURRENT_SNAPSHOT" =~ ^[0-9a-f]{64}$ && "$SNAPSHOT_HASH" == "$EXPECTED_CURRENT_SNAPSHOT" ]] || fail "current snapshot drift"
  fi
}

describe_backup(){
  verify_current
  validate_backup
  emit_snapshot TARGET_
}

backup_current(){
  local config="$1" tls_dir="$2" stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  BACKUP_DIR="${BACKUP_ROOT}/${stamp}-${POLICY_NAME}-${CERTIFICATE_NAME}-${REQUEST_ID}"
  [[ ! -e "$BACKUP_DIR" ]] || fail "backup already exists; partial evidence preserved"
  install -d -m 700 "$BACKUP_DIR"
  printf '%s\n' "$POLICY_NAME" > "$BACKUP_DIR/policy-name"
  if [[ -f "$config" ]]; then cp -a "$config" "$BACKUP_DIR/config.conf"; else : > "$BACKUP_DIR/config.absent"; fi
  if [[ -d "$tls_dir" ]]; then cp -a "$tls_dir" "$BACKUP_DIR/tls"; else : > "$BACKUP_DIR/tls.absent"; fi
}

restore_backup(){
  local backup="$1" policy config tls_dir
  safe_backup "$backup" || fail "복구 경로가 허용된 백업 루트 밖입니다."
  [[ -d "$backup" && -f "$backup/policy-name" ]] || fail "복구 메타데이터가 없습니다."
  policy="$(tr -d '\r\n' < "$backup/policy-name")"
  safe_name "$policy" || fail "백업 policy name이 올바르지 않습니다."
  config="/etc/nginx/conf.d/deploy-${policy}.conf"
  tls_dir="/etc/nginx/tls/${policy}"
  if [[ -f "$backup/config.conf" ]]; then install -m 644 "$backup/config.conf" "$config"; else rm -f "$config"; fi
  rm -rf "$tls_dir"
  # ★ if/fi 로 둔다. `[[ ... ]] && cmd` 를 함수의 마지막 문장으로 쓰면 조건이 거짓일 때
  #   그 1 이 함수의 반환값이 되고, set -e 아래의 호출부가 <메시지 없이> 죽는다.
  #   백업에 tls 가 없는 경우 = 최초 적용(tls.absent) 이라, 하필 신규 설치에서만 터졌다.
  if [[ -d "$backup/tls" ]]; then cp -a "$backup/tls" "$tls_dir"; fi
}

restore_on_error(){
  local rc=$?
  trap - ERR
  if [[ -n "$RESTORE_ON_ERROR" && -d "$RESTORE_ON_ERROR" ]]; then
    restore_backup "$RESTORE_ON_ERROR" || true
    nginx -t >/dev/null 2>&1 || true
    systemctl reload nginx >/dev/null 2>&1 || true
  fi
  exit "$rc"
}

# EXIT trap handlers run after apply_policy returns, so they must use state that
# survives the function scope. Keep each cleanup independent so one failed rm
# cannot prevent the staged certificate directory from being removed.
cleanup_apply_temps(){
  local exit_status=$?
  trap - EXIT
  if [[ -n "$TEMP_CONFIG" ]]; then rm -f -- "$TEMP_CONFIG" || true; fi
  if [[ -n "$RAW_BODY" ]]; then rm -f -- "$RAW_BODY" || true; fi
  if [[ -n "$STAGED_CERT_DIR" ]]; then rm -rf -- "$STAGED_CERT_DIR" || true; fi
  return "$exit_status"
}

nginx_python_helper(){
  local helper="$1" python=/usr/libexec/platform-python
  shift
  [[ -x "$python" ]] || python=/usr/bin/python3
  [[ -x "$python" && -f "$helper" && ! -L "$helper" \
    && "$(stat -c '%u:%g:%a' "$helper")" == 0:0:640 ]] || fail "설정 편집기를 지원하는 WEB 에이전트를 먼저 설치해 주세요."
  "$python" -I -B "$helper" "$@"
}

raw_validator(){
  nginx_python_helper /usr/local/deploy/bin/nginx-config-validator.py "$@"
}

freeze_raw_config(){
  RAW_BODY="$(mktemp /etc/nginx/conf.d/.deploy-approved-body.XXXXXXXX)"
  raw_validator freeze "$CONFIG_FILE" "$RAW_BODY" "$CONFIG_SHA256"
  local args=("$RAW_BODY" --policy "$POLICY_NAME" --server-name "$SERVER_NAME" --listen "$LISTEN_PORT"
    --policy-type "$POLICY_TYPE" --web-root "$WEB_ROOT" --tls "$TLS" --redirect-http "$REDIRECT_HTTP")
  local i
  if [[ "${#ENDPOINT_HOSTS[@]}" -gt 0 ]]; then
    for ((i=0;i<${#ENDPOINT_HOSTS[@]};i++)); do args+=(--upstream "${ENDPOINT_HOSTS[i]}" "${ENDPOINT_PORTS[i]}"); done
  elif [[ "$POLICY_TYPE" == REVERSE_PROXY || "$API_PROXY" == true ]]; then
    if [[ "$POLICY_TYPE" == REVERSE_PROXY ]]; then args+=(--upstream "$UPSTREAM_HOST" "$UPSTREAM_PORT");
    else args+=(--upstream "$API_UPSTREAM_HOST" "$API_UPSTREAM_PORT"); fi
    [[ -z "$SECONDARY_HOST" ]] || args+=(--upstream "$SECONDARY_HOST" "$SECONDARY_PORT")
  fi
  raw_validator "${args[@]}"
}

render_proxy_location(){
  local host="$1" port="$2"
  [[ "$host" == *:* ]] && host="[$host]"
      if [[ "${#ENDPOINT_HOSTS[@]}" -gt 0 || -n "$SECONDARY_HOST" ]]; then
        printf '        proxy_pass http://deploy_policy_%s_%s;\n' "${POLICY_NAME//[^A-Za-z0-9]/_}" "$REQUEST_ID"
        printf '        proxy_connect_timeout %ss;\n' "$CONNECT_TIMEOUT"
        echo '        proxy_next_upstream error timeout http_502 http_503 http_504;'
        local tries=2; [[ "${#ENDPOINT_HOSTS[@]}" -gt 0 ]] && tries="${#ENDPOINT_HOSTS[@]}"
        printf '        proxy_next_upstream_tries %s;\n' "$tries"
        printf '        proxy_next_upstream_timeout %ss;\n' "$(( 10#$CONNECT_TIMEOUT * tries ))"
      else
        printf '        proxy_pass http://%s:%s;\n' "$host" "$port"
      fi
      echo '        proxy_http_version 1.1;'
      echo '        proxy_set_header Host $deploy_request_host;'
      echo '        proxy_set_header X-Real-IP $remote_addr;'
    # ★★ $remote_addr 로 '덮어쓴다'. append($proxy_add_x_forwarded_for) 금지.
    #   WEB 노드는 공개 접점(신뢰 경계)이라 클라이언트가 보낸 XFF 를 그대로 믿으면 안 된다.
    #   append 하면 최좌측이 클라이언트가 정한 값이 되는데, 오피스의 AccessZone 은
    #   "XFF 최좌측이 사설 IP면 내부망" 으로 판정한다 —
    #   즉 공격자가 `X-Forwarded-For: 10.0.0.1` 하나로 <외부망 OTP 강제를 우회>한다.
    #   설치기 components/nginx.sh 도 같은 이유로 덮어쓰기를 쓴다(주석 참조).
      echo '        proxy_set_header X-Forwarded-For $remote_addr;'
      echo '        proxy_set_header X-Forwarded-Proto $scheme;'
      echo '        proxy_set_header X-Forwarded-Host $deploy_request_host;'
    # 클라이언트가 보낸 대체 전달 헤더가 위의 실제 접점 정보를 덮지 못하게 한다.
      echo '        proxy_set_header Forwarded "";'
      echo '        proxy_set_header X-Forwarded-Port "";'
      echo '        proxy_set_header X-Forwarded-Prefix "";'
      echo '        proxy_set_header X-Forwarded-Ssl "";'
      [[ "$WEBSOCKET" == "true" ]] && {
        echo '        proxy_set_header Upgrade $http_upgrade;'
        echo '        proxy_set_header Connection "upgrade";'
      }
      printf '        proxy_read_timeout %ss;\n        proxy_send_timeout %ss;\n' "$READ_TIMEOUT" "$SEND_TIMEOUT"
      printf '        proxy_buffering %s;\n' "$([[ "$PROXY_BUFFERING" == "true" ]] && echo on || echo off)"
}

render_config(){
  local target="$1" cert_path="$2" key_path="$3"
  {
    echo "# Generated by Deploy System. Request ${REQUEST_ID}."
    if [[ "${#ENDPOINT_HOSTS[@]}" -gt 0 || -n "$SECONDARY_HOST" ]]; then
      printf 'upstream deploy_policy_%s_%s {\n' "${POLICY_NAME//[^A-Za-z0-9]/_}" "$REQUEST_ID"
      local hosts=() ports=() host i
      if [[ "${#ENDPOINT_HOSTS[@]}" -gt 0 ]]; then hosts=("${ENDPOINT_HOSTS[@]}"); ports=("${ENDPOINT_PORTS[@]}");
      else
        if [[ "$POLICY_TYPE" == "REVERSE_PROXY" ]]; then hosts=("$UPSTREAM_HOST" "$SECONDARY_HOST"); ports=("$UPSTREAM_PORT" "$SECONDARY_PORT");
        else hosts=("$API_UPSTREAM_HOST" "$SECONDARY_HOST"); ports=("$API_UPSTREAM_PORT" "$SECONDARY_PORT"); fi
      fi
      for ((i=0;i<${#hosts[@]};i++)); do
        host="${hosts[i]}"; [[ "$host" == *:* ]] && host="[$host]"
        printf '    server %s:%s max_fails=%s fail_timeout=%ss;\n' "$host" "${ports[i]}" "$MAX_FAILS" "$FAIL_TIMEOUT"
      done
      echo '}'
    fi
    if [[ "$TLS" == "true" && "$REDIRECT_HTTP" == "true" && "$LISTEN_PORT" != "80" ]]; then
      printf 'server { listen 80; server_name %s; return 301 https://$host$request_uri; }\n' "$SERVER_NAME"
    fi
    echo "server {"
    if [[ "$TLS" == "true" ]]; then
      printf '    listen %s ssl%s;\n' "$LISTEN_PORT" "$([[ "$HTTP2" == "true" ]] && printf ' http2')"
      printf '    ssl_certificate %s;\n    ssl_certificate_key %s;\n' "$cert_path" "$key_path"
      printf '    ssl_protocols %s;\n' "$TLS_PROTOCOLS"
    else
      printf '    listen %s;\n' "$LISTEN_PORT"
    fi
    printf '    server_name %s;\n' "$SERVER_NAME"
    # $host는 외부 요청 포트를 제거하므로 로그인 절대 리다이렉트가 잘못될 수 있다.
    # Nginx가 받은 Host를 보존하고 Host 없는 HTTP/1.0 요청만 서버 이름으로 대체한다.
    echo '    set $deploy_request_host $http_host;'
    echo '    if ($deploy_request_host = "") { set $deploy_request_host $host; }'
    printf '    client_max_body_size %sm;\n' "$CLIENT_MAX_BODY_MB"
    [[ "$TLS" == "true" && "$HSTS" == "true" ]] && echo '    add_header Strict-Transport-Security "max-age=31536000" always;'
    [[ "$SECURITY_HEADERS" == "true" ]] && {
      echo '    add_header X-Content-Type-Options "nosniff" always;'
      echo '    add_header X-Frame-Options "SAMEORIGIN" always;'
      echo '    add_header Referrer-Policy "same-origin" always;'
    }
    echo '    location / {'
    if [[ "$POLICY_TYPE" == "REVERSE_PROXY" ]]; then
      render_proxy_location "$UPSTREAM_HOST" "$UPSTREAM_PORT"
    else
      printf '        root %s/current;\n' "$WEB_ROOT"
      if [[ "$POLICY_TYPE" == "STATIC_SPA" ]]; then
        echo '        try_files $uri $uri/ /index.html;'
      else
        echo '        try_files $uri $uri/ =404;'
      fi
    fi
    if [[ -n "$ALLOWED_CIDRS" ]]; then
      local cidr oldifs="$IFS"; IFS=','
      for cidr in $ALLOWED_CIDRS; do printf '        allow %s;\n' "$cidr"; done
      IFS="$oldifs"; echo '        deny all;'
    fi
    echo '    }'
    if [[ "$API_PROXY" == "true" ]]; then
      printf '    location ^~ %s {\n' "$API_PREFIX"
      render_proxy_location "$API_UPSTREAM_HOST" "$API_UPSTREAM_PORT"
    if [[ -n "$ALLOWED_CIDRS" ]]; then
      local cidr oldifs="$IFS"; IFS=','
      for cidr in $ALLOWED_CIDRS; do printf '        allow %s;\n' "$cidr"; done
      IFS="$oldifs"; echo '        deny all;'
    fi
      echo '    }'
    fi
    echo '}'
  } > "$target"
}

# ★ 이 파일이 다른 생성기(deploy-web-proxy.sh · deploy-web-app.sh)의 것이면 손대지 않는다.
#   파일명 규칙이 겹치기 때문에, 승인받은 정책이 승인 없는 배포 설정을 덮거나 그 반대가
#   될 수 있다. 덮으면 백업·롤백 체인이 남의 설정을 자기 것으로 착각하게 된다.
assert_owns_config(){
  local target="$1"
  [[ -f "$target" ]] || return 0
  if head -n 1 "$target" | grep -q '^# Generated by Deploy System\. Request '; then
    return 0
  fi
  fail "다른 생성기가 만든 설정이라 덮어쓰지 않습니다: $target ($(head -n 1 "$target"))"
}

# ★ 같은 (listen, server_name) 이 이미 있으면 nginx 는 나중 것을 무시한다.
#   nginx -t 는 통과하므로 적용은 성공으로 보이고 서비스만 되지 않는다.
assert_no_conflicting_server(){
  local target="$1" port="$2" name="$3" other escaped
  escaped="${name//./\\.}"
  for other in /etc/nginx/conf.d/*.conf; do
    [[ -f "$other" && "$other" != "$target" ]] || continue
    grep -qE "^[[:space:]]*listen[[:space:]]+(\[::\]:)?${port}([^0-9]|$)" "$other" || continue
    grep -qE "^[[:space:]]*server_name[[:space:]]+${escaped}[[:space:]]*;" "$other" || continue
    fail "이미 같은 포트($port)와 server_name($name) 을 잡은 설정이 있습니다: $other — nginx 가 나중 것을 무시해 서비스되지 않는다."
  done
}

# ★ upstream 은 정규식만 통과하면 그대로 설정에 들어간다. 오타가 나도 nginx -t 는
#   문법만 보므로 통과하고, 배포는 "성공" 하는데 실제로는 502 만 돌아온다.
#   여기서 실제로 붙어 본다. 다만 <실패해도 적용을 막지는 않는다> — WAS 를 나중에
#   올리는 순서가 정상인 구성이 있고, 그때 프록시 설정을 못 넣으면 그게 더 곤란하다.
#   대신 조용히 넘어가지 않고 경고를 남겨 배포 로그에 이유가 보이게 한다.
warn_if_upstream_unreachable(){
  local host="$1" port="$2"
  command -v timeout >/dev/null 2>&1 || return 0
  if timeout 3 bash -c "</dev/tcp/${host}/${port}" 2>/dev/null; then
    echo "UPSTREAM_REACHABLE=true"
    return 0
  fi
  echo "UPSTREAM_REACHABLE=false"
  echo "경고: upstream ${host}:${port} 에 접속하지 못했습니다. 설정은 적용하지만 지금 요청하면 502 가 납니다." >&2
}

apply_policy(){
  validate_apply
  # validate_apply confines certificate inputs to this request's staging folder.
  # Register cleanup before any later validation/installation can exit early.
  if [[ -n "$CERTIFICATE" ]]; then STAGED_CERT_DIR="$(dirname -- "$CERTIFICATE")"; fi
  if [[ -n "$CONFIG_FILE" ]]; then STAGED_CERT_DIR="$(dirname -- "$CONFIG_FILE")"; fi
  trap cleanup_apply_temps EXIT
  command -v nginx >/dev/null 2>&1 || fail "nginx가 설치되어 있지 않습니다."
  command -v openssl >/dev/null 2>&1 || fail "openssl이 설치되어 있지 않습니다."
  local config="/etc/nginx/conf.d/deploy-${POLICY_NAME}.conf"
  # Freeze and validate editable bytes before backing up or touching any live file.
  [[ -z "$CONFIG_FILE" ]] || freeze_raw_config
  assert_owns_config "$config"
  assert_no_conflicting_server "$config" "$LISTEN_PORT" "$SERVER_NAME"
  if [[ "$POLICY_TYPE" == "REVERSE_PROXY" ]]; then
    warn_if_upstream_unreachable "$UPSTREAM_HOST" "$UPSTREAM_PORT"
  elif [[ "$API_PROXY" == "true" ]]; then
    warn_if_upstream_unreachable "$API_UPSTREAM_HOST" "$API_UPSTREAM_PORT"
  fi
  local tls_dir="/etc/nginx/tls/${POLICY_NAME}"
  local current="${tls_dir}/current"
  local cert_path="${current}/fullchain.pem" key_path="${current}/privkey.pem"
  snapshot_tree "$config" "$tls_dir"
  backup_current "$config" "$tls_dir"
  validate_backup
  local backup_hash="$SNAPSHOT_HASH"
  RESTORE_ON_ERROR="$BACKUP_DIR"
  trap restore_on_error ERR
  TEMP_CONFIG="$(mktemp /etc/nginx/conf.d/.deploy-${POLICY_NAME}.XXXXXX)"

  if [[ -n "$CERTIFICATE" ]]; then
    validate_certificate "$CERTIFICATE" "$PRIVATE_KEY"
    install -d -m 700 "$current"
    install -m 644 "$CERTIFICATE" "$cert_path"
    install -m 600 "$PRIVATE_KEY" "$key_path"
    chown root:root "$cert_path" "$key_path"
  elif [[ "$TLS" == "true" ]]; then
    [[ -f "$cert_path" && -f "$key_path" ]] || { restore_backup "$BACKUP_DIR"; fail "기존 인증서가 없으므로 인증서와 개인키를 업로드해야 합니다."; }
    validate_certificate "$cert_path" "$key_path"
  fi

  if [[ -n "$RAW_BODY" ]]; then
    { printf '# Generated by Deploy System. Request %s.\n' "$REQUEST_ID"; cat -- "$RAW_BODY"; } > "$TEMP_CONFIG"
  else
    render_config "$TEMP_CONFIG" "$cert_path" "$key_path"
  fi
  install -m 644 "$TEMP_CONFIG" "$config"
  if ! nginx -t; then
    restore_backup "$BACKUP_DIR"; nginx -t >/dev/null 2>&1 || true
    fail "nginx -t 실패로 기존 설정과 인증서를 복구했습니다."
  fi
  if ! systemctl reload nginx; then
    restore_backup "$BACKUP_DIR"; nginx -t >/dev/null 2>&1 || true; systemctl reload nginx >/dev/null 2>&1 || true
    fail "nginx reload 실패로 기존 설정과 인증서를 복구했습니다."
  fi
  RESTORE_ON_ERROR=""
  trap - ERR
  echo "BACKUP_PATH=$BACKUP_DIR"
  echo "BACKUP_SNAPSHOT_SHA=$backup_hash"
  snapshot_tree "$config" "$tls_dir"
  emit_snapshot CURRENT_
  echo "CONFIG_PATH=$config"
  if [[ -n "$RAW_BODY" ]]; then echo "CONFIG_BODY_SHA=$CONFIG_SHA256"; fi
  # ★ restore_backup 과 같은 이유로 if/fi 다. 이게 `[[ ]] && cmd` 였을 때,
  #   TLS 를 끈 정책(평문 리버스 프록시)은 적용에 <성공하고도> 함수가 1 을 반환해
  #   스크립트가 exit 1 로 끝났다. 배포시스템은 그걸 실패로 보고 요청을 FAILED 로 남기는데,
  #   정작 nginx 에는 설정이 이미 반영되어 있다. 게다가 복구 요청은 APPLIED 상태에서만
  #   가능해서, 실제로 적용된 변경을 화면에서 되돌릴 수도 없었다.
  if [[ "$TLS" == "true" ]]; then
    emit_certificate_metadata "$cert_path"
  fi
}

# 배포시스템이 파싱해 저장한다 — 이 값이 없으면 인증서 만료를 아무도 추적하지 못한다.
# 각 항목은 실패해도 적용 자체를 되돌리지 않는다(설정은 이미 정상 반영된 뒤다).
emit_certificate_metadata(){
  local cert="$1" subject issuer not_after sans
  subject="$(openssl x509 -in "$cert" -noout -subject 2>/dev/null | sed 's/^subject=[[:space:]]*//')" || subject=""
  issuer="$(openssl x509 -in "$cert" -noout -issuer 2>/dev/null | sed 's/^issuer=[[:space:]]*//')" || issuer=""
  not_after="$(openssl x509 -in "$cert" -noout -enddate 2>/dev/null | cut -d= -f2)" || not_after=""
  # subjectAltName 은 OpenSSL 1.1.1+ 에서만 -ext 로 뽑힌다. 없으면 빈 값으로 둔다.
  sans="$(openssl x509 -in "$cert" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' \n')" || sans=""

  echo "CERT_SUBJECT=$subject"
  echo "CERT_ISSUER=$issuer"
  echo "CERT_SANS=$sans"
  # 앱이 그대로 파싱할 수 있게 ISO-8601(UTC)로 바꿔 준다. 로케일에 따라 openssl 의
  # 원문 형식이 달라질 수 있어 문자열을 그대로 넘기지 않는다.
  if [[ -n "$not_after" ]]; then
    echo "CERT_NOT_AFTER=$(date -u -d "$not_after" +%Y-%m-%dT%H:%M:%S 2>/dev/null || echo '')"
  else
    echo "CERT_NOT_AFTER="
  fi
}

rollback_policy(){
  [[ "$EXPECTED_SNAPSHOT" =~ ^[0-9a-f]{64}$ ]] || fail "approved snapshot required"
  safe_name "$POLICY_NAME" || fail "policy identity required"
  verify_current
  local current_hash="$SNAPSHOT_HASH" policy="$POLICY_NAME" config tls_dir safety approved stage
  config="/etc/nginx/conf.d/deploy-${policy}.conf"; tls_dir="/etc/nginx/tls/${policy}"
  validate_backup
  [[ "$SNAPSHOT_HASH" == "$EXPECTED_SNAPSHOT" ]] || fail "backup changed since request"
  # Freeze a private copy, then validate it before changing current config/TLS.
  approved="$BACKUP_DIR"; stage="$(mktemp -d "${BACKUP_ROOT}/approved-copy.XXXXXXXX")"
  chmod 700 "$stage"; cp -a "$approved/." "$stage/"
  BACKUP_DIR="$stage"; validate_backup
  [[ "$SNAPSHOT_HASH" == "$EXPECTED_SNAPSHOT" ]] || fail "backup changed during copy"
  safety="$(mktemp -d "${BACKUP_ROOT}/pre-rollback.XXXXXXXX")"; chmod 700 "$safety"
  printf '%s\n' "$policy" > "$safety/policy-name"
  if [[ -f "$config" ]]; then cp -a "$config" "$safety/config.conf"; else : > "$safety/config.absent"; fi
  if [[ -d "$tls_dir" ]]; then cp -a "$tls_dir" "$safety/tls"; else : > "$safety/tls.absent"; fi
  BACKUP_DIR="$safety"; validate_backup
  [[ "$SNAPSHOT_HASH" == "$current_hash" ]] || fail "safety snapshot drift"
  RESTORE_ON_ERROR="$safety"; trap restore_on_error ERR
  restore_backup "$stage"
  if ! nginx -t || ! systemctl reload nginx; then
    restore_backup "$safety"
    snapshot_tree "$config" "$tls_dir"
    [[ "$SNAPSHOT_HASH" == "$current_hash" ]] || fail "safety recovery digest mismatch"
    nginx -t >/dev/null 2>&1 || true; systemctl reload nginx >/dev/null 2>&1 || true
    fail "복구본 적용에 실패하여 검증된 복구 직전 상태로 되돌렸습니다."
  fi
  local post
  if ! post="$(snapshot_tree "$config" "$tls_dir"; emit_snapshot CURRENT_)" \
      || ! grep -Fqx "CURRENT_SNAPSHOT_SHA=$EXPECTED_SNAPSHOT" <<< "$post"; then
    restore_backup "$safety"
    snapshot_tree "$config" "$tls_dir"
    [[ "$SNAPSHOT_HASH" == "$current_hash" ]] || fail "safety recovery digest mismatch"
    nginx -t >/dev/null 2>&1 || true; systemctl reload nginx >/dev/null 2>&1 || true
    fail "restored digest mismatch; safety state restored"
  fi
  RESTORE_ON_ERROR=""; trap - ERR
  echo "BACKUP_PATH=$safety"
  echo "BACKUP_SNAPSHOT_SHA=$current_hash"
  printf '%s\n' "$post"
  echo "ROLLED_BACK_FROM=$approved"
  if [[ -f "$tls_dir/current/fullchain.pem" ]]; then emit_certificate_metadata "$tls_dir/current/fullchain.pem"; fi
}

inspect(){
  echo "TRAFFIC_ROLLOUT_VERSION=1"
  echo "UPSTREAM_LIST_VERSION=1"
  echo "EDITOR_CONFIG_VERSION=1"
  echo "NGINX_INSTALLED=$(command -v nginx >/dev/null 2>&1 && echo true || echo false)"
  command -v nginx >/dev/null 2>&1 || exit 0
  echo "NGINX_VERSION=$(nginx -v 2>&1 | sed 's|nginx version: ||')"
  echo "NGINX_ACTIVE=$(systemctl is-active nginx 2>/dev/null || true)"
  echo "CONFIG_ROOT=/etc/nginx/conf.d"
  echo "TLS_ROOT=/etc/nginx/tls"
  find /etc/nginx/conf.d -maxdepth 1 -type f -name 'deploy-*.conf' -printf 'MANAGED_CONFIG=%p\n' 2>/dev/null | sort
}

read_config(){
  safe_name "$POLICY_NAME" || fail "policy name이 올바르지 않습니다."
  local config="/etc/nginx/conf.d/deploy-${POLICY_NAME}.conf"
  snapshot_tree "$config" "/etc/nginx/tls/${POLICY_NAME}"
  echo "EDITOR_CONFIG_VERSION=1"
  emit_snapshot CURRENT_
  if [[ "$CONFIG_PRESENT" == true ]]; then
    printf 'CONFIG_BODY_SHA=%s\n' "$(tail -n +2 -- "$config" | sha256sum | cut -d' ' -f1)"
    printf 'CONFIG_BODY_BASE64='; tail -n +2 -- "$config" | base64 -w 0; printf '\n'
  fi
}

[[ "$EUID" -eq 0 ]] || fail "root 권한으로 실행해야 합니다."
parse "$@"
case "$ACTION" in
  apply|rollback|traffic-drain|traffic-restore)
    command -v flock >/dev/null 2>&1 || fail "flock is required."
    exec 8>/var/lock/deploy-ops-nginx.lock
    flock -n 8 || fail "Another WEB nginx operation is in progress."
    exec 9>"$LOCK_FILE"
    flock -n 9 || fail "Another Nginx policy operation is in progress."
    ;;
esac
if [[ "$ACTION" == apply || "$ACTION" == rollback ]]; then
  safe_name "$POLICY_NAME" || fail "policy name이 올바르지 않습니다."
  [[ ! -e "/var/lib/deploy-system/nginx-rollout/${POLICY_NAME}.json" \
     && ! -L "/var/lib/deploy-system/nginx-rollout/${POLICY_NAME}.json" ]] \
    || fail "배포 롤링의 트래픽 복구 후 nginx 설정을 변경하세요."
fi
case "$ACTION" in
  apply) apply_policy;;
  rollback) rollback_policy;;
  inspect) inspect;;
  read-config) read_config;;
  describe-backup) describe_backup;;
  traffic-drain|traffic-restore)
    safe_name "$POLICY_NAME" || fail "policy name이 올바르지 않습니다."
    command_args=("${ACTION#traffic-}" --policy "$POLICY_NAME" --generation "$ROLLOUT_GENERATION" --expected-sha "$ROLLOUT_CONFIG_SHA")
    if [[ "$ACTION" == traffic-drain ]]; then
      safe_host "$ROLLOUT_HOST" && safe_port "$ROLLOUT_PORT" || fail "롤링 대상 주소가 올바르지 않습니다."
      command_args+=(--host "$ROLLOUT_HOST" --port "$ROLLOUT_PORT")
      [[ "$ROLLOUT_ALLOW_EMPTY" != true ]] || command_args+=(--allow-zero-upstreams)
    fi
    nginx_python_helper /usr/local/deploy/bin/nginx-rollout.py "${command_args[@]}";;
  *) fail "사용법: deploy-nginx-policy.sh apply|rollback|inspect [허용 옵션]";;
esac
