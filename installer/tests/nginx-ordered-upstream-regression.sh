#!/bin/bash
set -Eeuo pipefail
agent="${1:?staged agent required}"
fixture="${2:?owned fixture directory required}"
mkdir -p "$fixture"
source <(sed '/^\[\[.*EUID/,$d' "$agent")
parse --request-id 7 --policy-name ordered --server-name localhost --listen 18091 --tls false --upstream-purpose PROXY --upstream-endpoint 127.0.0.1 19001 --upstream-endpoint 127.0.0.1 19002 --upstream-endpoint ::1 19003
validate_apply
render_config "$fixture/three.conf" '' ''
[[ $(grep -c '^    server ' "$fixture/three.conf") == 3 ]]
[[ $(grep '^    server ' "$fixture/three.conf" | head -1) == *127.0.0.1:19001* ]]
[[ $(grep '^    server ' "$fixture/three.conf" | tail -1) == *'[0:0:0:0:0:0:0:1]:19003'* ]]
grep -Fq 'proxy_next_upstream_tries 3;' "$fixture/three.conf"
for header in Forwarded X-Forwarded-Port X-Forwarded-Prefix X-Forwarded-Ssl;do grep -Fq "proxy_set_header $header \"\";" "$fixture/three.conf";done
grep -Fq 'proxy_set_header X-Forwarded-For $remote_addr;' "$fixture/three.conf"
# Every negative is a subprocess of the same real parser/validator; no apply or reload.
base="$(sed '/^\[\[.*EUID/,$d' "$agent")"
negative(){ if bash -c "$base"$'\n''parse "$@"; validate_apply' _ "$@" >/dev/null 2>&1;then echo 'NEGATIVE_ACCEPTED' >&2;exit 1;fi; }
common='--request-id 7 --policy-name ordered --server-name localhost --listen 18091 --tls false'
negative $common --upstream-purpose PROXY --upstream-endpoint 127.0.0.1 19001 --upstream-endpoint 127.0.0.1 19001
negative $common --upstream-purpose PROXY --upstream-endpoint 127.0.0.1 19001 --upstream-endpoint bad\;directive 19002
negative $common --upstream-purpose PROXY --upstream-endpoint 127.0.0.1 19001 --upstream-endpoint 127.0.0.1 0
negative $common --upstream-purpose PROXY --upstream-host 127.0.0.1 --upstream-port 19001 --upstream-endpoint 127.0.0.2 19002
negative $common --upstream-purpose PROXY
negative $common --upstream-purpose PROXY --upstream-endpoint ::1 19001 --upstream-endpoint 0:0:0:0:0:0:0:1 19001
negative $common --upstream-purpose PROXY --upstream-endpoint 1:2:3 19001
negative $common --upstream-purpose PROXY --upstream-endpoint 999.1.2.3 19001
negative $common --upstream-purpose PROXY --upstream-endpoint 127.0.0.1. 19001
negative $common --upstream-purpose PROXY --upstream-endpoint 1::2: 19001
# API purpose renders all targets under only the static API path.
ENDPOINT_HOSTS=();ENDPOINT_PORTS=();UPSTREAM_PURPOSE='';LEGACY_UPSTREAM=false;UPSTREAM_HOST='';UPSTREAM_PORT=''
parse --policy-type STATIC_SPA --web-root /var/www/qa --api-proxy true --api-prefix /api/ --upstream-purpose API --upstream-endpoint 127.0.0.1 19001 --upstream-endpoint 127.0.0.1 19002 --upstream-endpoint ::1 19003
validate_apply;render_config "$fixture/api.conf" '' ''
grep -Fq 'location ^~ /api/' "$fixture/api.conf";[[ $(grep -c '^    server ' "$fixture/api.conf") == 3 ]]
# Historical two-address CLI still renders its original endpoint order.
ENDPOINT_HOSTS=();ENDPOINT_PORTS=();UPSTREAM_PURPOSE='';LEGACY_UPSTREAM=false;API_PROXY=false;API_PREFIX='';API_UPSTREAM_HOST='';API_UPSTREAM_PORT=1;WEB_ROOT='';POLICY_TYPE=REVERSE_PROXY
parse --upstream-host 127.0.0.1 --upstream-port 19001 --secondary-upstream-host 127.0.0.1 --secondary-upstream-port 19002
validate_apply;render_config "$fixture/legacy-two.conf" '' '';[[ $(grep -c '^    server ' "$fixture/legacy-two.conf") == 2 ]]
echo 'PASS_REAL_BASH_PARSER_RENDERER_3_API_LEGACY_AND_NEGATIVE_NOT_NGINX_TRAFFIC'
