# ForgeOJ agent instructions

## Repository status

Current learning stage (2026-10-03): personal learning records are VERIFIED on feat/m2-accounts; full M2 remains IN_PROGRESS. V8 owner/CAS lists, read-only official lists, current-version owner AC, server drafts and owner history passed fixed Linux API 130 + Worker 111 (241, zero failures/errors/skips), frontend 35/all checks. Exact artifacts passed real two-tab draft conflicts (keep local, explicit CAS save, load server), normal/fallback AC, personal/official progress 2/2, history 11, UI list create/rename/add/order/remove, and post-submit draft independence. Audit has 11 formal submissions/10 finished/1 cancelled, 11 published Outbox, four empty queues, actual grant denials and no sensitive log sentinels; precise owned-resource cleanup passed on a verified daemon. See docs/M2-LEARNING-RECORDS-VALIDATION.md and docs/evidence/m2-learning. 204 business inputs remain identical; the sole post-build Replay 1143 column-denial assertion fix was executed and recorded separately. Next is content/private reference/immutable review versions, self-test and solutions; external SMTP delivery still requires provider configuration and an authorized inbox. Never treat ACTIVE alone as a future private-content permission. Preserve the user's untracked M2-NEXT-CHAT-HANDOFF.md. Verified-unit feature commit/push remains authorized; no PR/main merge/tag/release. Earlier snapshots below are historical.

Current library stage (2026-10-03): ordinary accounts and public library/SMTP adapter units are VERIFIED; full M2 remains IN_PROGRESS on feat/m2-accounts. Fresh fixed Linux passed API 118 + Worker 111 (229, zero failures/errors/skips), frontend 24/all checks. Exact artifacts passed anonymous library queries, real browser selection of the second original fixture to AC, old-root polling to AC, eight verdicts, owner/Origin/revocation, log/queue/grant audit and owned-resource cleanup. See docs/M2-LIBRARY-SMTP-VALIDATION.md and docs/evidence/m2-library. 192 business/test/contract inputs match the build; one post-build Replay-only UTF-8/fact-export fix is explicitly recorded and executed. SMTP loopback TLS tests passed, but external provider/inbox delivery remains unconfigured/unverified. Next implement docs/M2-LEARNING-RECORDS-DESIGN.md (personal private lists, official read-only lists, current-version AC progress, CAS drafts, owner history); content/review versions, self-test and solutions remain later M2 work. Preserve the user's untracked M2-NEXT-CHAT-HANDOFF.md unchanged. A verified unit may be committed/pushed to this feature branch; no PR/main merge/tag/release is authorized. Earlier dated paragraphs are historical.

Current account stage (2026-10-02): M1 remains VERIFIED and the ordinary-account unit is now VERIFIED; M2 remains IN_PROGRESS on feat/m2-accounts, based on d38e2c9. The user approved all recommendations in docs/M2-ACCOUNTS-DESIGN.md. Registration/email verification, short JWT + MySQL sessions, rotating refresh, immediate revocation, password recovery/change, legacy email binding and account pages passed fresh fixed-Linux verification: API 100 + Worker 111 (211, zero failures/errors/skips), frontend 13/all checks. Exact final artifacts in target/forgeoj-linux-20261002-162353-b85f8da4 were replayed in an independent disposable stack: eight verdicts, browser registration/email activation/login to AC, legacy login with polling to AC, owner/Origin/logout/cancellation, 11 published Outbox records and four empty queues. All owned containers/volumes/network/images, Testcontainers and managed sandboxes are removed; 188 backend/frontend/validation inputs still match the accepted snapshot. Real Servlet error-dispatch regressions were found and fixed with direct, empty API error responses; /error remains protected. See docs/M2-ACCOUNTS-VALIDATION.md and committed redacted facts/screenshots under docs/evidence/m2-accounts. Do not treat this unit as all M2 gates passed or as production SMTP/HTTPS/cloud acceptance. Next work is the remaining M2 library/learning flow design against the approved Requirements/Roadmap. Keep the user's untracked M2-NEXT-CHAT-HANDOFF.md unchanged; dated snapshots below predate this approval. A verified unit may be committed/pushed to the current feature branch with an explained reason. No PR, main merge, tag or release is authorized. Recheck live Git state before continuing.

Latest snapshot (2026-10-02): M1 is `VERIFIED`, with all 15 gates audited in `docs/M1-GATE-AUDIT.md`. Baseline implementations `ab61c9a`, Linux evidence `8f18174` and independent replay `2e1af57` are committed/pushed; final audit adds two focused integration regressions for same-event Outbox recovery and every stale-owner write after actual lease recovery. Fresh fixed-Linux clean verification finished 10:43:48: API 49 + Worker 111 (160, zero failures/errors/skips); frontend 8/all checks passed at 10:44. No containers, managed sandboxes, fault JVMs or unique build image remain. API/Worker runtime classes/resources/dependencies (125/104 entries) match the earlier E2E JARs byte-for-byte, linking this regression to eight verdicts, real browser normal/fallback, permissions, logs, empty queues and fresh DB deployment evidence without claiming a repeated E2E run. L-026 closes; L-027–L-032 and Docker's controlled-use boundary remain. Independent administrator authentication/roles/manual DLQ retry are M4, not M1: earlier snapshots advanced them incorrectly. M2 remains `PLANNED`; next is its smallest account/learning-flow design, not broad implementation. User authorization is judgment-based: explain why a completed verified unit merits commit, then commit/push only the current feature branch. No completion PR, main merge, tag or release is authorized; feature push does not run the present CI workflow. Apply V5 before the new Worker. Dated paragraphs below are historical snapshots, not current status, uncommitted-state or scope claims. Recheck Git before continuing.

ForgeOJ has an approved requirements baseline, a verified M-1 minimal project scaffold, and a verified M0 minimum judging vertical slice. On 2026-09-30, commit `39e91145` passed 49 backend tests and the complete frontend verification in fixed Linux/amd64 containers, then passed a real Linux process-level Vite/API/Outbox/RabbitMQ/Worker/Docker/MySQL replay for AC, WA, CE, RE, TLE, and OLE. All 20 M0 gates are recorded as passed in `docs/M0-E2E-VALIDATION.md`.

M1 is `IN_PROGRESS` on `feat/m1-reliable-judging`; `docs/M1-RELIABLE-JUDGING-DESIGN.md` is its implementation baseline. Attempt/lease fencing, heartbeats, passive/active recovery, finite retry/dead letters, recoverable Outbox, separate queue boundaries, concurrent per-user quotas, and owner-only queued cancellation have local evidence through `44642f3`, recorded in `docs/M1-QUOTA-CANCELLATION-VALIDATION.md`. The current working tree (base HEAD `ceee82c`) additionally implements user-approved D-039: owner-only same-origin WebSocket, three-field monotonic notices, session/terminal cleanup, and frontend bounded reconnect plus GET polling recovery. On 2026-10-01, full Windows verification passed 38 API and 57 Worker tests (zero failures/errors/skips), including real MySQL, RabbitMQ, and Docker checks; all frontend gates and 8 tests passed. Details and limitations are in `docs/M1-NOTIFICATION-VALIDATION.md`. Notification changes are not yet committed or pushed; recheck actual Git state before continuing.

The same local working tree now also implements correlated redacted JSON logs: internal HTTP requestId, explicit submission/task/attempt IDs, post-commit lifecycle events, fixed failure codes, and no raw broker NACK reason or exception object. On 2026-10-01 at 18:33, fresh root `clean verify` passed 46 API and 67 Worker tests (113 total, zero failures/errors/skips), and frontend verification again passed all gates and 8 tests. Details, artifact hashes, red/green failures and limitations are in `docs/M1-OBSERVABILITY-VALIDATION.md`. Neither this stage nor the notification stage has been committed or pushed; HEAD remains `ceee82c`. Logs are best-effort observability, not durable audit or a replacement for MySQL.

The user explicitly chose 1 RUNNING + 3 pending without a retry reservation: full-queue failures use internal WAITING_RETRY, retain the running slot, clear the execution lease, and expose RUNNING until the next finite attempt. M1 runtime must enable recovery scanning for quota-deferred tasks. M1 is not `VERIFIED`: the wider hostile-code/crash/ACK-loss matrix, fixed-Linux notification/observability and process-level replay, operational closure, and final gate updates remain. Do not proceed to M2 or create a completion PR before the remaining gates pass.

The latest local stage now adds four real child-JVM fault checks: claim-owner kill and real lease expiry, terminal commit before ACK, orphan recovery by an already-started Worker, and preservation of another live Worker's sandbox at startup. A real orphan-name collision was reproduced first, then fixed with attempt-level names/labels, immutable Docker IDs, and cleanup authorized only by committed closed-attempt facts. No new dependency, schema, database grant, public API or MQ field was added. Fresh root `clean verify` finished on 2026-10-01 at 21:04:37 with API 46 + Worker 83 (129 total, zero failures/errors/skips); frontend again passed all gates and 8 tests. No managed sandbox or fault child JVM remained, and fault controls are absent from the production JAR. See `docs/M1-FAULT-RECOVERY-VALIDATION.md`, M1 design 5.1 and L-031. This is Windows-process/Docker Desktop evidence, not fixed-Linux or arbitrary network packet-loss acceptance. All three local stages remain uncommitted/unpushed at HEAD `ceee82c`; M1 remains `IN_PROGRESS`.

The subsequent sandbox-security stage is locally verified: 14 real bounded adversarial programs plus a real Worker credential probe exposed and fixed same-UID cleanup permissions, child/zombie reaping, global temporary-file leakage and writable shared memory. Docker init, IPC none, bounded process-empty verification and ownership-scoped tmp cleanup add no privileges or dependencies. Root `clean verify` completed 2026-10-01 21:39:40 with API 46 + Worker 98 (144 total, zero failures/errors/skips), recovered and checked on 2026-10-02; managed sandbox/fault JVM residue is zero. Frontend was unchanged and not rerun in this stage. See `docs/M1-SANDBOX-SECURITY-VALIDATION.md`, design 5.2 and L-032. MLE/SECURITY_VIOLATION classification remains unimplemented despite resource containment; resolve its trusted signals/contracts/migrations before final Linux/operational gate closure. No commit/push/release was made, HEAD remains `ceee82c`, M1 remains `IN_PROGRESS`.

The latest resource-verdict stage now implements trusted per-case cgroup v2 counter deltas: local oom becomes MLE, PID max becomes SECURITY_VIOLATION; user text/exit codes are ignored as evidence, external OOM-only or missing evidence is a platform failure. V3 already allowed both verdicts but VARCHAR(16) could not store the security string; new V5 widens it to 32 and preserves historical rows, without changing V1–V4 or grants. Apply V5 before the new Worker. Fresh root `clean verify` finished 2026-10-02 08:34:50 with API 48 + Worker 110 (158 total, zero failures/errors/skips), no managed sandbox/fault JVM residue or test controls in the production JAR. See D-040, design 5.3, L-032 and `docs/M1-RESOURCE-VERDICT-VALIDATION.md`. Pure JVM-only OOM and unaudited denied operations remain outside this trusted classifier. Frontend was unchanged/not rerun; fixed Linux, OPS_ADMIN and final M1 gates remain. Preserve all uncommitted changes at HEAD `ceee82c`; no commit/push/release was made.

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

The current H2 dependency is test-scoped and only proves that the empty application contexts can start. M0 also has disposable MySQL 8.4.12 tests for the V1/V2 Flyway migrations, API/Worker database boundaries, dev seed, server-side session login/logout, CSRF enforcement, the public-problem response whitelist, atomic Submission/JudgeTask/Outbox creation, rollback, sequential/concurrent request replay, and owner-only submission result reads. The query tests verify anonymous rejection, identical 404 behavior for another owner/missing/malformed IDs, an exact response-field whitelist, and diagnostic redaction. Fixed MySQL 8.4.12 and RabbitMQ 4.3.6 Testcontainers verify durable routing, persistent four-field JSON, publisher confirm, leaving unroutable events unpublished, strict Worker contract parsing, manual ACK, task/submission cross-checking, atomic state transitions, rollback, and single-winner duplicate/concurrent claims. Restricted Worker tests verify running-task-only snapshot loading, ordered bounded gzip expansion, source/test/dataset hashes, fail-closed tamper handling, all M0 terminal verdict mappings including OLE, atomic dual-table completion, and SYSTEM_ERROR without a user verdict. A real RabbitMQ/MySQL/Docker test verifies message-to-terminal execution and ACK after the terminal state becomes durable. A frontend Vitest flow verifies login, public-problem rendering, CSRF and Idempotency-Key submission headers, status polling to a terminal result, polling stop, and non-rendering of undeclared hidden fields against mocked API responses. On 2026-09-29, a disposable Windows + Docker Desktop stack verified a real browser AC flow. On 2026-09-30, fixed Linux/amd64 builds and a real separated-process replay verified all six M0 verdicts, the API-without-Docker boundary, four-field Outbox payloads, drained queues, redacted logs, and sandbox cleanup. These checks still do not prove the wider malicious-code matrix, M5 fixed-Linux-host security/performance acceptance, or M1 crash recovery.

## Completion and evidence

Use these states accurately: `PLANNED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `RELEASED`, `DEFERRED`, and `BACKLOG`.

Do not claim completion without fresh relevant verification. Report commands run, outcomes, skipped checks, and remaining limitations. Update the evidence matrix before promoting a capability into the resume, and never reuse performance numbers from another project.
