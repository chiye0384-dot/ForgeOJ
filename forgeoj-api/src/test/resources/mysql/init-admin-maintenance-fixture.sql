-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Disposable setup only: intentionally no audit INSERT permission.
CREATE USER 'limited_maintenance'@'%' IDENTIFIED BY 'public-limited-cli-fixture';
