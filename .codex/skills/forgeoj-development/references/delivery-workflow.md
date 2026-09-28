# ForgeOJ delivery workflow

Use this workflow for project initialization, feature work, fixes, review, and milestone completion.

## Before editing

1. Identify the current Roadmap milestone and the requirement or decision being implemented.
2. Inspect the actual repository and working tree; preserve unrelated user changes.
3. State the intended outcome, affected modules, risks, and verification method.
4. Break multi-step work into independently verifiable tasks. Do not use placeholders such as “handle edge cases later.”

Do not build later-version features merely because their types or tables seem convenient now.

## Implement

- Prefer the smallest design that satisfies the current milestone and preserves confirmed boundaries.
- For business rules, state machines, permissions, idempotency, and failure recovery, write a failing test first when practical, then the minimum implementation, then refactor.
- For configuration and scaffolding, validate observable behavior rather than forcing artificial unit tests.
- Keep API and Judge Worker boundaries intact. The API must not gain Docker control permissions.
- Treat MySQL as the business fact source; Redis, Elasticsearch, RabbitMQ, and WebSocket must not silently become authoritative.
- Do not add real credentials, private test data, copied problem content, or unreviewed external code.

## Diagnose failures

Read the exact failing output and identify the failing layer before editing. Form one evidence-based hypothesis at a time, apply the narrowest change, and rerun the smallest relevant check. A successful CLI check does not close a task when the requested IDE, browser, Docker, or Linux state still fails.

## Verify

Run fresh checks appropriate to the change, including relevant combinations of:

- build and static checks;
- focused unit tests;
- integration tests with disposable fixtures;
- permission and resource-ownership tests;
- duplicate-message and recovery tests;
- sandbox abuse tests;
- frontend end-to-end checks;
- fixed Linux environment checks.

Never mutate real user or production-like data without explicit authorization. Report skipped checks and their reason.

## Close the task

Before claiming completion:

1. compare the result with the original requirement and accepted decisions;
2. show the exact verification commands and outcomes;
3. update relevant documentation, Roadmap status, known limitations, upstream record, and evidence matrix;
4. distinguish implemented, verified, and released states;
5. identify any remaining limitation without disguising it as a deliberate bug.

Only evidence-backed, released capabilities may become resume claims.
