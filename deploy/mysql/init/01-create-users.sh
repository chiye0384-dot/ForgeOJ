#!/usr/bin/env bash

set -euo pipefail

validate_secret() {
  local name="$1"
  local value="$2"

  if [[ -z "${value}" || "${value}" == *[!A-Za-z0-9_@%+=.,:-]* ]]; then
    echo "${name} must be non-empty and contain only A-Z, a-z, 0-9, or _@%+=.,:-" >&2
    exit 1
  fi
}

validate_secret "MYSQL_ROOT_PASSWORD" "${MYSQL_ROOT_PASSWORD:-}"
validate_secret "FORGEOJ_MIGRATOR_PASSWORD" "${FORGEOJ_MIGRATOR_PASSWORD:-}"
validate_secret "FORGEOJ_API_DB_PASSWORD" "${FORGEOJ_API_DB_PASSWORD:-}"
validate_secret "FORGEOJ_WORKER_DB_PASSWORD" "${FORGEOJ_WORKER_DB_PASSWORD:-}"

mysql --protocol=socket --user=root --password="${MYSQL_ROOT_PASSWORD}" <<-EOSQL
CREATE USER IF NOT EXISTS 'forgeoj_migrator'@'%' IDENTIFIED BY '${FORGEOJ_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'forgeoj_api'@'%' IDENTIFIED BY '${FORGEOJ_API_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'forgeoj_worker'@'%' IDENTIFIED BY '${FORGEOJ_WORKER_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON forgeoj.* TO 'forgeoj_migrator'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
EOSQL
