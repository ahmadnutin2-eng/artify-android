# Artify engineering contract

## Product direction

Artify is an original professional Android drawing platform focused on Arabic calligraphy,
high-quality brushes, phone/tablet ergonomics, and optional live collaboration. Do not market or
implement it as a Procreate clone. References may inform interaction density and quality, but
Artify must retain its own name, assets, icons, brush names, and visual identity.

## Roles

Every task prompt must name exactly one role.

### Heavy-work agent (Claude Code by default)

- Own repository-wide inspection, long planning passes, dependency/security/provenance audits,
  backend and protocol analysis, large test matrices, documentation, repetitive mechanical work,
  and independent code review.
- Run expensive or long-lived analysis and verification when it does not require physical-device
  judgment or ownership of the product's visual quality.
- Produce bounded specifications under `docs/specs/` before handing implementation to Codex.
- May implement isolated non-visual infrastructure only when the handoff names Claude as Writer and
  its paths do not overlap an active Codex visual/rendering task.

### Product-quality agent (local Codex by default)

- Own visible product quality: UI/UX, responsive phone/tablet proportions, design tokens, icons,
  typography, motion, panels, brush previews, drawing feel, rendering, stylus behavior, and device
  screenshots.
- Own deep implementation where continuity and careful code reasoning matter, especially drawing,
  lifecycle, persistence safety, performance-sensitive paths, and final integration.
- Use Claude's plan/review as evidence, not as automatic approval; inspect the actual code and retain
  the final quality gate for anything the user sees or touches.
- Build, test, install only the sidecar package, and verify on real Android devices when relevant.

### Reviewer (Claude Code by default; Codex owns final product sign-off)

- Review the diff against the specification and acceptance criteria.
- Prioritize data loss, rendering regressions, lifecycle bugs, security, accessibility, and
  performance. Do not approve based only on compilation.
- Keep review separate from the implementation thread when practical.

The same agent must not declare its own high-risk change accepted without automated evidence and a
device check.

## Claude Code ↔ Codex coordination

- Both assistants use `docs/AI_HANDOFF.md` as the single task ledger.
- Claude Code normally owns high-volume planning, auditing, repetitive work and independent review.
  Codex normally owns visual/design implementation, rendering and interaction quality, deep
  integration, automated gates, and Android device verification.
- Assign by comparative advantage, not by alternating turns: give Claude work whose main cost is
  context volume or repetition; give Codex work whose main risk is visible quality, subtle behavior,
  data safety, performance, or architectural depth.
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
