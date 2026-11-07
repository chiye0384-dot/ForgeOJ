#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# ES enforces password-file modes; Compose bind-mount modes cannot be remapped.
set -euo pipefail
umask 077
cat /run/secrets/search-admin-password > /tmp/elastic-password
chmod 0400 /tmp/elastic-password
export ELASTIC_PASSWORD_FILE=/tmp/elastic-password
exec /usr/local/bin/docker-entrypoint.sh eswrapper
