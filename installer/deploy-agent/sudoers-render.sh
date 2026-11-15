#!/usr/bin/env bash
# Shared exact renderer for install-time sudoers and component drift detection.

render_deploy_agent_sudoers() {
  local template="$1" type="$2" read_enabled="$3" deploy_user="$4" read_user="$5"
  local commands=() command_list read_rule=""

  [[ "$type" == was || "$type" == web || "$type" == both ]] || return 2
  [[ "$read_enabled" == 0 || "$read_enabled" == 1 ]] || return 2
  [[ "$deploy_user" =~ ^[a-z_][a-z0-9_-]*$ && "$read_user" =~ ^[a-z_][a-z0-9_-]*$ ]] || return 2

  [[ "$type" == was || "$type" == both ]] && commands+=(/usr/local/deploy/bin/deploy-was-app.sh)
  if [[ "$type" == web || "$type" == both ]]; then
    commands+=(
      /usr/local/deploy/bin/deploy-web-app.sh
      /usr/local/deploy/bin/deploy-web-static.sh
      /usr/local/deploy/bin/deploy-web-proxy.sh
      /usr/local/deploy/bin/deploy-nginx-policy.sh
    )
  fi
  commands+=(/usr/local/deploy/bin/deploy-service-control.sh /usr/local/deploy/bin/deploy-runtime.sh)
  commands+=(/usr/local/deploy/bin/deploy-storage.sh)
  printf -v command_list '%s, ' "${commands[@]}"
  command_list="${command_list%, }"
  if [[ "$read_enabled" == 1 ]]; then
    read_rule="$read_user ALL=(root) NOPASSWD: /usr/local/deploy/bin/deploy-read.sh"
  fi

  sed \
    -e "s|__DEPLOY_USER__|$deploy_user|g" \
    -e "s|__READ_USER__|$read_user|g" \
    -e "s|__DEPLOY_COMMANDS__|$command_list|g" \
    -e "s|__READ_SUDOERS_RULE__|$read_rule|g" \
    "$template"
}
