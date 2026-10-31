#!/usr/bin/env bash
# Stops every service, or the ones named. Data is kept.
source "$(dirname "$0")/lib/common.sh"
require_install_dir
if (( $# )); then compose stop "$@"; else compose down; fi
