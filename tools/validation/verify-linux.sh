#!/usr/bin/env bash
# Copyright 2026 池也
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# /source is read-only; /artifacts is a new, ignored host directory.
# Never import host build products, caches or local secrets into the test copy.
test -f /source/pom.xml
test -d /artifacts
test ! -e /workspace/ForgeOJ
mkdir -p /workspace/ForgeOJ
tar -C /source --exclude='.git' --exclude='target' --exclude='node_modules' \
    --exclude='dist' --exclude='.idea' --exclude='.env' --exclude='.env.*' \
    --exclude='*.local' --exclude='NEXT-CHAT-HANDOFF.md' --exclude='docs/M2-NEXT-CHAT-HANDOFF.md' -cf - . \
    | tar -C /workspace/ForgeOJ -xf -
cd /workspace/ForgeOJ

# A stable hash manifest ties the dirty/committed source snapshot to this run.
snapshot_manifest="/artifacts/source-files.${1:-unknown}.sha256"
find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > "$snapshot_manifest"
# All uses two isolated copies. Never replace the backend manifest with a later frontend copy.
if test ! -e /artifacts/source-files.sha256; then cp "$snapshot_manifest" /artifacts/source-files.sha256; fi
sha256sum /artifacts/source-files.sha256
uname -a

collect_backend() {
    local module
    for module in forgeoj-api forgeoj-judge-worker; do
        mkdir -p "/artifacts/$module"
        if test -d "$module/target/surefire-reports"; then
            cp -a "$module/target/surefire-reports" "/artifacts/$module/"
        fi
        if test -f "$module/target/$module-0.0.1-SNAPSHOT.jar"; then
            cp "$module/target/$module-0.0.1-SNAPSHOT.jar" "/artifacts/$module/"
            sha256sum "/artifacts/$module/$module-0.0.1-SNAPSHOT.jar"
        fi
    done
}

case "${1:-}" in
    backend|api)
        trap collect_backend EXIT
        java -version
        mvn --version
        docker --version
        # The pinned image supplies exactly Maven 3.9.14. No host .m2 mount.
        # Builder and disposable MySQL containers share the default bridge. Avoid Desktop host forwarding.
        # This flag is read only by test sources; production JARs contain no direct-route adapter.
        export FORGEOJ_TEST_DIRECT_DB=1
        module_args=()
        if test "$1" = api; then module_args=(-pl forgeoj-api -am); fi
        mvn --settings /source/tools/validation/maven-central-settings.xml \
            --batch-mode --no-transfer-progress "${module_args[@]}" clean verify 2>&1 | tee /artifacts/backend.log
        ;;
    frontend)
        cd frontend
        node --version
        npm --version
        npm ci 2>&1 | tee /artifacts/npm-ci.log
        npm run verify 2>&1 | tee /artifacts/frontend.log
        cp -a dist /artifacts/frontend-dist
        ;;
    *) printf 'Usage: verify-linux.sh backend|api|frontend\n' >&2; exit 2 ;;
esac
