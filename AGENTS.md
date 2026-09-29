# ForgeOJ agent instructions

## Repository status

ForgeOJ has an approved requirements baseline and a verified M-1 minimal project scaffold. M0 business implementation started on 2026-09-28 and remains `IN_PROGRESS`. The data/infrastructure foundation, development-session login, public-problem read, atomic Submission/JudgeTask/Outbox creation, confirmed RabbitMQ Outbox publishing, and idempotent Worker task-claim slices have local automated evidence; the real task runner, sandbox, and end-to-end judging remain incomplete.

Use these documents as authoritative sources:

- `docs/ForgeOJ-Requirements.md` for behavior and scope;
- `docs/ForgeOJ-Decision-Log.md` for accepted tradeoffs;
- `docs/ForgeOJ-Roadmap.md` for version and milestone boundaries;
- `docs/M0-VERTICAL-SLICE-DESIGN.md` for the current M0 API, data, message, worker, and Docker implementation contract;
- `docs/KNOWN_LIMITATIONS.md` for accepted limitations;
- `docs/UPSTREAM-AND-LICENSE.md` before importing or changing a scaffold or material dependency;
- `docs/DIRECT-DEPENDENCY-LICENSES.md` for the current direct dependency license inventory and unresolved distribution conditions;
- `docs/Resume-Evidence-Matrix.md` before making completion or resume claims;
- `docs/Performance-Test-Plan.md` for any performance claim.

Read only the documents relevant to the current task. Keep them consistent when a confirmed decision changes.

## Decision boundary

- Inspect files, commands, versions, licenses, logs, and official documentation instead of asking the user for discoverable facts.
- Ask before choosing when the answer would materially change product scope, public behavior, persistent data, security boundaries, license obligations, recurring cost, or external state.
- Make routine implementation choices inside confirmed boundaries and explain important tradeoffs.
- Never silently invent a requirement or implement a later Roadmap item early.
- Explicit user instructions override repository guidance.

## Confirmed engineering boundaries

- Use JDK 21 for the backend and the V1 user-code runtime.
- Use Spring Boot 4.1.1 for the current backend baseline.
- Use the official MyBatis Spring Boot Starter 4.1.0, not MyBatis-Plus. PageHelper is deferred until real list-query requirements exist.
- License ForgeOJ-authored source under Apache-2.0 with `Copyright 2026 池也`. Do not relicense generated files, retained upstream code, dependencies, or user/problem content; preserve their original notices and license records.
- Keep the root Maven project as an aggregator/parent with separate executable `forgeoj-api` and `forgeoj-judge-worker` modules. Neither module may depend on the other.
- V1 supports one `Main.java`, the JDK standard library, and standard input/output problems.
- Keep the API service and Judge Worker as separate processes. The API must not control Docker.
- Use MySQL as the final business fact source. Redis is cache/session/rate-limit support; Elasticsearch is rebuildable public search; RabbitMQ transports at-least-once tasks; WebSocket is notification only.
- Distinguish platform failures from user verdicts.
- Do not expose hidden tests, private reference programs, credentials, or unrelated user code.
- Treat ordinary Docker as a constrained boundary for controlled small-scale use, not as absolute isolation for arbitrary hostile internet code.

## Upstream and dependency rules

- Do not copy code, UI, problem statements, tests, or assets without a clear compatible license and recorded source.
- Record repository URL, immutable commit/tag, license, retained files, modifications, and ForgeOJ-owned modules before importing a scaffold.
- Prefer a minimal official scaffold when a large starter adds more unrelated business code than useful infrastructure.
- Do not describe third-party capabilities as personally implemented.
- Do not add a dependency only to display another technology on the resume.

## Change workflow

- Identify the current Roadmap milestone and relevant requirement before editing.
- Preserve unrelated user changes and keep the patch scoped.
- Use tests for state machines, permissions, idempotency, consistency, and recovery. Use observable validation for build and configuration changes.
- Use disposable test data. Do not mutate real databases or external services without explicit authorization.
- Never commit secrets. Store only safe examples such as `.env.example`.
- If a requested result still fails in the IDE, browser, Docker, or target Linux environment, continue diagnosis; a partial command-line success is not completion.

Use the repository-pinned commands for the M-1 baseline:

- backend on Windows: `.\mvnw.cmd --batch-mode clean verify`;
- backend on Linux/macOS before the first commit records executable metadata: `bash ./mvnw --batch-mode clean verify`;
- frontend install: run `npm ci` inside `frontend`;
- frontend verification: run `npm run verify` inside `frontend`.

`mvnw.cmd` contains a documented null guard for an Apache Maven Wrapper 3.3.4 Windows bug. Keep the patch and `distributionSha256Sum` aligned with `docs/UPSTREAM-AND-LICENSE.md`; re-evaluate the patch when upgrading the Wrapper.

Linux/macOS environments running the script-only Maven Wrapper must provide `bash` and `unzip`. Without `unzip`, Wrapper 3.3.4 falls back from the configured ZIP URL to a tarball, which cannot match the recorded ZIP SHA-256.

The current H2 dependency is test-scoped and only proves that the empty application contexts can start. M0 also has disposable MySQL 8.4.12 tests for the V1 Flyway migration, API/Worker database boundaries, dev seed, server-side session login/logout, CSRF enforcement, the public-problem response whitelist, atomic Submission/JudgeTask/Outbox creation, rollback, and sequential/concurrent request replay. Fixed MySQL 8.4.12 and RabbitMQ 4.3.6 Testcontainers verify durable routing, persistent four-field JSON, publisher confirm, leaving unroutable events unpublished, strict Worker contract parsing, manual ACK, task/submission cross-checking, atomic state transition, rollback, and single-winner duplicate/concurrent claims. These tests do not yet prove task snapshot loading, Docker execution, terminal result writes, or crash recovery.

## Completion and evidence

Use these states accurately: `PLANNED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `RELEASED`, `DEFERRED`, and `BACKLOG`.

Do not claim completion without fresh relevant verification. Report commands run, outcomes, skipped checks, and remaining limitations. Update the evidence matrix before promoting a capability into the resume, and never reuse performance numbers from another project.
