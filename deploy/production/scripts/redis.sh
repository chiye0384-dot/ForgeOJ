#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -eu
umask 077
secret=$(cat /run/secrets/redis-password)
test "${#secret}" -eq 64
case "$secret" in *[!a-f0-9]*) exit 1;; esac
printf 'bind 0.0.0.0\nprotected-mode yes\nrequirepass %s\nmaxmemory 128mb\nmaxmemory-policy noeviction\nsave ""\nappendonly no\n' "$secret" > /tmp/redis.conf
exec redis-server /tmp/redis.conf
