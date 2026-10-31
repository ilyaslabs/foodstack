#!/usr/bin/env bash
# Starts every service (data is kept between restarts).
source "$(dirname "$0")/lib/common.sh"
require_install_dir
compose up -d "$@"
