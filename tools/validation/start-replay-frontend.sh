#!/usr/bin/env bash
# Copyright 2026 池也
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mkdir -p /workspace/ForgeOJ
tar -C /source --exclude='.git' --exclude='target' --exclude='node_modules' \
  --exclude='dist' --exclude='.idea' --exclude='.env' --exclude='.env.*' \
  --exclude='*.local' --exclude='NEXT-CHAT-HANDOFF.md' -cf - . \
  | tar -C /workspace/ForgeOJ -xf -
cd /workspace/ForgeOJ/frontend
npm ci
cp ../tools/validation/replay-vite.mjs ./replay-vite.mjs
exec node replay-vite.mjs
