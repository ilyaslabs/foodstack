#!/usr/bin/env bash
# Health of every service, and disk usage of the install directory.
source "$(dirname "$0")/lib/common.sh"
require_install_dir
compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
usage=$(df -P "$FOODSTACK_HOME" | awk 'NR==2 {gsub("%", "", $5); print $5}')
echo "Disk used: ${usage}% of the volume holding $FOODSTACK_HOME"
(( usage < 80 )) || warn "Disk usage is above 80%."
