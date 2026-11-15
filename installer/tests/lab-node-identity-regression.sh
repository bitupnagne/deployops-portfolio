#!/usr/bin/env bash
# 자기 tmpfs fixture만 사용하며 실제 ssh-keygen으로 첫 생성·보존·거부 계약을 검증한다.
set -euo pipefail
umask 077
helper="${1:?identity helper required}"
scratch="${2:?dedicated empty scratch directory required}"
[[ $(id -u) == 0 && "$scratch" == /scratch && -d "$scratch" && ! -L "$scratch" ]] || exit 2
[[ -z "$(find "$scratch" -mindepth 1 -print -quit)" ]] || exit 2
fail(){ printf 'FAIL: %s\n' "$*" >&2; exit 1; }
fresh(){ mkdir -m 700 -- "$scratch/$1"; }
verify(){ bash "$helper" --root "$scratch/$1" --node-id test-node "${@:2}" > /scratch/helper.log 2>&1; }
hashes(){
  (cd "$scratch/$1"; find . -type f -print0 | sort -z | xargs -0 -r sha256sum) | sha256sum | awk '{print $1}'
}
reject(){
  local case_name="$1" before after; shift
  before=$(hashes "$case_name")
  if verify "$case_name" "$@"; then fail "$case_name unexpectedly accepted"; fi
  after=$(hashes "$case_name")
  [[ "$before" == "$after" ]] || fail "$case_name changed file bytes on rejection"
  printf 'PASS rejection: %s\n' "$case_name"
}
clone(){ fresh "$1"; cp -a -- "$scratch/node-a/." "$scratch/$1/"; }
fingerprints(){ awk -F '\t' 'NR > 2 {print $1 " " $4}' "$scratch/$1/var/lib/officesim-host-identity/identity"; }

fresh node-a; verify node-a --fresh-node
fresh node-b; verify node-b --fresh-node
for type in rsa ecdsa ed25519; do
  first=$(fingerprints node-a | awk -v key="$type" '$1==key{print $2}')
  second=$(fingerprints node-b | awk -v key="$type" '$1==key{print $2}')
  [[ -n "$first" && -n "$second" && "$first" != "$second" ]] || fail "fresh $type keys not unique"
done
printf 'PASS: three real host key types unique across fresh nodes\n'
before=$(hashes node-a)
verify node-a; verify node-a --fresh-node
[[ "$before" == "$(hashes node-a)" ]] || fail 'restart rotated keys/markers'
printf 'PASS: repeated verification, including fresh flag, preserves completed identity bytes\n'

for name in cached-a cached-b; do
  fresh "$name"; mkdir -p "$scratch/$name/etc/ssh"
  cp -a "$scratch/node-a/etc/ssh/ssh_host_"* "$scratch/$name/etc/ssh/"
  before=$(hashes "$name")
  verify "$name" --fresh-node
  [[ "$before" != "$(hashes "$name")" ]] || fail 'cached keys not replaced'
done
for type in rsa ecdsa ed25519; do
  baseline=$(fingerprints node-a | awk -v key="$type" '$1==key{print $2}')
  first=$(fingerprints cached-a | awk -v key="$type" '$1==key{print $2}')
  second=$(fingerprints cached-b | awk -v key="$type" '$1==key{print $2}')
  [[ "$baseline" != "$first" && "$baseline" != "$second" && "$first" != "$second" ]] || fail "cached $type identity shared"
done
printf 'PASS: identical cached keys replaced independently on explicit fresh creation\n'

fresh no-fresh; reject no-fresh
[[ -z "$(find "$scratch/no-fresh" -mindepth 1 -print -quit)" ]] || fail 'no-fresh created state'
clone missing-private; rm "$scratch/missing-private/etc/ssh/ssh_host_rsa_key"; reject missing-private --fresh-node
clone missing-public; rm "$scratch/missing-public/etc/ssh/ssh_host_rsa_key.pub"; reject missing-public --fresh-node
clone missing-marker; rm "$scratch/missing-marker/var/lib/officesim-host-identity/identity"; reject missing-marker --fresh-node
clone missing-sentinel; rm "$scratch/missing-sentinel/etc/ssh/.officesim-host-identity-v1"; reject missing-sentinel --fresh-node
clone partial; printf 'initializing\n' > "$scratch/partial/var/lib/officesim-host-identity/initializing"; reject partial --fresh-node
clone pending; mkdir -m 700 "$scratch/pending/var/lib/officesim-host-identity/pending"; reject pending --fresh-node
clone partial-link; ln -s /missing-initialization "$scratch/partial-link/var/lib/officesim-host-identity/initializing"; reject partial-link
clone extra-key; cp "$scratch/extra-key/etc/ssh/ssh_host_rsa_key" "$scratch/extra-key/etc/ssh/ssh_host_unexpected_key"; reject extra-key
clone marker-drift; printf 'drift\n' >> "$scratch/marker-drift/var/lib/officesim-host-identity/identity"; reject marker-drift
clone key-mismatch; cp "$scratch/node-b/etc/ssh/ssh_host_rsa_key.pub" "$scratch/key-mismatch/etc/ssh/ssh_host_rsa_key.pub"; reject key-mismatch
clone fingerprint-drift
sed -i 's/SHA256:/SHA256:changed/' "$scratch/fingerprint-drift/var/lib/officesim-host-identity/identity"
cp "$scratch/fingerprint-drift/var/lib/officesim-host-identity/identity" "$scratch/fingerprint-drift/etc/ssh/.officesim-host-identity-v1"
reject fingerprint-drift
clone node-drift
if bash "$helper" --root "$scratch/node-drift" --node-id different-node > /scratch/helper.log 2>&1; then fail 'node identity drift accepted'; fi
clone private-mode; chmod 644 "$scratch/private-mode/etc/ssh/ssh_host_rsa_key"; reject private-mode
clone state-mode; chmod 755 "$scratch/state-mode/var/lib/officesim-host-identity"; reject state-mode
fresh root-mode; chmod 777 "$scratch/root-mode"; reject root-mode --fresh-node
clone locked; mkdir -m 700 "$scratch/locked/var/lib/officesim-host-identity/lock"; reject locked
[[ -d "$scratch/locked/var/lib/officesim-host-identity/lock" ]] || fail 'foreign lock removed'
fresh unsafe-ancestry; mkdir "$scratch/unsafe-ancestry/etc"; ln -s "$scratch/node-a/etc/ssh" "$scratch/unsafe-ancestry/etc/ssh"; reject unsafe-ancestry --fresh-node
fresh sentinel-only; mkdir -p "$scratch/sentinel-only/etc/ssh"; printf x > "$scratch/sentinel-only/etc/ssh/.officesim-host-identity-v1"; reject sentinel-only --fresh-node
printf 'PASS: actual ssh-keygen identity regression; no systemd/services/existing LAB nodes invoked\n'
