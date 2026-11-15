#!/usr/bin/env bash
# 새 root collector 전용: /ci 읽기전용, /java.tar.gz 읽기전용, /out 빈 전용 출력 mount.
set -euo pipefail
export LC_ALL=C DEBIAN_FRONTEND=noninteractive
[[ $(id -u) == 0 && $(dpkg --print-architecture) == amd64 ]]
. /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 26.04 && $VERSION_CODENAME == resolute ]]
[[ -d /out && -z $(find /out -mindepth 1 -print -quit) ]] || { echo '/out must be empty' >&2; exit 1; }
printf '%s  /java.tar.gz\n' ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 | sha256sum -c -
mkdir -p /out/debs /out/evidence /tmp/runtime-apt/partial
# 캐시 base에 포함된 Ubuntu archive key만 사용합니다. third-party/trusted=yes는 넣지 않습니다.
cat > /tmp/runtime.sources <<'SOURCES'
Types: deb
URIs: https://archive.ubuntu.com/ubuntu/
Suites: resolute resolute-updates
Components: main
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu/
Suites: resolute-security
Components: main
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES
apt_options=(-o Dir::Etc::sourcelist=/tmp/runtime.sources -o Dir::Etc::sourceparts=-
    -o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false
    -o APT::Get::AllowUnauthenticated=false -o APT::Update::Error-Mode=any
    -o Dir::Cache::archives=/tmp/runtime-apt)
apt-get "${apt_options[@]}" update 2>&1 | tee /out/evidence/apt-update.log
cp /tmp/runtime.sources /out/evidence/ubuntu.sources
cp /usr/share/keyrings/ubuntu-archive-keyring.gpg /out/evidence/
find /var/lib/apt/lists -maxdepth 1 -type f \( -name '*InRelease' -o -name '*Packages*' \) -exec cp -- {} /out/evidence/ \;
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /out/evidence/base-packages.tsv
# Docker Desktop bind filesystem의 copy_file_range 실패를 피해서 순차 복사합니다.
dd if=/java.tar.gz of=/out/java.tar.gz bs=1M status=none
cp /ci/Dockerfile.runtime /out/Dockerfile
cp /ci/runtime-verify.sh /out/runtime-verify.sh
printf '%s\n' 'OS=ubuntu-26.04-resolute' 'ARCH=amd64' 'BASE=eclipse-temurin@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec' 'JAVA=21.0.12.1+1' 'TRANSPORT=agent-http' > /out/IDENTITY
cd /out
for package in debs/*.deb; do
  [[ -f "$package" ]] || continue
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$package" "$(dpkg-deb -f "$package" Package)" "$(dpkg-deb -f "$package" Version)" "$(dpkg-deb -f "$package" Architecture)" "$(stat -c %s "$package")" "$(sha256sum "$package" | cut -d' ' -f1)"
done > PACKAGES.tsv
find . -type f ! -name RUNTIME.lock -printf '%P\n' | sort | while IFS= read -r path; do sha256sum "$path"; done > RUNTIME.lock
pin=$(sha256sum RUNTIME.lock | cut -d' ' -f1)
bash /ci/runtime-verify.sh /out "$pin"
printf 'RUNTIME_LOCK_SHA256=%s\n' "$pin"
