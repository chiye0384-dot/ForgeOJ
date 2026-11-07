#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Shared by preflight and the real POSIX probe; never reads secret contents.
set -euo pipefail
test "$#" -eq 1
private="$1"
for dir in "$private" "$private/secrets" "$private/tls" "$private/search"; do
  test -d "$dir" && test ! -L "$dir"
  test "$(stat -c %a "$dir")" = 700
done
for name in root-password migrator-password api-password worker-password rabbit-password redis-password jwt-secret admin-jwt-secret search-admin-password search-password monitoring-token grafana-password grafana-key smtp-password; do
  file="$private/secrets/$name"
  test -f "$file" && test ! -L "$file"
  case "$(stat -c %a "$file")" in 400|440|444) ;; *) exit 1;; esac
done
for name in tls/fullchain.pem search/search.crt search/search.key search/trust.p12; do
  file="$private/$name"
  test -f "$file" && test ! -L "$file"
  case "$(stat -c %a "$file")" in 400|440|444) ;; *) exit 1;; esac
done
test -f "$private/tls/privkey.pem" && test ! -L "$private/tls/privkey.pem"
case "$(stat -c %a "$private/tls/privkey.pem")" in 400|600) ;; *) exit 1;; esac
