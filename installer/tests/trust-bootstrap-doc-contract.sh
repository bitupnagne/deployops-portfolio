#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
docs=("$ROOT/installer/README.md" "$ROOT/docs/02-rocky-offline-install.md")

for doc in "${docs[@]}"; do
  [[ -f "$doc" ]] || { printf 'missing trust-bootstrap guidance: %s\n' "$doc" >&2; exit 1; }
  grep -Fq 'INSTALLER-MANIFEST.sha256.sig' "$doc" \
    || { printf 'outer installer signature missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq 'openssl dgst -sha256 -verify' "$doc" \
    || { printf 'host OpenSSL signature verification missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq "sed 's/\\r$//' INSTALLER-MANIFEST.sha256 | sha256sum -c --quiet" "$doc" \
    || { printf 'whole-package hash verification missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq 'openssl pkey -pubin' "$doc" \
    || { printf 'public-key fingerprint command missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq '/media/approved-trust/offline-public.pem' "$doc" \
    || { printf 'out-of-package trusted-key path missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq '심층 방어' "$doc" \
    || { printf 'internal signature gate is not labeled defense in depth in %s\n' "$doc" >&2; exit 1; }
  grep -Fq '부트스트랩' "$doc" \
    || { printf 'internal signature gate is not distinguished from bootstrap trust in %s\n' "$doc" >&2; exit 1; }
  grep -Fq 'root-owned' "$doc" \
    || grep -Fq 'root 소유' "$doc" \
    || { printf 'protected root-owned staging requirement missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq 'group/world' "$doc" \
    || { printf 'group/world write protection missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq '동일한' "$doc" \
    || { printf 'same verified copy requirement missing from %s\n' "$doc" >&2; exit 1; }
  grep -Fq '경쟁 조건' "$doc" \
    || { printf 'root-level mutation race limitation missing from %s\n' "$doc" >&2; exit 1; }
  outer_line="$(grep -nF 'openssl dgst -sha256 -verify' "$doc" | head -1 | cut -d: -f1)"
  hashes_line="$(grep -nF "sed 's/\\r$//' INSTALLER-MANIFEST.sha256 | sha256sum -c --quiet" "$doc" | head -1 | cut -d: -f1)"
  inner_line="$(grep -nF './verify-offline-bundle.sh --trusted-public-key' "$doc" | head -1 | cut -d: -f1)"
  install_line="$(grep -nF 'sudo ./install.sh --' "$doc" | head -1 | cut -d: -f1)"
  [[ -n "$outer_line" && -n "$hashes_line" && -n "$inner_line" && -n "$install_line" \
    && "$outer_line" -lt "$hashes_line" && "$hashes_line" -lt "$inner_line" \
    && "$hashes_line" -lt "$install_line" ]] \
    || { printf 'package trust commands do not precede installer scripts in %s (outer=%s hashes=%s inner=%s install=%s)\n' \
      "$doc" "$outer_line" "$hashes_line" "$inner_line" "$install_line" >&2; exit 1; }
done

printf 'trust-bootstrap-doc-contract: PASS (outer signature, external fingerprint, package hashes precede scripts)\n'
