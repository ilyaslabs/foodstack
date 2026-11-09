#!/usr/bin/env bash
# Mailpit: catches every email; read them at http://localhost:8025.
source "$(dirname "$0")/../lib.sh"
network
run_container fsplay-mailpit "$MAILPIT_IP" -p 127.0.0.1:8025:8025 "$MAILPIT_IMAGE"
wait_for "fsplay-mailpit" 30 docker exec fsplay-mailpit wget -qO- http://localhost:8025/livez
info "Mailpit: SMTP on $MAILPIT_IP:1025, emails at http://localhost:8025"
