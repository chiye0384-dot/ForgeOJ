#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -euo pipefail
test "$#" -eq 2
private=$(realpath "$1")
image="$2"
[[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || exit 2
test ! -e "$private/search/search.key" || { echo 'Existing search TLS must not be overwritten' >&2; exit 1; }
umask 077
openssl req -x509 -newkey rsa:3072 -nodes -days 365 -subj /CN=search -addext 'subjectAltName=DNS:search,DNS:localhost' -keyout "$private/search/search.key" -out "$private/search/search.crt" 2>/dev/null
# Public-only truststore; changeit protects no secret. Never put search.key in API.
docker run --rm --network none --user 0:0 --read-only --cap-drop ALL --security-opt no-new-privileges --label com.forgeoj.deployment-helper=search-tls --mount "type=bind,src=$private/search,dst=/work" --entrypoint keytool "$image" -importcert -noprompt -alias forgeoj-search -file /work/search.crt -keystore /work/trust.p12 -storetype PKCS12 -storepass changeit
chmod 0444 "$private/search/search.crt" "$private/search/search.key" "$private/search/trust.p12"
echo 'Internal TLS created; calendar renewal is required before certificate expiry.'
