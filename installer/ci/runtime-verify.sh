#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
fail() { printf 'runtime verify: %s\n' "$*" >&2; exit 1; }
[[ $# == 2 ]] || fail 'usage: runtime-verify.sh CONTEXT EXTERNAL_LOCK_SHA256'
cd -- "$1"
[[ $2 =~ ^[a-f0-9]{64}$ ]] || fail 'external SHA256 required'
[[ -f RUNTIME.lock && ! -L RUNTIME.lock ]] || fail 'missing regular lock'
[[ $(sha256sum RUNTIME.lock | cut -d' ' -f1) == "$2" ]] || fail 'lock pin mismatch'
[[ -z $(find . -type l -print -quit) ]] || fail 'symlink forbidden'
[[ -z $(find . ! -type f ! -type d -print -quit) ]] || fail 'special file forbidden'
declare -A paths=()
while IFS= read -r line; do
    [[ $line =~ ^([a-f0-9]{64})\ \ ([a-zA-Z0-9_./%+:~-]+)$ ]] || fail 'invalid lock line'
    path=${BASH_REMATCH[2]}
    [[ $path != /* && $path != *'..'* && $path != ./* && $path != *//* && $path != RUNTIME.lock ]] || fail 'unsafe path'
    [[ ! -v paths[$path] ]] || fail 'duplicate path'
    paths[$path]=1
done < RUNTIME.lock
for path in java.tar.gz PACKAGES.tsv IDENTITY Dockerfile runtime-verify.sh evidence/ubuntu.sources evidence/ubuntu-archive-keyring.gpg; do
    [[ -v paths[$path] ]] || fail "required input missing: $path"
done
count=0
while IFS= read -r -d '' path; do
    path=${path#./}
    [[ $path == RUNTIME.lock ]] && continue
    [[ -v paths[$path] ]] || fail "unlocked file: $path"
    count=$((count + 1))
done < <(find . -type f -print0)
[[ $count == ${#paths[@]} ]] || fail 'missing input'
sha256sum --strict -c RUNTIME.lock >/dev/null || fail 'input hash mismatch'
grep -Fx 'OS=ubuntu-26.04-resolute' IDENTITY >/dev/null || fail 'wrong OS identity'
grep -Fx 'ARCH=amd64' IDENTITY >/dev/null || fail 'wrong architecture'
grep -Fx 'BASE=eclipse-temurin@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec' IDENTITY >/dev/null || fail 'wrong base'
[[ $(sha256sum java.tar.gz | cut -d' ' -f1) == ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 ]] || fail 'unapproved Java archive'
declare -A packages=()
ssh_seen=0
while IFS=$'\t' read -r path name version arch size sha extra; do
    [[ -z ${extra:-} && $path == debs/*.deb && -v paths[$path] && $size =~ ^[1-9][0-9]*$ && $sha =~ ^[a-f0-9]{64}$ ]] || fail 'invalid package row'
    [[ ! -v packages[$path] ]] || fail 'duplicate package'
    packages[$path]=1
    [[ $(stat -c %s "$path") == "$size" && $(sha256sum "$path" | cut -d' ' -f1) == "$sha" ]] || fail 'package bytes mismatch'
    [[ $(dpkg-deb -f "$path" Package) == "$name" && $(dpkg-deb -f "$path" Version) == "$version" && $(dpkg-deb -f "$path" Architecture) == "$arch" ]] || fail 'package metadata mismatch'
    [[ $arch == amd64 || $arch == all ]] || fail 'package architecture mismatch'
    if [[ $name == openssh-client ]]; then
        [[ $version == 1:10.2p1-2ubuntu3.6 ]] || fail 'unapproved openssh version'
        ssh_seen=$((ssh_seen + 1))
    fi
done < PACKAGES.tsv
[[ $ssh_seen -le 1 ]] || fail 'duplicate legacy openssh-client entry'
for path in "${!paths[@]}"; do
    [[ $path != debs/* || -v packages[$path] ]] || fail 'unlisted package'
done
printf 'runtime context verified: %s packages; external pin %s\n' "${#packages[@]}" "$2"
