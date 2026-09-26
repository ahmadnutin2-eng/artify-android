# Plan 0C — Inventory of unfinished, duplicate and risky systems

- Role: Planner (Claude). Static inventory; nothing was changed or removed.
- Base commit: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Method: repository-wide scripted scan (declared-versus-referenced symbols, hard-coded strings,
  dialog systems, emoji, logging, manifest, network) plus a manual read of every hit listed below.
  False positives were removed by hand (for example, Room DAOs are used through generated code).
- Severity: `docs/ACCEPTANCE_CRITERIA.md` (S1 = data loss / legal / security … S4 = cosmetic).
- Rule for every "dead code" row: **do not delete in a clean-up sweep.** Each row gets an owner
  decision (wire it in, keep it as planned work, or retire it in a dedicated commit that names the
  file).

## 1. Release blockers already specified

| ID | Sev | Item | Spec |
|----|-----|------|------|
| R1 | S1 | Third-party asset provenance (356 of 372 assets listed in extraction metadata) | `PHASE-0A2` |
| R2 | S1 | Save uses live bitmaps; a failed load gets overwritten by the flattened fallback; saves can mix old and new files | `PHASE-1A` |
| R3 | S2 | Symmetry joins strokes to their reflection | `PHASE-2B1` |

## 2. Security, privacy and policy

| ID | Sev | Finding | Evidence | Route |
|----|-----|---------|----------|-------|
| P1 | **S1 (privacy/policy)** | The live relay **pairs two unauthenticated strangers** and forwards live strokes and PNG patches between them. There are no accounts, report/block controls, rate limits or moderation. This conflicts with the `AGENTS.md` rule that every network event must be authenticated, and with store policies for user-generated content. | `collaboration-server/README.md`, `server.js:21-116`, `CollaborationClient.kt:37-48` | Keep collaboration **off by default in release builds** until Plan 6A/6B. New spec needed: `PHASE-6A-collaboration-threat-model` |
| P2 | S3 | Release builds reject `ws://`, and debug-only cleartext is scoped to `src/debug`. This is **correct and should be kept.** | `CollaborationClient.kt:38-39`, `src/debug/AndroidManifest.xml:12` | none |
| P3 | S3 | User AI keys are stored in plain `SharedPreferences`. Backup and device transfer correctly exclude that file, but the key isn't encrypted at rest. | `AiKeyStore.kt:36-78`, `res/xml/*_rules.xml` | Plan 6A: Android Keystore-wrapped storage |
| P4 | S3 | The debug crash reporter sends crash text to an external LLM endpoint when a key is configured. It is gated to `DEBUG` and has an empty key in release (**correct**), but its payload rules (no artwork, no tokens) are not tested. | `debug/AiBugReporter.kt:28-57`, `app/build.gradle:93-109` | Add a unit test asserting that release `BuildConfig` keys are blank |
| P5 | S3 | The default relay is a free Render host that sleeps, which is fine for beta and not for paid use (already noted in the roadmap). | `CollaborationConfig.kt:11` | Plan 6E |

## 3. Duplicate or parallel systems

| ID | Sev | Finding | Evidence | Route |
|----|-----|---------|----------|-------|
| U1 | S3 | **Two `AdjustmentsPanel` classes.** `adjust.AdjustmentsPanel` is used by `CanvasActivity` and `LayersPanel`. `ui.canvas.AdjustmentsPanel` (208 lines) is referenced nowhere; only a comment in `ActionsPanel.kt:106` mentions it. | files named | Owner decision: retire it in its own commit |
| U2 | S2 | **The panel geometry is set twice.** `PanelUi.dockSheet` clamps the width, then `BrushPanel.onStart` overrides it. | `BrushPanel.kt:74-115`, `PanelUi.kt:479-509` | `PHASE-0D` scope 1 |
| U3 | S3 | **Five dialog mechanisms:** 19 `BottomSheetDialogFragment`, 24 `DialogFragment()`, 12 `AlertDialog.Builder`, 3 `PopupWindow`, and many ad-hoc `Dialog(` constructions. The roadmap asks for one floating-panel system. | scan counts | Plan 3A: choose the `BottomSheetDialogFragment` + `PanelUi.dockSheet` path as canonical, migrate by surface |
| U4 | S4 | `LayerManager` (in `canvas/Layer.kt`) is not used. `CanvasViewModel` owns the layers. | `Layer.kt:57` | Retire it in its own commit |
| U5 | S4 | `MeasurementOverlayView` (419 lines) is not used by any layout or code. | `urban/ui/MeasurementOverlayView.kt` | Owner decision: wire it into the measurement tool or retire it |
| U6 | S4 | `walkthrough.md` says `DrawingAudioEngine` is not wired. It **is** wired now (`DrawingView.kt:228`, 11 call sites), so the document is stale. | `DrawingView.kt:228` | Record in `STATUS.md` (done) |

## 4. Internationalisation and accessibility

| ID | Sev | Finding | Evidence | Route |
|----|-----|---------|----------|-------|
| I1 | S3 | **38 toasts are hard-coded in Arabic.** An English-locale user sees Arabic messages, including the save-failure message. | e.g. `CanvasActivity.kt:743, 833-920, 1045-1165` | Plan 3C: move them to `strings.xml` and `values-ar` |
| I2 | S3 | **About 154 hard-coded UI strings** (`text =`, `setText`, `setTitle`, `contentDescription =`). The biggest offenders are the Urban dialogs (21, 16, 16), `CanvasActivity` (13), `PlanAnalysisDialog` (11) and `SubscriptionDialog` (11). | scan | Plan 3C. The billing dialog is urgent because pricing text must be localised |
| I3 | S4 | **Emoji and dingbats are used as icons or labels:** `brush_set_my_brushes` "🖼️" (both locales); "✕" close buttons (`CanvasActivity.kt:1636,1878`; `UrbanToolbarDock.kt:354`); "✓"/"✕"/"⚙"/"✏"/"✢" labels in Urban UI; emoji prefixes in 23 brush set names. `AGENTS.md` forbids emoji as production navigation icons. | lines named | `PHASE-0D` (brush) and Plan 3B (the rest) |
| I4 | S3 | Compact brush-category rows are 34–42dp tall (touch target below 44dp). | `BrushPanel.kt:530-535` | `PHASE-0D` |

## 5. Documentation drift

| ID | Finding | Route |
|----|---------|-------|
| D1 | `AI_CONTEXT_NOTE.md` says "not compiled yet" and instructs extraction from the reference IPA. | A superseding notice was added at the top this round, with nothing removed |
| D2 | `AGENT_WORKFLOW.md` "Cloud connection prerequisites" says the project is not a git repository; it now is. | Tracked in `STATUS.md`. Update when 0A closes |
| D3 | There is no POSIX `gradlew` script; only `gradlew.bat` is committed. A Linux Codex Cloud environment cannot run the documented gates. | 0A task: run `gradlew.bat wrapper` on Windows and commit the generated `gradlew` with its executable bit |

## 6. Checked and healthy (keep)

- Project path-traversal defence (`ProjectDocumentStore.resolveAsset`/`ownedDocumentFile`).
- Revision-exact dirty flag (`CanvasViewModel.markProjectSaved`).
- Backup exclusion of AI keys on both backup paths.
- Release build blocks cleartext collaboration.
- Bounded preview caches.
- 29 JVM unit-test classes and 2 instrumented test classes already exist across vectorize, urban, DXF, adjust,
  layers, billing, project, brushes and canvas. Plan 0B extends these rather than replacing them.
