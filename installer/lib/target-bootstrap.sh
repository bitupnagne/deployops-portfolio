#!/usr/bin/env bash
# Compatibility names for older sourced installers. Server access remains owned
# by the company access-control system. Deploy cannot configure SSH.
target_bootstrap_error() { printf '[target-bootstrap][ERROR] %s\n' "$*" >&2; }
target_bootstrap_options_valid() { target_bootstrap_error 'SSH transport retired; register the outbound agent'; return 1; }
target_bootstrap_require_tools() { target_bootstrap_options_valid; }
target_bootstrap_apply() { target_bootstrap_options_valid; }
target_ssh_verify() { target_bootstrap_options_valid; }
target_ssh_configure() { target_bootstrap_options_valid; }
