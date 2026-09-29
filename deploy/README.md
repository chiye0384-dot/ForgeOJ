# ForgeOJ development infrastructure

`compose.dev.yml` supplies the disposable MySQL and RabbitMQ runtime used while
building M0. It intentionally does not start the API, frontend, or Judge Worker;
run those as separate development processes. This is not a production deployment
definition.

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

## Run the M0 API slice

Set `SPRING_PROFILES_ACTIVE=dev` plus the database and RabbitMQ passwords from
your ignored `.env` file in the API process environment. At minimum the API
needs `FORGEOJ_API_DB_PASSWORD`, `FORGEOJ_MIGRATOR_PASSWORD`, and
`RABBITMQ_DEFAULT_PASS`; do not commit their real values. Then run from the
repository root:

```powershell
.\mvnw.cmd --batch-mode -pl forgeoj-api spring-boot:run
```

The `dev` profile adds the repeatable development seed after the production
migration. It creates only the M0 user `learner` (password
`forgeoj-dev-only`) and the original `sum-two-integers` problem. The default
profile creates neither. The currently available HTTP slice is:

- `GET /api/v1/auth/session` for authentication state and a CSRF token;
- `POST /api/v1/auth/login` and `POST /api/v1/auth/logout` using that token;
- `GET /api/v1/problems/sum-two-integers` for the public-field whitelist.
- authenticated `POST /api/v1/problems/sum-two-integers/submissions` with a
  UUID `Idempotency-Key` header to atomically create the queued submission,
  judge task, and four-field Outbox event;
- authenticated `GET /api/v1/submissions/{submissionId}` for the owner-only,
  field-limited status and terminal result.

With the `dev` profile, the API polls unpublished M0 Outbox rows in small
batches, publishes persistent JSON to the durable RabbitMQ topology, and only
sets `published_at` after a positive publisher confirm with no returned
message. Never enable the `dev` profile in production.

## Run the M0 Judge Worker

Run the Worker in a separate terminal. Supply `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME=forgeoj_worker`, `SPRING_DATASOURCE_PASSWORD`, the
five `SPRING_RABBITMQ_*` connection values, and a Docker CLI path visible to the
Worker. Then explicitly enable the two fail-closed M0 switches:

```powershell
$env:FORGEOJ_WORKER_CONSUMER_ENABLED = 'true'
$env:FORGEOJ_WORKER_SANDBOX_ENABLED = 'true'
.\mvnw.cmd --batch-mode -pl forgeoj-judge-worker spring-boot:run
```

The Worker verifies the Linux Docker Engine at startup, consumes the strict
four-field message, claims the task idempotently, reloads the immutable snapshot
and hidden tests from MySQL, executes Java 21 in a restricted container, writes
the Submission and JudgeTask terminal states in one transaction, and only then
ACKs. Keep both switches disabled when the Worker must not control Docker.

Start the frontend separately with `npm run dev` inside `frontend`; Vite proxies
`/api` to the API at `127.0.0.1:8080`. The recorded disposable full-flow check is
in `../docs/M0-E2E-VALIDATION.md`.

## Stop and reset

```powershell
docker compose --env-file .env -f deploy/compose.dev.yml down
```

Adding `--volumes` permanently deletes the disposable local MySQL and RabbitMQ
data. Use it only when an intentional clean reset is required.

The images are pinned to exact patch tags and immutable digests for
`linux/amd64`. Updating a digest is an explicit dependency change that requires
license, migration, and integration-test review.
