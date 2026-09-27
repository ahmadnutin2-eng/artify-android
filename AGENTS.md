# Artify engineering contract

## Product direction

Artify is an original professional Android drawing platform focused on Arabic calligraphy,
high-quality brushes, phone/tablet ergonomics, and optional live collaboration. Do not market or
implement it as a Procreate clone. References may inform interaction density and quality, but
Artify must retain its own name, assets, icons, brush names, and visual identity.

## Roles

Every task prompt must name exactly one role.

### Planner (Claude Code by default)

- Inspect the repository and the current roadmap before planning.
- Produce or update a scoped specification under `docs/specs/`.
- Define user value, dependencies, non-goals, risks, measurable acceptance criteria, and tests.
- Do not modify production source code in a planning task.
- Split work so one implementation task can be completed and verified independently.

### Executor (local Codex)

- Implement one approved specification at a time.
- Preserve unrelated user changes and avoid broad rewrites.
- Build, run automated tests, install the sidecar APK, and verify visually on the connected Android
  device when UI, drawing, stylus, performance, or lifecycle behavior changes.
- Record evidence and remaining risks in the matching specification before handoff.

### Reviewer (Claude Code or a separate independent thread)

- Review the diff against the specification and acceptance criteria.
- Prioritize data loss, rendering regressions, lifecycle bugs, security, accessibility, and
  performance. Do not approve based only on compilation.
- Keep review separate from the implementation thread when practical.

The same agent must not declare its own high-risk change accepted without automated evidence and a
device check.

## Claude Code ↔ Codex coordination

- Both assistants use `docs/AI_HANDOFF.md` as the single task ledger.
- Claude Code normally owns planning and independent review; local Codex normally owns
  implementation, automated tests, and Android device verification.
- Before acting, read the handoff's active task, role, branch, base commit, allowed paths, and exit
  gate. Do not silently broaden them.
- Only the named Writer may edit production source for the active task. The other assistant remains
  read-only and may update only the approved specification or review report.
- A handoff is valid only after the Writer commits the work and records the exact commit SHA. Never
  hand off uncommitted files or rely on chat history as project state.
- When both assistants run at the same time, use separate Git worktrees and branches. Never let two
  agents write to the same working tree concurrently.

## Required gates

For Android changes, run:

```powershell
& .\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' '-Partify.sidecar=true'
```

For UI changes, also install `app/build/outputs/apk/debug/app-debug.apk` on the connected device and
capture a screenshot at the target phone and tablet breakpoints. For drawing-engine changes, test a
short stroke, a long fast stroke, pressure, undo/redo, save/reopen, and export.

Do not commit secrets, signing material, `local.properties`, generated builds, extracted reference
applications, or third-party proprietary assets. Never log collaboration tokens or customer art.

## Product quality rules

- Prevent data loss before adding growth or monetization features.
- Drawing input must remain responsive when panels, autosave, collaboration, or previews are active.
- Use one icon system and one spacing/type scale. Emoji are not production navigation icons.
- A brush preview must be rendered from exactly one stroke and must truthfully represent the preset.
- Essential local drawing, opening existing work, and exporting a basic image must never require a
  subscription or a network connection.
- Paid features must provide continuing value: cloud sync/history, collaboration, premium content,
  advanced brush authoring, or professional export.
- Every network event must be authenticated, ordered or idempotent, reconnectable, and safe to retry.
- Accessibility labels, RTL behavior, minimum touch targets, and small landscape phones are release
  requirements, not later polish.

## Planning sources

- Master sequence: `docs/PRODUCT_ROADMAP.md`
- Release gates and metrics: `docs/ACCEPTANCE_CRITERIA.md`
- Planner/executor handoff: `docs/AGENT_WORKFLOW.md`
- Live Claude/Codex ledger: `docs/AI_HANDOFF.md`
- Phase specifications: `docs/specs/PHASE-<number>-<name>.md`
