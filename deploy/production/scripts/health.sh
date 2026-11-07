#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# The token goes only to curl's stdin, never its argv or Docker health output.
set -eu
test "$#" -eq 1
case "$1" in api) port=9091;; worker) port=9092;; *) exit 2;; esac
token=$(cat /run/secrets/forgeoj.monitoring.token)
test -n "$token"
printf 'header = "Authorization: Bearer %s"\n' "$token" |
  curl --config - --fail --silent --max-time 2 --output /dev/null "http://127.0.0.1:$port/health/ready"
