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

## Run the API and local accounts

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
profile creates neither. V6 preserves this legacy ACTIVE account and its history;
it may still log in with its username, but must confirm its current password and
verify a bound email before using password recovery. Old in-process sessions do
not survive the M2 authentication upgrade. The judging HTTP slice remains:

- `GET /api/v1/auth/session` for authentication state and a CSRF token;
- `POST /api/v1/auth/login` and `POST /api/v1/auth/logout` using that token;
- `GET /api/v1/problems/sum-two-integers` for the public-field whitelist.
- authenticated `POST /api/v1/problems/sum-two-integers/submissions` with a
  UUID `Idempotency-Key` header to atomically create the queued submission,
  judge task, and four-field Outbox event;
- authenticated `GET /api/v1/submissions/{submissionId}` for the owner-only,
  field-limited status and terminal result.

The M2 account unit is `VERIFIED`; the complete M2 milestone remains
`IN_PROGRESS`. Its browser entry is `http://localhost:5173/account`. Registration,
email activation, refresh, current/all-session logout, password change/reset and
legacy email binding are described in [the account design](../docs/M2-ACCOUNTS-DESIGN.md)
and D-041. [Final acceptance](../docs/M2-ACCOUNTS-VALIDATION.md) records the fixed
Linux 211 backend / 13 frontend checks, actual browser registration-to-AC, runtime
audit and exact cleanup; [sanitized facts](../docs/evidence/m2-accounts/README.md)
are retained with the source. This verifies local email flows, not production SMTP.
Use disposable account/email values rather than real personal data while testing.

JWT signing/parsing remains third-party Spring Security JOSE / Nimbus capability;
versions and Apache-2.0 notices are recorded in [the dependency inventory](../docs/DIRECT-DEPENDENCY-LICENSES.md),
with ForgeOJ account-flow ownership in [OWNERSHIP](../docs/OWNERSHIP.md).

Start the frontend separately with `npm run dev` inside `frontend`. For the
default dev mailbox, use the same browser hostname and frontend port consistently:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:FORGEOJ_AUTH_MAIL_APP_URL = 'http://localhost:5173'
$env:FORGEOJ_AUTH_MAIL_MODE = 'local'
.\mvnw.cmd --batch-mode -pl forgeoj-api spring-boot:run
```

After registering on the account page, open `http://127.0.0.1:2525/`, select the
activation email and explicitly confirm on the account page. The link carries a
token in the fragment; the page removes it from the address before sending a
confirmation POST. Reset and binding use the same mailbox. Binding confirmation
also requires the correct signed-in account. If Vite uses another port, set
`FORGEOJ_AUTH_MAIL_APP_URL` to that actual origin before starting the API.

`LocalAccountMail` is restricted to `dev`/`test`, listens only on `127.0.0.1`,
stores at most 100 messages in memory and clears them on shutdown. It is a local
fixture, not SMTP or a production mailbox; do not publish its port. Changing
`FORGEOJ_AUTH_MAIL_PORT` selects another loopback port. The simulator is not
reachable from the host when it runs inside a container without an explicit
development-only access arrangement; the provided infrastructure Compose runs
neither API nor mailbox.

Account configuration belongs to the API process environment. `docker compose
--env-file .env` supplies interpolation for infrastructure and does not export
those variables to a separately launched API:

| API environment variable | Default | Development use / deployment boundary |
|---|---|---|
| `FORGEOJ_AUTH_JWT_SECRET` | Empty; startup fails outside dev without a valid key | At least 32 UTF-8 bytes of private random signing material in deployment. Dev may generate an ephemeral key when empty; restarting then invalidates old access JWTs. Never use the public Surefire test key for real data. |
| `FORGEOJ_AUTH_COOKIE_SECURE` | `true` | Dev selects `false` for local HTTP only. Keep `true` with production HTTPS. |
| `FORGEOJ_AUTH_MAIL_MODE` | `disabled` | Dev defaults to `local`; an explicit environment value wins. Local is accepted only with dev/test. Select `smtp` explicitly for the configured TLS adapter. |
| `FORGEOJ_AUTH_MAIL_PORT` | `2525` | Loopback mailbox port; development only. |
| `FORGEOJ_AUTH_MAIL_APP_URL` | `http://localhost:5173` | Actual frontend root URL; production SMTP requires HTTPS. |
| `FORGEOJ_AUTH_MAIL_SMTP_HOST` / `PORT` / `TLS` | Empty / `587` / `starttls` | Provider host/port; required STARTTLS or `implicit` TLS, normal certificate/hostname validation. |
| `FORGEOJ_AUTH_MAIL_SMTP_FROM` / `USERNAME` / `PASSWORD` | Empty | Single sender and a complete authentication pair in the API environment; never commit or log credentials. |
| `FORGEOJ_AUTH_MAIL_SMTP_CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` / `WRITE_TIMEOUT_MS` | `5000` each | Socket wait bounds 100–30000 ms. |
| `FORGEOJ_AUTH_LIMITS_MULTIPLIER` | `1` | Retain production defaults. Disposable automated tests may increase this value to avoid fixture interactions; that is not production capacity evidence. |

The explicit `smtp` adapter is implemented; local TLS protocol tests verify the
wire behavior, without contacting a provider. Before enabling public registration
or recovery, configure the verified provider and authorized recipient and verify
actual delivery, credentials, timeouts and failure handling. See
`docs/M2-SMTP-DESIGN.md` and `.env.example`. Default
disabled mode or delivery failure is caught after the account transaction commits
and logs only `account.mail_delivery_failed`; the generic 202 response does not
promise that mail arrived. The user can request another eligible token after the
database cooldown. There is no durable mail Outbox or automatic retry guarantee
in this unit.

The signing key must be generated privately once and retained outside the
repository; it must not be an example password or a key printed in logs. Each
protected request validates the short JWT and then MySQL ACTIVE/session state;
database failure does not permit access. Access and refresh are host-only,
HttpOnly, SameSite=Strict cookies. All write requests need the exact same-origin
Origin header and the CSRF header returned by `GET /api/v1/auth/session`; login
and identity changes rotate CSRF, refresh keeps it. The frontend coordinates
refreshes through Web Locks and rechecks the session after acquiring the lock;
without Web Locks, short JWT expiry requires a fresh login instead of rotating a
shared refresh cookie automatically. Clients must not retry an already consumed
refresh indefinitely.
Redis session caching/distributed limits remain M4, and production HTTPS/proxy
and SMTP acceptance remain separate work. Apply through V9 with the migrator before
running the upgraded API; do not roll back by deleting authentication rows or
editing V1–V8. V7 adds nullable problem difficulty and read-only problem tags;
the dev repeatable seed labels only the existing original A+B example. Public
library entry is `/problems`, with actual topics at `/problems/:slug`; `/` remains
the original A+B entry. Production migrations do not seed problems or users.

V8 adds private personal lists, official read-only lists and Java21 code drafts;
it seeds no lists, drafts or progress. `/learning` exposes own lists/history and
official public metadata. Draft writes require the current session, Origin, CSRF
and expectedVersion; 409 needs an explicit conflict choice. Progress counts only
the current judge_version's own FINISHED/AC. This unit passed fixed Linux and
real-browser acceptance; see `docs/M2-LEARNING-RECORDS-VALIDATION.md`. Full M2 and
external SMTP delivery remain unfinished.

V9 adds author-only content drafts and compressed tests, with no public content or
successful-validation seed. `/authoring` supports separate reference/solution
code and manual/ZIP tests. Writes require the current session, Origin/CSRF and
expectedVersion; archived drafts stay read-only. Worker has no mutable-authoring
table access. This draft phase is VERIFIED; formal content sandbox validation,
review/withdrawal and public solution access remain pending. See
`docs/M2-CONTENT-VALIDATION.md`.

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
