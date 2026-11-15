#!/usr/bin/env bash
# nginx.sh - DMZ 리버스 프록시(Nginx) 설치/구성 컴포넌트 (OS무관: rpm/deb)
#   포트 기반 proxy_pass. 신뢰 프록시 헤더(X-Forwarded-*)를 세팅한다.

NGINX_CONF_RHEL="/etc/nginx/conf.d/officesystem.conf"
NGINX_CONF_DEB="/etc/nginx/conf.d/officesystem.conf"   # 데비안도 nginx.conf 가 conf.d 를 include

_nginx_install_packages() {
  bundle_dir nginx >/dev/null 2>&1 && bundle_has_packages nginx \
    || { err "오프라인 Nginx 패키지 번들이 없습니다. 필요한 패키지를 반입한 뒤 다시 실행하세요."; return 1; }
  info "Nginx 패키지 설치(번들): $(bundle_dir nginx)"
  install_local_packages "$(bundle_dir nginx)" || return 1
  command -v openssl >/dev/null 2>&1 \
    || { err "Nginx 관리에 필요한 openssl 실행 파일이 없습니다. 오프라인 패키지 구성을 확인하세요."; return 1; }
}

# 패키지가 깔린 것과 "이 시스템용으로 구성된 것"은 다르다.
# 둘을 구분하지 않으면 상태표가 installed 로 보이는데 실제로는 프록시/정적 사이트 설정이
# 하나도 없는 상태가 된다 — 처음 설치하는 사람이 여기서 그냥 넘어간다.
nginx_status() {
  local conf="$NGINX_CONF_RHEL"
  [[ "${OS_FAMILY:-}" == "debian" ]] && conf="$NGINX_CONF_DEB"
  if [[ -f "$conf" ]]; then
    if command -v openssl >/dev/null 2>&1 && nginx -t >/dev/null 2>&1 && systemctl is-active --quiet nginx \
      && systemctl is-enabled --quiet nginx; then
      echo installed
    else
      echo drift
    fi
  elif command -v nginx >/dev/null 2>&1 || pkg_is_installed nginx; then
    echo partial          # 패키지만 있고 구성 없음
  else
    echo missing
  fi
}

nginx_install() (
  command -v flock >/dev/null 2>&1 || { err "flock is required"; return 1; }
  local lock_root="${NGINX_REPLAY_LOCK_ROOT:-/var/lock}" lock
  local candidate="" published="" owned_identity="" owned_sha=""
  local stock_original="" stock_disabled="" stock_identity="" stock_sha=""
  local -a created_dirs=() created_dir_ids=()
  mkdir -p "$lock_root" || return 1
  # Each WEB agent owns one of these locks; acquire all three in fixed order.
  for lock in deploy-ops-nginx-policy.lock deploy-ops-web-proxy.lock deploy-ops-web-static.lock; do
    [[ ! -L "$lock_root/$lock" ]] || { err "Nginx lock symlink refused"; return 1; }
    if [[ -e "$lock_root/$lock" ]]; then
      [[ -f "$lock_root/$lock" && "$(stat -c %u "$lock_root/$lock")" == 0 ]] || return 1
    fi
  done
  exec 200>"$lock_root/deploy-ops-nginx-policy.lock" || return 1
  flock -n 200 || { err "Nginx policy operation in progress"; return 1; }
  exec 201>"$lock_root/deploy-ops-web-proxy.lock" || return 1
  flock -n 201 || { err "WEB proxy operation in progress"; return 1; }
  exec 202>"$lock_root/deploy-ops-web-static.lock" || return 1
  flock -n 202 || { err "WEB static operation in progress"; return 1; }
  trap 'rc=$?; if (( rc != 0 )); then if [[ -n "$published" ]] && declare -F _nginx_remove_owned_failed_config >/dev/null; then _nginx_remove_owned_failed_config || true; fi; _nginx_restore_owned_stock; _nginx_remove_owned_dirs; fi; [[ -z "$candidate" ]] || rm -f -- "$candidate"; exit "$rc"' EXIT
  local force="${1:-}"
  local retained_conf="$NGINX_CONF_RHEL" retained_mode retained_sha packages_reconciled=0
  [[ "${OS_FAMILY:-}" == "debian" ]] && retained_conf="$NGINX_CONF_DEB"
  # Replaying the installer must never regenerate UI-managed live routes.
  # Even force reinstalls preserve configuration; route edits belong to the UI.
  if [[ -e "$retained_conf" || -L "$retained_conf" ]]; then
    [[ -f "$retained_conf" && ! -L "$retained_conf" \
      && "$(stat -c %u "$retained_conf")" == 0 ]] \
      || { err "기존 Nginx 구성은 root 소유 일반 파일이어야 합니다."; return 1; }
    retained_mode="$(stat -c %a "$retained_conf")"
    [[ "$retained_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$retained_mode & 022) == 0 )) \
      || { err "기존 Nginx 구성의 그룹/기타 쓰기 권한을 허용하지 않습니다."; return 1; }
    retained_sha="$(sha256sum "$retained_conf" | awk '{print $1}')" || return 1
    nginx -t || { err "기존 Nginx 구성 검증 실패 — 구성은 보존했습니다."; return 1; }
    [[ "$(sha256sum "$retained_conf" | awk '{print $1}')" == "$retained_sha" ]] \
      || { err "Nginx config changed during validation; preserved"; return 1; }
    if [[ "$force" == force ]] || ! command -v openssl >/dev/null 2>&1; then
      _nginx_install_packages || return 1
      packages_reconciled=1
      [[ "$(sha256sum "$retained_conf" | awk '{print $1}')" == "$retained_sha" ]] \
        || { err "패키지 갱신 중 기존 Nginx 구성이 변경되었습니다. 적용하지 않았습니다."; return 1; }
      nginx -t || { err "갱신된 Nginx의 구성 검증 실패 — 기존 구성은 보존했습니다."; return 1; }
    fi
    systemctl enable nginx || return 1
    if systemctl is-active --quiet nginx; then
      if (( packages_reconciled )); then
        # A reload leaves the old master binary running after a package update.
        systemctl restart nginx || { err "갱신된 Nginx 재시작 실패 — 구성은 보존했습니다."; return 1; }
      else
        systemctl reload nginx || { err "Nginx reload 실패 — 구성은 보존했습니다."; return 1; }
      fi
    else
      systemctl start nginx || { err "Nginx 기동 실패 — 구성은 보존했습니다."; return 1; }
    fi
    [[ "$(sha256sum "$retained_conf" | awk '{print $1}')" == "$retained_sha" ]] \
      || { err "재설치 중 Nginx 구성이 변경되었습니다. 덮어쓰거나 복원하지 않았습니다."; return 1; }
    systemctl is-active --quiet nginx && systemctl is-enabled --quiet nginx || return 1
    info "기존 Nginx 구성 보존 및 실행 상태 검증 완료: $retained_conf"
    return 0
  fi
  if [[ "$(nginx_status)" == "installed" && "$force" != "force" ]]; then
    info "Nginx 이미 설치됨: $(nginx -v 2>&1)"
  else
    _nginx_install_packages || return 1
  fi

  mkdir -p "$(dirname "$retained_conf")" || return 1
  candidate="$(mktemp "$(dirname "$retained_conf")/.officesystem-installer.XXXXXX")" || return 1
  # 설치 직후 부팅 자동 기동을 먼저 확정한다.
  #   아래 DMZ 구성 단계는 "선택된 서비스 없음"이나 검증 실패로 중간에 빠져나갈 수 있는데,
  #   enable 을 그 성공 경로 안에만 두면 그런 설치본은 재부팅 후 nginx 가 올라오지 않는다.
  #   설치했으면 켜지는 것이 기본값이어야 한다.
  systemctl enable nginx || { err "nginx 부팅 자동 기동 등록 실패"; return 1; }
  systemctl is-enabled --quiet nginx || { err "nginx 부팅 자동 기동 상태 검증 실패"; return 1; }

  # ── 구성 (대화형/무인 공용) ──
  # 무인 응답 키: NGINX_UPSTREAM_HOST(필수) / NGINX_MAX_BODY /
  #   서비스별 NGINX_PROXY_{OFFICE,DEPLOY,GITLAB,NEXUS_WEB,NEXUS_REGISTRY}(y|n, 기본 y)
  #   포트 재정의 NGINX_{키}_LISTEN / NGINX_{키}_UPSTREAM
  #   NGINX_CUSTOM_SERVICES — 위 5종 외 업무 앱 프록시(형식은 아래 해당 블록 주석 참조)
  section "DMZ 프록시 구성 (포트 기반)"
  local upstream
  upstream="$(ask NGINX_UPSTREAM_HOST "내부 앱 호스트(IP) — 예: 192.0.2.96 : " "")"
  if [[ -z "$upstream" ]]; then err "내부 앱 호스트 IP가 필요합니다."; return 1; fi

  local maxbody
  maxbody="$(ask NGINX_MAX_BODY "최대 업로드 크기(레지스트리/대용량 대비) [0=무제한]: " "0")"

  # 프록시할 서비스 목록: 키|표시명|기본 listen|기본 upstream|대용량여부|외부존헤더(앱이 존 판별)
  local services=(
    "OFFICE|오피스 웹|18080|18080|no|yes"
    "DEPLOY|배포시스템|9090|9090|no|yes"
    "GITLAB|GitLab|8929|8929|yes|no"
    "NEXUS_WEB|Nexus 웹|8081|8081|no|no"
    "NEXUS_REGISTRY|Nexus 레지스트리|5000|5000|yes|no"
  )

  local conf="$NGINX_CONF_RHEL"
  [[ "$OS_FAMILY" == "debian" ]] && conf="$NGINX_CONF_DEB"
  : > "$candidate"

  local entry skey name lport uport big zone count=0
  local -A listen_owners=()
  for entry in "${services[@]}"; do
    skey="$(printf '%s' "$entry" | cut -d'|' -f1)"
    name="$(printf '%s' "$entry" | cut -d'|' -f2)"
    lport="$(printf '%s' "$entry" | cut -d'|' -f3)"
    uport="$(printf '%s' "$entry" | cut -d'|' -f4)"
    big="$(printf '%s' "$entry" | cut -d'|' -f5)"
    zone="$(printf '%s' "$entry" | cut -d'|' -f6)"
    if ask_confirm "NGINX_PROXY_${skey}" "[$name] 프록시할까요? (listen ${lport} → ${upstream}:${uport})" y; then
      lport="$(ask "NGINX_${skey}_LISTEN" "  $name listen 포트 [$lport]: " "$lport")"
      uport="$(ask "NGINX_${skey}_UPSTREAM" "  $name upstream 포트 [$uport]: " "$uport")"
      _nginx_claim_listen listen_owners "$lport" "$name" \
        || { rm -f "$candidate"; return 1; }
      local cmb="$maxbody"
      [[ "$big" == "yes" ]] && cmb="0"
      _nginx_server_block "$name" "$lport" "$upstream" "$uport" "$cmb" "$zone" >> "$candidate"
      count=$((count+1))
    fi
  done

  # ── 사용자 정의 프록시 대상 ─────────────────────────────────
  # 위 5종은 이 저장소가 배포하는 서비스라 고정이다. 그 외 업무 앱을 DMZ 로 노출하려면
  # 설치 후 conf 를 손으로 쓰는 대신 아래 키로 선언한다(무인 설치에서도 재현 가능).
  #   NGINX_CUSTOM_SERVICES="표시명|listen|upstream포트|upstream호스트|외부존(yes|no); ..."
  #   - upstream호스트를 비우면 NGINX_UPSTREAM_HOST 를 쓴다(WAS VIP 지정 시 유용).
  #   - 외부존=yes 면 X-Access-Zone: external 주입 + 업로드 스트리밍.
  # 예) "업무앱|8080|8080||yes"  또는  "업무앱|80|8080|192.0.2.100|yes; 배치|8081|8081||no"
  local custom_raw
  custom_raw="$(ask NGINX_CUSTOM_SERVICES "추가 프록시 대상 (표시명|listen|upstream포트|호스트|외부존; …) [없으면 엔터]: " "")"
  if [[ -n "$custom_raw" ]]; then
    local IFS_OLD="$IFS" rec
    IFS=';'
    for rec in $custom_raw; do
      IFS="$IFS_OLD"
      rec="$(printf '%s' "$rec" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      [[ -n "$rec" ]] || { IFS=';'; continue; }

      local c_name c_lport c_uport c_host c_zone
      c_name="$(printf '%s' "$rec" | cut -d'|' -f1)"
      c_lport="$(printf '%s' "$rec" | cut -d'|' -f2)"
      c_uport="$(printf '%s' "$rec" | cut -d'|' -f3)"
      c_host="$(printf '%s' "$rec" | cut -d'|' -f4)"
      c_zone="$(printf '%s' "$rec" | cut -d'|' -f5)"
      [[ -n "$c_host" ]] || c_host="$upstream"
      [[ "$c_zone" == "yes" ]] || c_zone="no"

      if [[ -z "$c_name" || ! "$c_lport" =~ ^[0-9]{1,5}$ || ! "$c_uport" =~ ^[0-9]{1,5}$ ]]; then
        err "NGINX_CUSTOM_SERVICES 항목 형식 오류(표시명|listen|upstream포트|호스트|외부존): $rec"
        rm -f "$candidate"
        return 1
      fi

      _nginx_claim_listen listen_owners "$c_lport" "$c_name" \
        || { rm -f "$candidate"; return 1; }
      _nginx_server_block "$c_name" "$c_lport" "$c_host" "$c_uport" "$maxbody" "$c_zone" \
        >> "$candidate"
      info "  + 사용자 정의: $c_name (listen $c_lport → $c_host:$c_uport, 외부존=$c_zone)"
      count=$((count+1))
      IFS=';'
    done
    IFS="$IFS_OLD"
  fi

  # ── 정적 사이트(SSR/CSR 산출물) ────────────────────────────
  #   배포 에이전트는 <web-root>/releases/<시각>/ 에 풀고 <web-root>/current 심볼릭 링크를 돌린다.
  #   그 current 를 가리키는 server 블록이 없으면 배포는 "성공"하는데 아무것도 서비스되지 않는다.
  #   게다가 헬스체크가 nginx 기본 페이지를 200 으로 통과시켜 조용히 넘어간다.
  local static_root static_listen
  static_root="$(ask NGINX_STATIC_ROOT "정적 산출물 web-root (배포 에이전트의 --web-root) [없으면 엔터]: " "")"
  if [[ -n "$static_root" ]]; then
    static_listen="$(ask NGINX_STATIC_LISTEN "  정적 사이트 listen 포트 [80]: " "80")"
    _nginx_claim_listen listen_owners "$static_listen" "정적 사이트" \
      || { rm -f "$candidate"; return 1; }
    _nginx_static_block "$static_root" "$static_listen" "$maxbody" >> "$candidate"
    info "  + 정적 사이트: ${static_root}/current (listen ${static_listen})"
    count=$((count+1))
    # 배포 전에도 nginx 가 기동해야 하므로 디렉터리를 미리 만들어 둔다.
    # 설치 스크립트의 umask 가 077 이어도 nginx worker 가 root/current/release 를
    # 통과할 수 있도록 공개 정적 경로의 두 디렉터리만 root 소유 0755 로 고정한다.
    _nginx_prepare_static_root "$static_root" \
      || { rm -f "$candidate"; return 1; }
    _nginx_disable_stock_default "$static_listen" || return 1
  fi

  if [[ "$count" -eq 0 ]]; then
    info "선택된 서비스가 없어 구성을 생성하지 않았습니다."
    rm -f "$candidate"
    return 0
  fi

  chmod 0644 "$candidate" || return 1
  owned_identity="$(stat -c '%d:%i' "$candidate")" || return 1
  owned_sha="$(sha256sum "$candidate" | awk '{print $1}')" || return 1
  # Atomic no-clobber publication refuses any target created during generation.
  ln -T -- "$candidate" "$conf" || { err "Nginx target appeared during generation; preserved"; return 1; }
  published="$conf"
  _nginx_owned_config() {
    [[ -f "$published" && ! -L "$published" \
      && "$(stat -c '%d:%i' "$published")" == "$owned_identity" \
      && "$(sha256sum "$published" | awk '{print $1}')" == "$owned_sha" ]]
  }
  _nginx_remove_owned_failed_config() {
    if _nginx_owned_config; then rm -f -- "$published" || return 1
    else err "Nginx config changed externally; cleanup refused"; return 1; fi
  }
  if ! nginx -t; then
    _nginx_remove_owned_failed_config || true
    err "New Nginx config failed validation; only unchanged owned candidate removed"
    return 1
  fi
  _nginx_owned_config || { err "Nginx config changed before restart; preserved"; return 1; }
  if ! systemctl restart nginx; then
    _nginx_remove_owned_failed_config || true
    err "Nginx restart failed; no stale backup restoration"
    return 1
  fi
  _nginx_owned_config || { err "Nginx config changed during restart; preserved"; return 1; }
  systemctl is-active --quiet nginx && systemctl is-enabled --quiet nginx || return 1
  success "Nginx DMZ proxy applied"
  info "다음 단계(필수): 내부 앱 호스트($upstream)에서"
  info "  1) 앱은 X-Forwarded-* 신뢰 — Spring: server.forward-headers-strategy=framework"
  info "     + server.tomcat.remoteip.internal-proxies 에 이 DMZ IP 등록"
  info "  2) 앱 포트는 이 DMZ($(hostname -I 2>/dev/null | awk '{print $1}'))에서만 접근 허용(방화벽)"
  info "  3) 라우터 외부 포워딩은 이 DMZ 호스트로만 지정"
)

# 정적 파일은 nginx worker 가 읽는다. root/release 경로 자체는 웹 공개 경로이므로
# 정확히 이 두 디렉터리만 root:root 0755 로 맞추고, 상위 경로나 기존 파일은 변경하지 않는다.
_nginx_prepare_static_root() {
  local static_root="$1" parent current component mode
  local -a components=()
  [[ "$static_root" =~ ^/[A-Za-z0-9._/+:-]+$ \
    && "$static_root" == /*/* \
    && "$static_root" != */ \
    && "$static_root" != *//* \
    && ! "$static_root" =~ (^|/)\.($|/) \
    && ! "$static_root" =~ (^|/)\.\.($|/) ]] || {
    err "정적 WebRoot 는 /var/www/app 과 같은 하위 경로여야 합니다: $static_root"
    return 1
  }
  parent="${static_root%/*}"
  [[ -n "$parent" ]] || parent="/"
  IFS='/' read -r -a components <<< "${parent#/}"
  current="/"
  for component in "${components[@]}"; do
    [[ -n "$component" ]] || continue
    current="${current%/}/$component"
    [[ ! -L "$current" ]] || {
      err "정적 WebRoot 상위 경로에 심볼릭 링크가 있습니다. 실제 경로를 지정하세요: $current"
      return 1
    }
    if [[ ! -e "$current" ]]; then
      _nginx_create_owned_dir "$current" || return 1
    fi
    [[ -d "$current" ]] || { err "정적 WebRoot 상위 경로가 디렉터리가 아닙니다: $current"; return 1; }
    mode="$(stat -c '%a' -- "$current")" || { err "정적 WebRoot 상위 권한을 확인할 수 없습니다: $current"; return 1; }
    (( (8#$mode & 0001) != 0 )) || {
      err "정적 WebRoot 상위 경로에 nginx worker 공개 탐색 권한이 없습니다: $current (mode $mode)"
      return 1
    }
  done
  [[ ! -L "$static_root" ]] || { err "정적 WebRoot 자체가 심볼릭 링크입니다. 실제 경로를 지정하세요: $static_root"; return 1; }
  [[ ! -L "$static_root/releases" ]] || { err "정적 WebRoot/releases 가 심볼릭 링크입니다."; return 1; }
  for current in "$static_root" "$static_root/releases"; do
    if [[ ! -e "$current" ]]; then _nginx_create_owned_dir "$current" || return 1; fi
    [[ -d "$current" && ! -L "$current" && "$(stat -c %u "$current")" == 0 ]] || return 1
    mode="$(stat -c %a "$current")" || return 1
    (( (8#$mode & 0001) != 0 && (8#$mode & 0022) == 0 )) || {
      err "Existing static directory must be publicly traversable and not writable by group/others; permissions preserved"
      return 1
    }
  done
}

# 생성하는 블록은 모두 server_name _ 이므로 같은 listen을 공유할 수 없다.
_nginx_claim_listen() {
  local -n owners="$1"
  local port="$2" label="$3" normalized
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( 10#$port >= 1 && 10#$port <= 65535 )) \
    || { err "Nginx listen 포트는 1~65535여야 합니다: $port"; return 1; }
  normalized="$((10#$port))"
  if [[ -n "${owners[$normalized]:-}" ]]; then
    err "Nginx listen 포트 $port 중복: ${owners[$normalized]} / $label. 서로 다른 포트를 지정하세요. 같은 포트의 경로 분기는 별도 Nginx 사이트 설정이 필요합니다."
    return 1
  fi
  owners[$normalized]="$label"
}

# server block 1개 생성(표준 신뢰프록시 헤더 + 웹소켓 + 타임아웃)
#   - X-Forwarded-For 는 \$remote_addr 로 '덮어쓰기' — 클라이언트가 위조한 XFF(사설 IP)로
#     외부존 OTP를 우회하는 것을 차단한다(append 금지).
#   - zone=yes(오피스/배포): X-Access-Zone external 주입 + 업로드 스트리밍(request_buffering off)
_nginx_server_block() {
  local name="$1" lport="$2" upstream="$3" uport="$4" maxbody="$5" zone="${6:-no}"
  local zone_lines=""
  if [[ "$zone" == "yes" ]]; then
    zone_lines=$'        proxy_set_header X-Access-Zone external;\n        proxy_request_buffering off;'
  fi
  cat <<EOF
# ${name}
server {
    listen ${lport};
    server_name _;
    client_max_body_size ${maxbody};
    location / {
        proxy_pass http://${upstream}:${uport};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
${zone_lines}
    }
}
EOF
}

# 정적 산출물 server block — root 는 반드시 current(심볼릭 링크)를 가리킨다.
#   배포 에이전트가 링크를 원자적으로 갈아끼우므로, 여기를 releases/ 나 web-root 자체로
#   잡으면 배포해도 옛 내용이 계속 나간다.
#   승인 정책의 deploy-*.conf가 먼저 읽혀도 이름 없는 기존 접속은 이 사이트를 유지한다.
#   같은 포트의 명시적인 server_name 정책(HTTP 프록시·HTTPS 리다이렉트)은 그대로 우선한다.
_nginx_static_block() {
  local root="$1" lport="$2" maxbody="$3"
  cat <<EOF
# static site (deploy-web-static.sh 가 갱신하는 current 를 서빙)
server {
    listen ${lport} default_server;
    server_name _;
    client_max_body_size ${maxbody};
    root ${root}/current;
    index index.html;

    # SPA 라우팅: 파일이 없으면 index.html 로 폴백한다.
    location / {
        try_files \$uri \$uri/ /index.html;
    }

    # 배포 확인용 — 이 경로가 200 이면 current 가 실제로 걸려 있다는 뜻이다.
    # 헬스체크를 nginx 기본 페이지로 하면 배포 실패를 못 잡는다.
    location = /index.html {
        add_header Cache-Control "no-cache";
    }
}
EOF
}

# 배포판 기본 server 블록 비활성화.
#   nginx.org 패키지는 /etc/nginx/conf.d/default.conf 로 80 을 잡는다. conf.d 는 알파벳 순으로
#   로드되므로 default.conf 가 officesystem.conf 보다 먼저 읽히고, server_name 이 매칭되지 않는
#   요청은 "첫 번째 블록"으로 간다 — 즉 우리 정적 사이트가 아니라 기본 페이지가 나간다.
#   더 나쁜 건 그 기본 페이지도 200 이라, 배포 헬스체크가 통과해 <b>배포 실패를 못 잡는다</b>.
_nginx_create_owned_dir() {
  local path="$1" id
  mkdir -m 0755 -- "$path" || return 1
  id="$(stat -c '%d:%i:%a' "$path")" || return 1
  created_dirs+=("$path"); created_dir_ids+=("$id")
}
_nginx_remove_owned_dirs() {
  local i path
  for ((i=${#created_dirs[@]}-1; i>=0; i--)); do
    path="${created_dirs[$i]}"
    if [[ -d "$path" && ! -L "$path" && "$(stat -c '%d:%i:%a' "$path")" == "${created_dir_ids[$i]}" ]]; then
      rmdir -- "$path" 2>/dev/null || true
    fi
  done
}
_nginx_restore_owned_stock() {
  [[ -n "$stock_disabled" ]] || return 0
  if [[ ! -e "$stock_original" && ! -L "$stock_original" && -f "$stock_disabled" && ! -L "$stock_disabled" \
    && "$(stat -c '%d:%i:%a' "$stock_disabled")" == "$stock_identity" \
    && "$(sha256sum "$stock_disabled" | awk '{print $1}')" == "$stock_sha" ]]; then
    if ln -T -- "$stock_disabled" "$stock_original"; then rm -f -- "$stock_disabled"; fi
  else
    err "Stock configuration changed externally; automatic restoration refused"
  fi
}
_nginx_disable_stock_default() {
  local port="$1" f="${NGINX_STOCK_DEFAULT:-/etc/nginx/conf.d/default.conf}"
  [[ -e "$f" || -L "$f" ]] || return 0
  [[ -f "$f" && ! -L "$f" && "$(stat -c %u "$f")" == 0 ]] || return 1
  grep -qE "^[[:space:]]*listen[[:space:]]+(\[::\]:)?${port}([^0-9]|$)" "$f" || return 0
  local mode="$(stat -c %a "$f")"
  (( (8#$mode & 0022) == 0 )) || return 1
  stock_original="$f"; stock_disabled="${f}.disabled-by-installer"
  [[ ! -e "$stock_disabled" && ! -L "$stock_disabled" ]] || { err "Existing disabled stock backup preserved"; return 1; }
  stock_identity="$(stat -c '%d:%i:%a' "$f")" || return 1
  stock_sha="$(sha256sum "$f" | awk '{print $1}')" || return 1
  # No-clobber link reserves the backup; only delete the matching original.
  ln -T -- "$f" "$stock_disabled" || return 1
  [[ "$(stat -c '%d:%i:%a' "$f")" == "$stock_identity" \
    && "$(sha256sum "$f" | awk '{print $1}')" == "$stock_sha" ]] || return 1
  rm -f -- "$f" || return 1
  info "Matching stock default disabled without overwriting an existing backup"
}
