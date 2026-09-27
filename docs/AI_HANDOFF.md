# Claude Code ↔ Codex handoff

This file is the shared control surface between the two assistants. Update it at every handoff and
commit the update with the work it describes.

## Active task

| Field | Value |
| --- | --- |
| Status | `CHANGES_REQUESTED` |
| Planner | Claude Code |
| Writer | Codex |
| Reviewer | Claude Code |
| Specification | `docs/specs/PHASE-0D-brush-theme-bridge.md` |
| Implementation branch | `fix/0D-brush-review` |
| Base commit | `8683fac` |
| Implementation commit | `3228527206299797244b222a8e76e40ecb5b2f2b` |
| Allowed production scope | Brush library, brush preview, brush engine support, shared panel geometry |
| Reviewed commit | `3228527206299797244b222a8e76e40ecb5b2f2b` (branch head `a4ac866` is docs-only; production code identical) |
| Review report | `docs/reviews/REVIEW-2026-09-27-0D-3228527.md` |
| Next action | Codex addresses R1–R4 (recommended: R6, R8) on `fix/0D-brush-review`, records evidence in the spec, commits, and sets `READY_FOR_CLAUDE_REVIEW` with the new SHA |

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

## Review result (2026-09-27, Claude Code, reviewed `3228527`)

- Verdict: **`CHANGES_REQUESTED`**. No S1/S2 defect was found in the code, but AC3 fails as
  written, the preview threading deviates from the spec's design, narrow-window geometry is
  unproven, and none of the required device, instrumented or screenshot evidence exists.
- Reviewer gate run: `testDebugUnitTest` re-executed with `--rerun`, giving **314 tests, 0
  failures**. `assembleDebug` and `assembleDebugAndroidTest` succeed.
- Required before re-review:
  - **R1:** verify panel geometry in split-screen at about 360dp and 640dp width, in RTL and LTR,
    and fix it if the tool rail is covered or less than 40% of the canvas stays visible.
  - **R2:** tie preview jobs to the panel's lifecycle, cancel them on rebind, show the placeholder,
    and don't swallow OOM.
  - **R3:** run `BrushPreviewDeterminismInstrumentedTest`, capture the phone and tablet screenshots,
    and fill the spec's Implementation evidence.
  - **R4:** remove emoji from the source `BrushSet.name` values and from `brush_set_my_brushes`, and
    add the AC3 test.
- Recommended: **R6** (seed from the id only, and assert seeds are unique across the library) and
  **R8** (move `gradlew` to its own 0A commit).
- Owner decisions: **R5** (the set icon family), the Studio pad split, and the 0A2
  asset-provenance gate, which still blocks `ACCEPTED`.
- **Device-check precondition (D1):** this branch has Room **v4**. The sidecar build now on tablet
  `R52WA0H7GXH` came from `feature/1C-recovery` (Room **v5**), and installing this APK over it is a
  Room downgrade that crashes on the first database open. Device-check on an integration build that
  also contains 1D, 1B and 1C, or on a freshly uninstalled sidecar. Never install on `artify.com`.
- Independence: Claude drafted most of this code before the Executor committed it, so this review
  approves nothing. A second review pass by Codex or a separate thread is recommended before
  `ACCEPTED`.

## Handoff states

`PLANNING` → `READY_FOR_CODEX` → `CODEX_IMPLEMENTING` → `READY_FOR_CLAUDE_REVIEW` →
`CHANGES_REQUESTED` or `READY_FOR_DEVICE_CHECK` → `ACCEPTED`.

