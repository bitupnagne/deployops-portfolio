#!/usr/bin/env bash
# Real package-routing functions; mocked DNF/RPM. No installs or network calls.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$root/lib/os.sh"
. "$root/components/nginx.sh"
task_tmp="$(mktemp -d)"; trap 'rm -rf "$task_tmp"' EXIT
for group in nginx target-prerequisites docker; do
  mkdir -p "$task_tmp/$group/repodata"
  printf fixture > "$task_tmp/$group/repodata/repomd.xml"
  (cd "$task_tmp/$group" && sha256sum repodata/repomd.xml > REPODATA.sha256)
done
printf 'nginx|1:1.30.3-1.el8.ngx|x86_64|%064d|nginx.rpm\n' 0 > "$task_tmp/nginx/PACKAGES.lock"
printf 'procps-ng|0:3.3.15-14.el8|x86_64|%064d|procps-ng.rpm\n' 0 > "$task_tmp/target-prerequisites/PACKAGES.lock"
printf 'openssl|1:1.1.1k-17.el8_10|x86_64|%064d|openssl.rpm\n' 0 >> "$task_tmp/target-prerequisites/PACKAGES.lock"
printf fixture > "$task_tmp/nginx/nginx.rpm"
for package in docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin; do
  printf 'docker-fixture' > "$task_tmp/docker/$package.rpm"
  printf '%s|0:1.0-1|x86_64|%064d|%s.rpm\n' "$package" 0 "$package" >> "$task_tmp/docker/PACKAGES.lock"
done
bootstrap_rpm_signing_keys() { :; }
PKG=mock_dnf; OS_FAMILY=rhel
mock_dnf() { printf '%s\n' "$@" > "$task_tmp/args"; return "${solver_result:-0}"; }
rpm() {
  if [[ "${!#}" == openssl ]]; then
    printf '%s' "${mock_openssl_reply:-1:1.1.1k-17.el8_10|x86_64}"
  else
    printf '%s' "${mock_rpm_reply:-1:1.30.3-1.el8.ngx|x86_64}"
  fi
}
export OFFLINE_VERIFY_AUTHENTICITY=1

install_local_packages "$task_tmp/nginx"
for arg in '--disablerepo=*' "--repofrompath=offline-bundle,file://$task_tmp/nginx" \
  "--repofrompath=offline-prerequisites,file://$task_tmp/target-prerequisites" \
  '--enablerepo=offline-prerequisites' '--setopt=offline-prerequisites.module_hotfixes=true' \
  '--setopt=offline-prerequisites.gpgcheck=1' '--setopt=offline-prerequisites.skip_if_unavailable=false'; do
  grep -Fx -- "$arg" "$task_tmp/args" >/dev/null
done
[[ "$(sed -n '/^install$/,$p' "$task_tmp/args" | tail -n +2)" == $'nginx-1:1.30.3-1.el8.ngx.x86_64\nopenssl-1:1.1.1k-17.el8_10.x86_64' ]]
if grep -E 'https?://|procps-ng|allowerasing|skip-broken|nobest|nodeps' "$task_tmp/args"; then exit 1; fi

OFFLINE_VERIFY_AUTHENTICITY=0 install_local_packages "$task_tmp/nginx"
grep -Fx -- '--setopt=offline-prerequisites.gpgcheck=0' "$task_tmp/args" >/dev/null
mock_openssl_reply='1:1.1.1k-12.el8_9|x86_64'
if install_local_packages "$task_tmp/nginx"; then echo 'Wrong installed OpenSSL accepted' >&2; exit 1; fi
unset mock_openssl_reply
cp "$task_tmp/target-prerequisites/PACKAGES.lock" "$task_tmp/complete.lock"
sed '/^openssl|/d' "$task_tmp/complete.lock" > "$task_tmp/target-prerequisites/PACKAGES.lock"
rm "$task_tmp/args"
if install_local_packages "$task_tmp/nginx"; then echo 'Missing OpenSSL lock was accepted' >&2; exit 1; fi
[[ ! -e "$task_tmp/args" ]]
cp "$task_tmp/complete.lock" "$task_tmp/target-prerequisites/PACKAGES.lock"
grep '^openssl|' "$task_tmp/complete.lock" >> "$task_tmp/target-prerequisites/PACKAGES.lock"
if install_local_packages "$task_tmp/nginx"; then echo 'Duplicate OpenSSL lock was accepted' >&2; exit 1; fi
[[ ! -e "$task_tmp/args" ]]
cp "$task_tmp/complete.lock" "$task_tmp/target-prerequisites/PACKAGES.lock"
solver_result=1
if install_local_packages "$task_tmp/nginx"; then echo 'Failed offline solver was ignored' >&2; exit 1; fi
unset solver_result
rm "$task_tmp/args"
mv "$task_tmp/target-prerequisites/PACKAGES.lock" "$task_tmp/prerequisites.lock"
if install_local_packages "$task_tmp/nginx"; then echo 'Missing dependency lock was accepted' >&2; exit 1; fi
[[ ! -e "$task_tmp/args" ]]
mv "$task_tmp/prerequisites.lock" "$task_tmp/target-prerequisites/PACKAGES.lock"
printf tamper >> "$task_tmp/target-prerequisites/repodata/repomd.xml"
if install_local_packages "$task_tmp/nginx"; then echo 'Invalid dependency metadata was accepted' >&2; exit 1; fi
[[ ! -e "$task_tmp/args" ]]

# The new dependency-repo exposure is limited to Nginx; Docker stays unchanged.
mock_rpm_reply='0:1.0-1|x86_64'
install_local_packages "$task_tmp/docker"
if grep -q 'offline-prerequisites' "$task_tmp/args"; then exit 1; fi

# A missing bundle or failed bundled install must never try an online fallback.
info() { :; }; err() { :; }
bundle_dir() { return 1; }
bundle_has_packages() { return 1; }
apt-get() { echo online-apt >> "$task_tmp/forbidden"; return 1; }
mock_dnf() { echo online-dnf >> "$task_tmp/forbidden"; return 1; }
for OS_FAMILY in rhel debian; do
  if _nginx_install_packages; then echo 'Missing offline bundle was accepted' >&2; exit 1; fi
done
[[ ! -e "$task_tmp/forbidden" ]]
bundle_dir() { printf '%s' "$task_tmp/nginx"; }
bundle_has_packages() { return 0; }
install_local_packages() { printf '%s\n' "$1" >> "$task_tmp/delegated"; return "${local_result:-0}"; }
openssl() { :; } # This component-routing fixture has the CLI; missing CLI is tested separately.
_nginx_install_packages
local_result=1
if _nginx_install_packages; then echo 'Bundled install failure was ignored' >&2; exit 1; fi
[[ "$(wc -l < "$task_tmp/delegated")" == 2 && ! -e "$task_tmp/forbidden" ]]
echo 'nginx offline dependency routing: PASS (exact Nginx+OpenSSL requests, shared local deps, installed EVR check, missing/duplicate lock refusal, auth modes, solver/missing/invalid refusal, Docker unchanged, no online fallback)'
