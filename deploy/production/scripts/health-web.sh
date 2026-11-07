#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -eu
exec curl --fail --silent --max-time 3 --output /dev/null \
  --cacert /run/tls/fullchain.pem --resolve "${FORGEOJ_DOMAIN}:443:127.0.0.1" \
  "https://${FORGEOJ_DOMAIN}/api/v1/auth/session"
