#!/bin/bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
test "$#" -eq 1
[[ "$1" =~ ^[a-z0-9][a-z0-9.-]{0,63}$ ]] || exit 2
for component in api worker web; do
  docker build --platform linux/amd64 -f "Dockerfile.$component" -t "forgeoj-$component:$1" .
  printf '%s_IMAGE=' "FORGEOJ_${component^^}"
  docker image inspect --format '{{.Id}}' "forgeoj-$component:$1"
done
