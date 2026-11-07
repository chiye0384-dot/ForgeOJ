#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Generate local secrets once. Does not provision TLS, SMTP, cloud or an account.
set -euo pipefail
test "$#" -eq 1
[[ "$1" == /* ]] || exit 2
test ! -e "$1" || { echo 'Refusing to overwrite private configuration' >&2; exit 1; }
umask 077
mkdir -p "$1/secrets" "$1/tls" "$1/search"
for name in root-password migrator-password api-password worker-password rabbit-password redis-password jwt-secret admin-jwt-secret search-admin-password search-password monitoring-token grafana-password grafana-key; do
  openssl rand -hex 32 | tr -d '\n' > "$1/secrets/$name"
done
# Replace with actual SMTP password before enabling production SMTP.
touch "$1/secrets/smtp-password"
# Compose file secrets are bind mounts: uid/gid remapping is not supported.
# Parent dirs remain 0700 on the host. Containers receive only selected files.
chmod 0444 "$1"/secrets/*
echo 'Private files generated. Supply public TLS and internal search certificates before starting.'
