# ForgeOJ development infrastructure

`compose.dev.yml` supplies the disposable MySQL and RabbitMQ runtime used while
building M0. It does not yet start the API, frontend, or Judge Worker and is not
a production deployment definition.

## Start

From the repository root, copy `.env.example` to the ignored `.env` file and
replace every example secret. The MySQL initialization script deliberately
accepts only a conservative password character set so that secrets cannot alter
its SQL statements.

```powershell
docker compose --env-file .env -f deploy/compose.dev.yml up -d --wait
```

Both services bind only to `127.0.0.1`. MySQL uses UTC and `utf8mb4`; RabbitMQ
uses the `/forgeoj` vhost and a non-guest development account. The three MySQL
accounts have separate purposes:

- `forgeoj_migrator`: Flyway owner with grant capability inside the `forgeoj`
  schema;
- `forgeoj_api`: public/API data only and no `problem_test_case` access;
- `forgeoj_worker`: submission/task updates and hidden tests, but no user table.

Flyway creates tables and applies the final table grants. The init script only
creates accounts and grants the migrator enough rights to run those migrations.

## Stop and reset

```powershell
docker compose --env-file .env -f deploy/compose.dev.yml down
```

Adding `--volumes` permanently deletes the disposable local MySQL and RabbitMQ
data. Use it only when an intentional clean reset is required.

The images are pinned to exact patch tags and immutable digests for
`linux/amd64`. Updating a digest is an explicit dependency change that requires
license, migration, and integration-test review.
