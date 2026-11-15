#!/usr/bin/env bash
# Real noexec mount in a private namespace; uses a tiny Java-version fixture,
# never changes the system JDK or host mount flags.
set -euo pipefail
if [[ "${1:-}" != --inner ]]; then
  [[ $(id -u) == 0 ]] || { echo 'Root/private mount namespace required.' >&2; exit 1; }
  common="$(readlink -f "${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/components/common.sh}")"
  task="$(mktemp -d /var/tmp/java-noexec-regression.XXXXXXXX)"
  chmod 0755 "$task"
  trap '[[ "$task" == /var/tmp/java-noexec-regression.* ]] && rm -rf -- "$task"' EXIT
  mkdir -m 0755 "$task/noexec" "$task/jvm"
  before="$(findmnt -n -o OPTIONS -T /tmp)"
  unshare --mount --propagation private -- bash "$0" --inner "$task" "$common"
  [[ "$(findmnt -n -o OPTIONS -T /tmp)" == "$before" ]]
  echo 'java-noexec-staging-regression: PASS (actual noexec mount, original host mounts/JDK unchanged)'
  exit 0
fi
task="$2"; common="$3"
[[ "$task" == /var/tmp/java-noexec-regression.* && -d "$task/noexec" && -d "$task/jvm" ]]
mount --bind "$task/noexec" "$task/noexec"
mount -o remount,bind,noexec "$task/noexec"
[[ ",$(findmnt -n -o OPTIONS -T "$task/noexec")," == *,noexec,* ]]
export TMPDIR="$task/noexec"
JAVA_HOME_DIR="$task/jvm/temurin-21"
. "$common"
JAVA_PROFILE="$task/java-profile.sh"
info(){ :; }; success(){ :; }; err(){ printf '%s\n' "$*" >&2; }
BOM_JAVA_ARCHIVE=java.tar.gz
OFFLINE_VERIFY_AUTHENTICITY=0
bundle_file(){ printf '%s\n' "$task/java.tar.gz"; }
mkdir -p "$task/archive/jdk/bin"
cat > "$task/archive/jdk/bin/java" <<EOF
#!/bin/sh
case "\$0" in
  */.temurin21-install.*/*) stat -c '%u:%a' "\$(dirname "\$(dirname "\$(dirname "\$0")")")" >> "$task/staging-modes";;
esac
printf 'openjdk version "21.0.12"\\n'
EOF
chmod 0755 "$task/archive/jdk/bin/java"
tar -czf "$task/java.tar.gz" -C "$task/archive" jdk
# Reproduce the original failure: implicit mktemp chooses noexec TMPDIR.
old_stage="$(mktemp -d)"
tar -xzf "$task/java.tar.gz" -C "$old_stage"
if "$old_stage/jdk/bin/java" -version > "$task/noexec-result" 2>&1; then
  echo 'Fixture failed to enforce noexec.' >&2; exit 1
fi
rm -rf -- "$old_stage"
java_install
[[ -x "$JAVA_HOME_DIR/bin/java" && -f "$JAVA_PROFILE" && $(java_status) == installed ]]
grep -qx '0:700' "$task/staging-modes"
[[ "$(find "$task/jvm" -maxdepth 1 -name '.temurin21-install.*' | wc -l)" == 0 ]]
"$JAVA_HOME_DIR/bin/java" -version | grep -q 'version "21'
echo 'PASS fresh install ignores noexec TMPDIR and validates inside root-only destination staging'
printf 'keep-original\n' > "$JAVA_HOME_DIR/existing.marker"
sed -i 's/21.0.12/17.0.12/' "$task/archive/jdk/bin/java"
tar -czf "$task/java.tar.gz" -C "$task/archive" jdk
if java_install force; then echo 'Wrong-version replacement accepted.' >&2; exit 1; fi
[[ $(cat "$JAVA_HOME_DIR/existing.marker") == keep-original ]]
"$JAVA_HOME_DIR/bin/java" -version | grep -q 'version "21'
[[ "$(find "$task/jvm" -maxdepth 1 -name '.temurin21-install.*' | wc -l)" == 0 ]]
echo 'PASS wrong candidate preserves existing JDK and cleans destination staging'
