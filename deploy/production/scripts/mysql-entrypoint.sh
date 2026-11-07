#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -eu
export MYSQL_ROOT_PASSWORD=$(cat /run/secrets/root-password)
exec /entrypoint.sh "$@"
