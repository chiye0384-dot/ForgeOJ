#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -eu
umask 077
secret=$(cat /run/secrets/rabbit-password)
test "${#secret}" -eq 64
case "$secret" in *[!a-f0-9]*) exit 1;; esac
printf 'default_user = forgeoj\ndefault_pass = %s\ndefault_vhost = /forgeoj\n' "$secret" > /tmp/rabbitmq.conf
export RABBITMQ_CONFIG_FILE=/tmp/rabbitmq
exec docker-entrypoint.sh rabbitmq-server
