#!/usr/bin/env bash
# Verify SQL handed to the client. Does not connect to MariaDB or print credentials.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/components/common.sh"
err() { :; }
calls=0
db_root_exec() { calls=$((calls+1)); captured_sql="$1"; }
password='quote'\''back\slash";&$fixture'
db_ensure_schema_and_user fixture_schema fixture_user "$password" unused-root
[[ "$calls" == 1 ]]
[[ "$captured_sql" == "SET SESSION sql_mode=CONCAT_WS(',', @@SESSION.sql_mode, 'NO_BACKSLASH_ESCAPES');"$'\n'* ]]
expected='IDENTIFIED BY '\''quote'\'''\''back\slash";&$fixture'\'';'
[[ "$(printf '%s\n' "$captured_sql" | grep -Fc "$expected")" == 4 ]]
[[ "$captured_sql" != *'SET GLOBAL'* ]]
before="$calls"
if db_ensure_schema_and_user 'bad`schema' fixture_user test unused-root; then exit 1; fi
if db_ensure_schema_and_user fixture_schema "bad'user" test unused-root; then exit 1; fi
if db_ensure_schema_and_user fixture_schema fixture_user $'bad\npassword' unused-root; then exit 1; fi
[[ "$calls" == "$before" ]]
printf 'PASS: provisioning preserves literal apostrophe/backslash/shell characters and rejects malformed identifiers before SQL execution\n'

temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
reloads=0
mktemp() { builtin printf '%s' "$temp/unit-stage"; }
chmod() { [[ "${chmod_failure:-0}" == 0 ]]; }
mv() { [[ "${publish_failure:-0}" == 0 ]]; }
systemctl() { reloads=$((reloads+1)); return 0; }
chmod_failure=1
if write_systemd_service fixture 'fixture unit'; then exit 1; fi
[[ "$reloads" == 0 ]]
chmod_failure=0; publish_failure=1
if write_systemd_service fixture 'fixture unit'; then exit 1; fi
[[ "$reloads" == 0 ]]
publish_failure=0
write_systemd_service fixture 'fixture unit'
[[ "$reloads" == 1 ]]
printf 'PASS: failed unit permission/publish returns failure before daemon reload; successful publication reloads once\n'
