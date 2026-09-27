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
| Implementation commit | `d32acb28deae84a5eac3c32389de1f9b1d6350d2` |
| Allowed production scope | Brush library, brush preview, brush engine support, shared panel geometry |
| Reviewed commit | `d32acb28deae84a5eac3c32389de1f9b1d6350d2` (branch head `4bc5c1b` is docs-only; production code identical). Earlier review: `3228527` |
| Review report | `docs/reviews/REVIEW-2026-09-27-0D-d32acb2.md` (earlier: `docs/reviews/REVIEW-2026-09-27-0D-3228527.md`) |
| Next action | Codex fixes N1 (restore the `dockSheet` drag lock, immersive mode and animation for the brush panel) and N4 (guard), commits, then sets `READY_FOR_DEVICE_CHECK` and runs the device script on `R5CTA0M9KRK` |

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

## Review-fix evidence (Codex, 2026-09-27)

- Commit: `d32acb28deae84a5eac3c32389de1f9b1d6350d2`.
- R1: shared production geometry now preserves the rail, 40% canvas, 16dp bottom edge, and a usable
  preview column at 360dp/640dp split-screen widths.
- R2: preview rendering is owned by a view-bound scope, cancellable per row and adapter, shows a
  placeholder, cross-fades in 100ms, and logs/recovers cache references after OOM.
- R4: all built-in names and both `My Brushes` resources are pictogram-free at the source.
- R6/R7: seed identity is brush-id-only and unique across the library; tests call production
  geometry rather than duplicating it.
- Verification: 316 JVM tests passed; debug APK and Android-test APK assembled.
- Device/instrumented execution is still pending because ADB reported no connected devices. Do not
  downgrade the existing Room-v5 sidecar with this Room-v4 branch.

## Re-review result (2026-09-27, Claude Code, independent, reviewed `d32acb2`)

- Verdict: **`CHANGES_REQUESTED` (narrow)**.
  - **Resolved:** R4, R6 and R7.
  - **R2:** resolved in code. The preview scope is bound to the view, jobs are cancelled per row,
    per recycle and on adapter detach, a placeholder shows with a 100ms fade, and OOM is logged
    with the cache trimmed.
  - **R1:** resolved on paper. At least 40% of the canvas stays visible, there is a 64dp gutter and
    a preview column of at least 72dp, and window-based Configuration dimensions are used.
  - **R9:** closed.
- Reviewer gate: `--rerun` at `4bc5c1b` gave **316 JVM tests, 0 failures**. The debug app and test
  APKs assemble.
- **N1 (S3, required):** `BrushPanel` replaced `PanelUi.dockSheet` with `expandSheet`
  (`BrushPanel.kt:94`). That dropped `isDraggable = false` (`PanelUi.kt:519`), which means dragging
  or over-scrolling can now dismiss the panel. It also dropped immersive mode (`:502`) and the
  side-panel animation (`:503-507`). The six other docked panels still have all of these.
- **N4 (S4, recommended):** `checkNotNull(previewScope)` (`BrushPanel.kt:492`) can be reached from
  the fragment-scoped `repository.all` collector (`:348-366`) after `onDestroyView`. Collect on
  `viewLifecycleOwner` or guard against a null scope.
- **Device check items:**
  - **N2:** the 64dp constant is not the real rail footprint (84dp on the tablet XML, 50dp on a
    compact phone), so check for overlap in Arabic.
  - **N3:** check that a 152dp palette at a 360dp split-screen width is still usable.
  - **N5:** measure AC7 with parallel previews.
  - **R3:** run the instrumented test and capture the screenshots, both still missing.
- Connected device (read-only): `R5CTA0M9KRK`, Galaxy S22 Ultra, Android 16, about 823 × 384 dp in
  landscape. Its sidecar was installed on 2026-09-24, before Room v5, so it is likely safe for this
  Room-v4 branch. Confirm before installing.
- Still open: R5 (owner decision on the icon family), R8 (`gradlew` not split out, recommended),
  and the 0A2 gate, which blocks `ACCEPTED`.

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
