#!/usr/bin/env bash
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
# Isolated benchmark only. Forward exact stdin/stdout/exit status to the real CLI.
set -u
phase=other
identifier=''
name=''
if [[ ${1:-} == container ]]; then
  phase=${2:-other}
fi
for argument in "$@"; do
  if [[ $argument =~ ^[a-f0-9]{64}$ ]]; then identifier=$argument; fi
  if [[ $argument =~ ^forgeoj-[a-f0-9]{32}-[a-f0-9]{32}$ ]]; then name=$argument; fi
  case "$argument" in
    javac) phase=compile ;;
    java) phase=run ;;
    dd) phase=transfer ;;
  esac
done
temporary=''
if [[ $phase == create ]]; then temporary=$(mktemp); fi
begin=$(date +%s%N)
if [[ -n $temporary ]]; then
  /usr/local/bin/docker "$@" > "$temporary"
  result=$?
else
  /usr/local/bin/docker "$@"
  result=$?
fi
end=$(date +%s%N)
if [[ -n $temporary ]]; then
  created=$(cat "$temporary")
  if [[ $created =~ ^[a-f0-9]{64}$ ]]; then identifier=$created; fi
  cat "$temporary"
  rm -- "$temporary"
fi
# Global inventory probes have no attempt container and are outside this measurement.
if [[ -z $identifier && $phase != create ]]; then exit "$result"; fi
# Every invocation owns a file: concurrent append loses records on Windows shared mounts.
# No source, arguments, environment, credentials or command output are recorded.
record=$(mktemp /measure/command-XXXXXXXXXX.json) || exit 99
printf '{"phase":"%s","containerId":"%s","name":"%s","startNs":%s,"durationNs":%s,"exitCode":%s}\n' \
  "$phase" "$identifier" "$name" "$begin" "$((end-begin))" "$result" > "$record" || exit 99
exit "$result"
