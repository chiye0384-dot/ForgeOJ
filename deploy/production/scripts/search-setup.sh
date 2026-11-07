#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -euo pipefail
umask 077
trap 'rm -f /tmp/search-curl /tmp/search-user.json' EXIT
admin=$(cat /run/secrets/search-admin-password)
password=$(cat /run/secrets/search-password)
[[ "$admin" =~ ^[a-f0-9]{64}$ && "$password" =~ ^[a-f0-9]{64}$ ]] || exit 1
printf 'user = "elastic:%s"\ncacert = "/run/search.crt"\nfail\nsilent\nshow-error\n' "$admin" > /tmp/search-curl
curl --config /tmp/search-curl -X PUT https://search:9200/_security/role/forgeoj_search -H 'Content-Type: application/json' -d '{"cluster":[],"indices":[{"names":["forgeoj-public-*"],"privileges":["manage","read","write","create_index","view_index_metadata"]}]}' -o /dev/null
printf '{"password":"%s","roles":["forgeoj_search"]}' "$password" > /tmp/search-user.json
curl --config /tmp/search-curl -X PUT https://search:9200/_security/user/forgeoj_search -H 'Content-Type: application/json' --data-binary @/tmp/search-user.json -o /dev/null
echo 'Scoped internal search account configured.'
