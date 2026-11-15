#!/usr/bin/env bash
# Source consistency only; no compose, Docker or database execution.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$(sed -n 's/^MARIADB_VERSION="\([0-9][0-9.]*\)"$/\1/p' "$root/installer/bundles/fetch-bundles.sh")"
[[ "$version" == 11.4.13 ]]
[[ "$(grep -c -F "    image: mariadb:$version" "$root/docker-compose.yml")" == 1 ]]
grep -F '기존 메이저 버전의 볼륨은 논리 백업·별도 복원을 거쳐 이관한다.' "$root/docker-compose.yml" >/dev/null
grep -F '      - mariadb-data:/var/lib/mysql' "$root/docker-compose.yml" >/dev/null
printf '%s\n' 'PASS Deploy compose and official collector share the exact 11.4.13 baseline; existing volume mapping is retained'
