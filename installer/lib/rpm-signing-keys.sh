#!/usr/bin/env bash
# 공식 게시 fingerprint 및 검토한 export SHA pin. 키 갱신은 소스 검토를 거쳐야 한다.
# https://rockylinux.org/resources/gpg-key-info
# https://docs.docker.com/engine/install/centos/
# https://docs.nginx.com/nginx/admin-guide/installing-nginx/installing-nginx-open-source/
# https://mariadb.com/docs/server/server-management/install-and-upgrade-mariadb/installing-mariadb/binary-packages/gpg
rpm_key_contracts() {
  printf '%s\n' \
    'rocky8|7051C470A929F454CEBE37B715AF5DAC6D745A60|ef1ca99bba38bb2869a06fbf46860fcdaeece796be98f8dfb0160b84bdbc69ab|https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-rockyofficial' \
    'docker|060A61C51B558A7F742B77AAC52FEB6B621E9F35|e6c650e0700b1bf4868b693b30761b926844befc8a0acb7ac0dd9b1faf1b7423|https://download.docker.com/linux/centos/gpg' \
    'nginx|8540A6F18833A80E9C1653A42FD21310B49F6B46|9e1dde9a3501229679707fc7581379c488a00b2f936292ae11bfb9f992f47171|https://nginx.org/keys/nginx_signing.key' \
    'mariadb|177F4010FE56CA3336300305F1656F24C74CD1D8|893f3856fbdab24ab610731247ac06c1c520aead4b5e0dfef412f1395171037b|https://supplychain.mariadb.com/MariaDB-Server-GPG-KEY'
}

export_rpm_signing_keys() (
  set -euo pipefail
  local dest="$1" task_key_tmp vendor fingerprint expected url actual ids tool
  for tool in curl gpg sha256sum awk mktemp; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Key collector requires %s\n' "$tool" >&2; exit 1; }
  done
  task_key_tmp="$(mktemp -d)"
  trap 'rm -rf "$task_key_tmp"' EXIT
  mkdir -p "$dest"
  while IFS='|' read -r vendor fingerprint expected url; do
    mkdir -m 700 "$task_key_tmp/$vendor"
    curl --proto '=https' --tlsv1.2 -fSL "$url" -o "$task_key_tmp/$vendor.asc"
    gpg --homedir "$task_key_tmp/$vendor" --batch --import "$task_key_tmp/$vendor.asc"
    # full primary fingerprint 1개만 export한다. 같은 파일의 다른 키는 신뢰하지 않는다.
    # Rocky RPM 4.14 import는 ASCII armor를 요구한다(확장자와 무관).
    gpg --homedir "$task_key_tmp/$vendor" --batch --armor --export "$fingerprint" > "$dest/$vendor.gpg"
    ids="$(gpg --homedir "$task_key_tmp/$vendor" --batch --with-colons --show-keys "$dest/$vendor.gpg" \
      | awk -F: '$1=="pub" {primary=1; next} $1=="fpr" && primary {print $10; primary=0}')"
    [[ "$ids" == "$fingerprint" ]] || { printf 'Unexpected key export: %s\n' "$vendor" >&2; exit 1; }
    actual="$(sha256sum "$dest/$vendor.gpg" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || { printf 'Exported key changed; review required: %s\n' "$vendor" >&2; exit 1; }
    printf '%s|%s|%s\n' "$vendor" "$fingerprint" "$actual"
  done < <(rpm_key_contracts)
  rpm_key_contracts > "$dest/KEYS.lock"
)

verify_rpm_signing_keys() {
  local dir="$1" vendor fingerprint expected url actual
  [[ -f "$dir/KEYS.lock" && "$(sed 's/\r$//' "$dir/KEYS.lock")" == "$(rpm_key_contracts)" ]] || return 1
  while IFS='|' read -r vendor fingerprint expected url; do
    [[ "$expected" =~ ^[0-9a-f]{64}$ && -f "$dir/$vendor.gpg" && ! -L "$dir/$vendor.gpg" ]] || return 1
    actual="$(sha256sum "$dir/$vendor.gpg" | awk '{print $1}')" || return 1
    [[ "$actual" == "$expected" ]] || { printf 'Pinned signing key differs: %s\n' "$vendor" >&2; return 1; }
  done < <(rpm_key_contracts)
}

bootstrap_rpm_signing_keys() {
  local dir="$1" vendor fingerprint expected url
  verify_rpm_signing_keys "$dir" || return 1
  while IFS='|' read -r vendor fingerprint expected url; do
    rpmkeys --import "$dir/$vendor.gpg" || return 1
  done < <(rpm_key_contracts)
}
