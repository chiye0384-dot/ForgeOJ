CREATE USER IF NOT EXISTS 'forgeoj_migrator'@'%' IDENTIFIED BY 'm0-migrator-test-secret';
CREATE USER IF NOT EXISTS 'forgeoj_api'@'%' IDENTIFIED BY 'm0-api-test-secret';
CREATE USER IF NOT EXISTS 'forgeoj_worker'@'%' IDENTIFIED BY 'm0-worker-test-secret';

GRANT ALL PRIVILEGES ON forgeoj.* TO 'forgeoj_migrator'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
