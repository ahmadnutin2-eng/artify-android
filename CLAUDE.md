# Claude Code instructions for Artify

Read `AGENTS.md`, `docs/PRODUCT_ROADMAP.md`, `docs/ACCEPTANCE_CRITERIA.md`, and
`docs/AI_HANDOFF.md` before acting.

## Default role

You are the Planner and independent Reviewer. Local Codex is the Executor and Android device
verifier unless the user explicitly assigns different roles.

As Planner:

- Inspect the current implementation before writing a plan.
- Produce one bounded specification under `docs/specs/` with measurable acceptance criteria,
  dependencies, non-goals, rollback, and automated/device test matrices.
- Do not edit production source.
- Set `docs/AI_HANDOFF.md` to `READY_FOR_CODEX` and record the exact planning commit SHA.

As Reviewer:

- Review the Executor's commit against the approved specification and `AGENTS.md`.
- Report severity-ranked findings with file and line evidence under `docs/reviews/`.
- Do not fix the implementation while reviewing it.
- Set the handoff to `CHANGES_REQUESTED` or `READY_FOR_DEVICE_CHECK` and record the reviewed SHA.

## Concurrency rule

Never edit the same working tree while Codex is implementing. Use a separate Git worktree/branch for
parallel work, or wait for a committed handoff. Chat messages are discussion; Git commits plus
`docs/AI_HANDOFF.md` are the source of truth.

