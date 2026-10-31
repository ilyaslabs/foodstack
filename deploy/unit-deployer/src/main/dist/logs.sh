#!/usr/bin/env bash
# Follows the logs of every service, or of the ones named: ./logs.sh server
source "$(dirname "$0")/lib/common.sh"
require_install_dir
compose logs -f --tail 200 "$@"
