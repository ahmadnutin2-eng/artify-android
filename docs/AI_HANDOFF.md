# Claude Code ↔ Codex handoff

This file is the shared control surface between the two assistants. Update it at every handoff and
commit the update with the work it describes.

## Active task

| Field | Value |
| --- | --- |
| Status | `READY_FOR_CLAUDE_REVIEW` |
| Planner | Claude Code |
| Writer | Codex |
| Reviewer | Claude Code |
| Specification | `docs/specs/PHASE-0D-brush-theme-bridge.md` |
| Implementation branch | `fix/0D-brush-review` |
| Base commit | `8683fac` |
| Implementation commit | `3228527206299797244b222a8e76e40ecb5b2f2b` |
| Allowed production scope | Brush library, brush preview, brush engine support, shared panel geometry |
| Next action | Claude reviews commit `3228527` without editing production source |

## Required review checks

- One deterministic preview stroke per brush; no duplicate stroke.
- Brush preview remains truthful for grid, spray, grain, scatter, water, and Arabic nib presets.
- Panel geometry remains usable on small landscape phones and tablets.
- Icons and set names follow one visual family and contain no emoji navigation symbols.
- Unit tests and debug sidecar build remain green.
- Any rendering or layout concern that cannot be proven in JVM tests is marked for real-device
  verification, not guessed as accepted.

## Executor evidence

- Command: `& .\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' '-Partify.sidecar=true'`
- Result: `BUILD SUCCESSFUL` on 2026-09-27.
- Remote branch: `origin/fix/0D-brush-review`.
- Commit: `3228527206299797244b222a8e76e40ecb5b2f2b`.

## Handoff states

`PLANNING` → `READY_FOR_CODEX` → `CODEX_IMPLEMENTING` → `READY_FOR_CLAUDE_REVIEW` →
`CHANGES_REQUESTED` or `READY_FOR_DEVICE_CHECK` → `ACCEPTED`.

