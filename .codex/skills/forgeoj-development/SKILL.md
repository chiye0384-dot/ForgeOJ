---
name: forgeoj-development
description: Guide ForgeOJ scaffold selection, milestone planning, implementation, review, and evidence updates using the repository's approved requirements and decisions. Use only for work inside ForgeOJ; do not use for unrelated Java projects or ordinary conceptual explanations.
---

# ForgeOJ Development

Keep ForgeOJ changes aligned with the approved product boundary and supported by fresh evidence.

## Start from repository truth

Locate the ForgeOJ repository root, then read `AGENTS.md`. Read only the documents relevant to the current task:

- Product behavior or scope: `docs/ForgeOJ-Requirements.md`.
- Accepted tradeoffs: `docs/ForgeOJ-Decision-Log.md`.
- Current milestone and deferred work: `docs/ForgeOJ-Roadmap.md`.
- Known boundaries: `docs/KNOWN_LIMITATIONS.md`.
- Upstream code or dependencies: `docs/UPSTREAM-AND-LICENSE.md`.
- Completion or resume claims: `docs/Resume-Evidence-Matrix.md` and, for performance work, `docs/Performance-Test-Plan.md`.

Do not copy these documents into the skill. They remain the evolving sources of truth.

## Route the task

- For scaffold, upstream, dependency, or license selection, read [references/upstream-selection.md](references/upstream-selection.md).
- For implementation, refactoring, review, debugging, or milestone completion, read [references/delivery-workflow.md](references/delivery-workflow.md).
- For a mixed task, read both, but complete upstream approval before importing code.

## Resolve uncertainty correctly

- Investigate discoverable facts yourself: repository state, dependency versions, license text, build output, test results, and tool availability.
- Ask a concise question before choosing when the answer would change product scope, public behavior, data migration, security boundary, license obligation, recurring cost, or an external side effect.
- Do not ask the user to decide routine implementation details that remain inside an approved design.
- Never invent a requirement to keep moving. Record genuinely optional ideas in the Roadmap rather than implementing them silently.

## Preserve status honesty

Use the repository status vocabulary: `PLANNED`, `IN_PROGRESS`, `IMPLEMENTED`, `VERIFIED`, `RELEASED`, `DEFERRED`, and `BACKLOG`.

Do not describe a capability as complete because code exists or starts locally. Completion requires the relevant tests, target-environment checks, documentation, and evidence defined by the current milestone.

Explicit user instructions override this workflow. If they change an accepted requirement, update the requirements and decision log before or with the implementation so the repository remains internally consistent.
