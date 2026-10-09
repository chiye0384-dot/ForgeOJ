#!/bin/sh
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Installed only inside the exact owned disposable Worker, after its healthy startup.
# Simulate a temporary engine create error; verification, inspect and cleanup still work.
if [ "$1" = container ] && [ "$2" = create ]; then
    echo 'FORGEOJ_OPERATIONS_DISPOSABLE_CREATE_FAILURE' >&2
    exit 1
fi
exec /tmp/forgeoj-operations-real-docker "$@"
