#!/usr/bin/env bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Sourced by Oracle's entrypoint: strict mode is scoped to a subshell.
(
set -euo pipefail
for role in migrator api worker; do
  value=$(cat "/run/secrets/${role}-password")
  [[ "$value" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid DB secret file' >&2; exit 1; }
  case "$role" in
    migrator) export FORGEOJ_MIGRATOR_PASSWORD="$value" ;;
    api) export FORGEOJ_API_DB_PASSWORD="$value" ;;
    worker) export FORGEOJ_WORKER_DB_PASSWORD="$value" ;;
  esac
done
source /opt/forgeoj/create-users.sh
)
