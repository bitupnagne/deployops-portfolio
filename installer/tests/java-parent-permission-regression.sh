#!/usr/bin/env bash
# Real Linux filesystem/ELF regression; all Java writes stay in a private mount namespace.
set -euo pipefail

if [[ "${1:-}" != --inner ]]; then
  [[ $(id -u) == 0 ]] || { echo 'This regression requires root and a private mount namespace.' >&2; exit 1; }
  common="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/components/common.sh}"
  common="$(readlink -f "$common")"
  task_tmp="$(mktemp -d /var/tmp/java-parent-regression.XXXXXXXX)"
  chmod 0755 "$task_tmp"
  trap 'rm -rf -- "$task_tmp"' EXIT
  before=absent
  [[ ! -e /usr/lib/jvm && ! -L /usr/lib/jvm ]] || before="$(stat -c '%d:%i:%u:%g:%a:%F' /usr/lib/jvm)"
  mkdir -m 0755 "$task_tmp/usr-lib"
  set +e
  unshare --mount --propagation private -- bash "$0" --inner "$task_tmp" "$common"
  result=$?
  set -e
  after=absent
  [[ ! -e /usr/lib/jvm && ! -L /usr/lib/jvm ]] || after="$(stat -c '%d:%i:%u:%g:%a:%F' /usr/lib/jvm)"
  [[ "$before" == "$after" ]] || { echo 'Outside Java parent metadata changed.' >&2; exit 1; }
  (( result == 0 )) || exit "$result"
  echo 'PASS: private-namespace Java parent regression; outside metadata unchanged.'
  exit 0
fi

task_tmp="$2"
common="$3"
[[ "$task_tmp" == /var/tmp/java-parent-regression.* && -d "$task_tmp/usr-lib" ]]
mount --bind "$task_tmp/usr-lib" /usr/lib
[[ "$(stat -c '%d:%i' /usr/lib)" == "$(stat -c '%d:%i' "$task_tmp/usr-lib")" ]]
getent passwd nobody >/dev/null
unset JAVA_HOME_DIR
. "$common"
JAVA_PROFILE="$task_tmp/java-profile.sh"
info() { :; }
success() { :; }
err() { printf '%s\n' "$*" >&2; }
bundle_file() {
  [[ "${BLOCK_BUNDLE:-n}" == n ]] || { echo 'Unexpected archive access during parent repair.' >&2; return 1; }
  printf '%s\n' "$task_tmp/java.tar.gz"
}
mkdir -p "$task_tmp/archive/jdk/bin"
cp /bin/true "$task_tmp/archive/jdk/bin/java"
chmod 0755 "$task_tmp/archive" "$task_tmp/archive/jdk" "$task_tmp/archive/jdk/bin" "$task_tmp/archive/jdk/bin/java"
tar -czf "$task_tmp/java.tar.gz" -C "$task_tmp/archive" jdk
BOM_JAVA_ARCHIVE=java.tar.gz
BOM_JAVA_SHA256="$(sha256sum "$task_tmp/java.tar.gz" | awk '{print $1}')"

umask 077
[[ ! -e /usr/lib/jvm ]]
java_install
printf 'Fresh standard parent mode: %s\n' "$(stat -c %a /usr/lib/jvm)"
runuser -u nobody -- /usr/lib/jvm/temurin-21/bin/java -version
[[ $(stat -c %a /usr/lib/jvm) == 755 && $(java_status) == installed ]]
echo 'PASS: actual default-path fresh install under umask077 is executable by a service user.'

chmod 0700 /usr/lib/jvm
[[ $(java_status) == drift ]]
BLOCK_BUNDLE=y
java_install
[[ $(stat -c %a /usr/lib/jvm) == 755 && $(java_status) == installed ]]
runuser -u nobody -- /usr/lib/jvm/temurin-21/bin/java -version
java_install
echo 'PASS: existing0700 parent is repaired idempotently without force or archive access.'

mkdir -p "$task_tmp/private/jvm"
chmod 0700 "$task_tmp/private"
chmod 0755 "$task_tmp/private/jvm"
JAVA_HOME_DIR="$task_tmp/private/jvm/jdk"
if java_install; then echo 'Unsafe custom ancestor was accepted.' >&2; exit 1; fi
[[ $(stat -c %a "$task_tmp/private") == 700 && ! -e "$JAVA_HOME_DIR" ]]
echo 'PASS: custom private ancestor is rejected without changing its permissions.'

mkdir -m 0755 "$task_tmp/custom-parent"
JAVA_HOME_DIR="$task_tmp/custom-parent/jdk"
BLOCK_BUNDLE=n
java_install
[[ $(stat -c %a "$task_tmp/custom-parent") == 755 && $(java_status) == installed ]]
runuser -u nobody -- "$JAVA_HOME_DIR/bin/java" -version
echo 'PASS: existing safe custom parent remains supported.'

JAVA_HOME_DIR=/usr/lib/jvm/temurin-21
mv /usr/lib/jvm /usr/lib/jvm-real
chmod 0700 /usr/lib/jvm-real
ln -s jvm-real /usr/lib/jvm
if java_install; then echo 'Symlink standard parent was accepted.' >&2; exit 1; fi
[[ -L /usr/lib/jvm && $(stat -c %a /usr/lib/jvm-real) == 700 ]]
echo 'PASS: standard-parent symlink is rejected without exposing its target.'
