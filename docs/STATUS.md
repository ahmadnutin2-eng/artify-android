# Artify status board

Single source of truth for what is planned, in progress and accepted. Update this file in the same
commit as any spec status change. Last updated **2026-09-26** by Claude (Planner), base
`5941ab1c48a384ed2d3ff073e538398da56fa98f`.

## Where things stand

- The repository exists on GitHub (`main`, one commit). Codex stopped after creating it; its quota
  ran out before the first cloud tasks.
- Claude (Anthropic) has taken the Planner and Reviewer roles for this round. Every item below is a
  **specification or review**. No production source has been changed.
- **Release is blocked** by 0A2 (asset provenance) and 1A (data-loss defects).

## Board

| ID | Title | Kind | Status | Blocks / depends | Doc |
|----|-------|------|--------|------------------|-----|
| 0A | Repository and clean clone; commit POSIX `gradlew` | infra | **Open**: `gradlew` missing (0C D3) | blocks 0B CI | `AGENT_WORKFLOW.md` |
| 0A2 | Asset provenance and original replacement library | spec + tools | **Draft**: awaiting owner decisions | **blocks release**, 0D, Plan 4 | `specs/PHASE-0A2-asset-provenance.md` |
| 0B | Baseline measurement harness | spec | Draft | needs 0A | `specs/PHASE-0B-baseline-measurement.md` |
| 0C | Inventory of unfinished/duplicate/risky systems | baseline | **Done** (for review) | feeds 3A–3C, 6A | `baseline/INVENTORY-0C-2026-09-26.md` |
| 0C-T | Design token sheet (measured + proposed) | baseline | **Done** (for review) | feeds 3B | `baseline/DESIGN-TOKENS.md` |
| 0D | Brush library and theme bridge | spec | Draft rev 2 | needs 0A2, 2B1 first | `specs/PHASE-0D-brush-theme-bridge.md` |
| 1A | Document transaction model (atomic save, safe reopen) | spec | Draft: **highest priority to execute** | — | `specs/PHASE-1A-document-transaction-model.md` |
| 1B | Autosave and lifecycle | spec | Draft | needs 1A | `specs/PHASE-1B-autosave-lifecycle.md` |
| 1C | Recovery and corruption UX, safe delete | spec | Draft | needs 1A, 1B, 1D (Room v5) | `specs/PHASE-1C-recovery-and-corruption-ux.md` |
| 1D | Migration tests and failure injection | spec | Draft | needs 1A (`FileOps`) | `specs/PHASE-1D-migration-and-failure-injection.md` |
| 2B1 | Symmetry stroke isolation (defect) | code | **Implemented** on `fix/2B1-symmetry-strokes`: JVM + device tests pass; awaiting review | before 0B stroke baseline | `specs/PHASE-2B1-symmetry-stroke-isolation.md` |
| 2B2 | QuickShape commit fidelity: freehand residue, zoom-dependent weight, eraser paints colour | code | **Implemented** on `fix/2B2-quickshape-commit` (stacked on 2B1): JVM + device tests pass; awaiting review | after 2B1 (same code area) | `specs/PHASE-2B2-quickshape-commit-fidelity.md` |
| 6A | Collaboration threat model + pre-release hardening (part A) | spec | Draft: part A **blocks public release of "Online"** | — | `specs/PHASE-6A-collaboration-threat-model.md` |
| R-1 | Review: brush library vs Plans 2/3 | review | **Done** | — | `reviews/REVIEW-2026-09-26-brush-library.md` |

## Recommended execution order for the Executor

1. **1A**, because data loss is S1 and the roadmap puts Plan 1 first. Its source areas don't overlap
   with 2B1, so these two can run in parallel on separate branches.
2. **2B1**, a small, contained fix to the drawing path, then **2B2** on the same area.
   **6A part A**: the relay-server items (scope 5) can run in parallel right away. The Android
   items touch `CanvasActivity`/`CanvasViewModel` like 1A, so start them after 1A merges.
3. **0A** `gradlew` + **0B** harness, so every later phase has baselines.
4. **1D**, then **1B**, then **1C**.
5. **0A2** replacements (after the owner's decisions), then **0D**.

Only one implementation branch may own a source area at a time (`AGENT_WORKFLOW.md`). 1A and 1B both
touch `CanvasActivity.saveArtwork`, so run them sequentially.

## Decisions waiting for the product owner

| # | Decision | Where |
|---|----------|-------|
| 1 | Fill the `decision` column for the 372 assets | `docs/provenance/asset-inventory.csv` |
| 2 | Approve or reject each Texture Lab texture | `docs/provenance/texture-lab-sheet.png` |
| 3 | Approve the proposed Artify brush taxonomy | `PHASE-0A2` scope 4 |
| 4 | Withdraw the internal Play build that contains the assets? | `PHASE-0A2` risks |
| 5 | Disable random-stranger collaboration in release until 6A? | `INVENTORY-0C` P1 |
| 6 | Accept the two-generation storage cost and the recovery wording | `PHASE-1A`, `PHASE-1C` |
| 7 | The autosave idle delay (3s proposed) and indicator placement | `PHASE-1B` |
| 8 | Keep or remove the dark/light split in the Brush Studio pad | `PHASE-0D` |
| 9 | Name the reference devices | `PHASE-0B` scope 1 |
| 10 | Keep the repository **private** | — |

## Ready-to-paste Executor prompt (next task)

```text
Role: Executor. Implement only docs/specs/PHASE-1A-document-transaction-model.md from base
5941ab1c48a384ed2d3ff073e538398da56fa98f on branch feature/1A-document-transactions. Preserve
unrelated changes; do not delete files outside the spec's scope. Run
.\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' '-Partify.sidecar=true', install the
sidecar APK, run the device checks in the spec, and fill its "Implementation evidence" section
(base/head SHA, commands, results, device, metrics, deviations, remaining risks). Push the branch
and stop; an independent Reviewer will review it.
```

## Stale documents (kept for history; do not follow)

- `AI_CONTEXT_NOTE.md`: superseded; notice added at the top.
- `walkthrough.md`: `DrawingAudioEngine` is now wired (`DrawingView.kt:228`).
- `AGENT_WORKFLOW.md` "Cloud connection prerequisites" step 1–3: the repository now exists.
