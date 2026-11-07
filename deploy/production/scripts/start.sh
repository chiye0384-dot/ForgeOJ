#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
test "$#" -ge 1 && test "$#" -le 2
instance=$(realpath "$1")
umask 077
exec 9>"${instance}.start.lock"
flock -n 9 || { echo 'This instance is already being started.' >&2; exit 1; }
mode=()
if test "$#" -eq 2; then test "$2" = --isolated; mode=(--isolated); fi
python3 scripts/preflight.py "$instance" "${mode[@]}"
compose=(docker compose --env-file "$instance" -f compose.yml)
# Explicit maintenance window; do not migrate while old writers are active.
if test -n "$("${compose[@]}" ps -q api worker web)"; then
  echo 'Stop this instance writers explicitly before migration.' >&2; exit 1
fi
"${compose[@]}" up -d --wait --wait-timeout 180 mysql rabbitmq redis search
"${compose[@]}" run --rm --no-deps migrate
"${compose[@]}" run --rm --no-deps search-setup
"${compose[@]}" up -d --wait --wait-timeout 180 api worker web prometheus grafana
echo 'All configured readiness checks passed. Complete the documented business and release gates before opening access.'
