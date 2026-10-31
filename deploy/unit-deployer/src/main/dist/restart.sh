#!/usr/bin/env bash
# Restarts everything, or one service: ./restart.sh server
source "$(dirname "$0")/lib/common.sh"
require_install_dir
if (( $# )); then compose up -d --force-recreate "$@"; else compose down && compose up -d; fi
