#!/usr/bin/env bash
# No immediate backup, new key, Docker group grant, or storage reset during installation.
deploy_system_install_nexus_backup_broker() (
  set +x
  local src="$SCRIPT_DIR/nexus-backup" dest=/usr/local/lib/deployops/nexus-backup
  local base=/var/lib/deployops/nexus-backup config=/etc/deployops/nexus-backup.json
  [[ -x /usr/libexec/platform-python ]] || { err "Nexus 백업 broker에 Rocky platform-python이 필요합니다."; return 1; }
  [[ "$DEPLOY_APP_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || return 1
  local check
  for check in /usr/local/lib/deployops "$dest" /var/lib/deployops "$base" \
      "$base/inbox" "$base/results" "$base/state" "$base/state/checks" /etc/deployops /var/backups/deployops-nexus; do
    [[ ! -L "$check" ]] || return 1
    [[ "$(readlink -m -- "$check")" == "$check" ]] || return 1
    if [[ -e "$check" ]]; then [[ -d "$check" ]] || return 1; fi
  done
  if [[ -e /etc/deployops ]]; then
    [[ "$(stat -c '%u' /etc/deployops)" == 0 ]] || return 1
  else
    install -d -o root -g root -m 0755 /etc/deployops || return 1
  fi
  install -d -o root -g root -m 0755 "$dest" "$base" || return 1
  install -d -o "$DEPLOY_APP_USER" -g "$DEPLOY_APP_USER" -m 0700 "$base/inbox" || return 1
  install -d -o root -g "$DEPLOY_APP_USER" -m 0750 "$base/results" || return 1
  install -d -o root -g root -m 0700 "$base/state" "$base/state/checks" /var/backups/deployops-nexus || return 1
  install -o root -g root -m 0600 "$src/nexus-local-backup-restore.py" "$dest/nexus-local-backup-restore.py" || return 1
  install -o root -g root -m 0600 "$src/nexus-isolated-restore-worker.py" "$dest/nexus-isolated-restore-worker.py" || return 1
  install -o root -g root -m 0600 "$src/nexus-promotion-worker.py" "$dest/nexus-promotion-worker.py" || return 1
  install -d -o root -g root -m 0700 "$base/state/artifact-baselines" || return 1
  install -o root -g root -m 0600 "$src/nexus-backup-broker.py" "$dest/nexus-backup-broker.py" || return 1
  install -o root -g root -m 0644 "$src/deployops-nexus-backup.service" /etc/systemd/system/deployops-nexus-backup.service || return 1
  install -o root -g root -m 0644 "$src/deployops-nexus-backup.timer" /etc/systemd/system/deployops-nexus-backup.timer || return 1
  # Operator must explicitly verify H2/all local blob paths and coordinate every external writer.
  # Existing administrator config is preserved byte-for-byte on reinstall.
  if [[ ! -e "$config" && ! -L "$config" ]]; then
    (umask 077; set -o noclobber; cat > "$config" <<EOF
{"schema":1,"containerName":"worksystem-nexus","imageId":"sha256:617de9147a594bd1fb8d942cd56f1135ebb61cc622566269907e178377b3fdbd","volume":"nexus-data","keyFile":"/etc/deployops/nexus-secrets/secrets.json","heap":"1g","backupRoot":"/var/backups/deployops-nexus","appUser":"$DEPLOY_APP_USER","externalWritersManaged":false,"allStorageInDataVolume":false}
EOF
    ) || return 1
    chown root:root "$config" && chmod 0600 "$config" || return 1
  else
    [[ -f "$config" && ! -L "$config" && "$(stat -c '%u:%g:%a' "$config")" == 0:0:600 ]] || return 1
  fi
  # Root-selected isolation ports; existing root settings and baseline receipts remain unchanged.
  local restore_config=/etc/deployops/nexus-restore.json
  local restore_enabled="${NEXUS_ISOLATED_RESTORE_ENABLED:-false}"
  [[ "$restore_enabled" == true || "$restore_enabled" == false ]] || return 1
  if [[ ! -e "$restore_config" && ! -L "$restore_config" ]]; then
    (umask 077; set -o noclobber; printf '{"schema":1,"enabled":%s,"portBase":18000}\n' "$restore_enabled" > "$restore_config") || return 1
    chown root:root "$restore_config" && chmod 0600 "$restore_config" || return 1
  else
    [[ -f "$restore_config" && ! -L "$restore_config" && "$(stat -c '%u:%g:%a' "$restore_config")" == 0:0:600 ]] || return 1
  fi
  # Safe default; service emits ready=true only after explicit root config and helper validation.
  local ready="$base/results/ready.json" ready_tmp
  [[ ! -L "$ready" ]] || return 1
  ready_tmp="$(mktemp "$base/results/.ready-XXXXXX")" || return 1
  printf '%s\n' '{"schema":1,"ready":false,"restoreReady":false,"externalWritersManaged":false}' > "$ready_tmp" || return 1
  chown root:"$DEPLOY_APP_USER" "$ready_tmp" && chmod 0640 "$ready_tmp" || return 1
  mv -f "$ready_tmp" "$ready" || return 1
  systemctl daemon-reload || return 1
  systemctl enable --now deployops-nexus-backup.timer >/dev/null 2>&1 || return 1
)
