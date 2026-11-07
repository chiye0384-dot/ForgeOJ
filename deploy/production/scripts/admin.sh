#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -eu
test "$#" -eq 1
case "$1" in admin-bootstrap|admin-recover) ;; *) exit 2;; esac
export FORGEOJ_ADMIN_CLI_DB_PASSWORD=$(cat /run/secrets/migrator-password)
export FORGEOJ_ADMIN_CLI_DB_USERNAME=forgeoj_migrator
export FORGEOJ_ADMIN_CLI_DB_URL="$SPRING_DATASOURCE_URL"
exec java -cp '/app/classes:/app/lib/*' com.forgeoj.api.ForgeojApiApplication "$1"
