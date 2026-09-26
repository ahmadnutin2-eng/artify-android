# Phase 1A — Document transaction model (atomic save and safe reopen)

## Specification metadata

- Status: Draft. Needs product-owner approval.
- Planner owner: Claude, standing in as Planner while the Codex Cloud quota is unavailable
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent review (must not be the implementing agent)
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Implementation head SHA: TBD
- Dependencies: none blocking. It may run in parallel with 0D because the source areas don't overlap.
- Affected source areas: `project/ProjectDocumentStore.kt`, `project/ProjectDocument*.kt`,
  `ui/canvas/CanvasActivity.kt` (`saveArtwork`, `loadArtworkWithoutBlockingUi`,
  `loadEditableProject` only), `canvas/CanvasViewModel.kt` (snapshot API only), `ArtifyApplication.kt`,
  `database/ArtworkDatabase.kt`
- Follow-ups (separate specs): 1B autosave/lifecycle cadence, 1C recovery and corruption UX,
  1D migration and failure-injection suite

## Problem and user value

A drawing app that can lose or silently damage artwork cannot charge money. Each file is currently
written atomically on its own, but **a save as a whole is not atomic**, and several code paths can
permanently replace a layered project with a damaged or flattened one.

## Current evidence (code inspection at base commit)

Line numbers refer to the base commit.

| # | Severity | Defect | Location |
|---|----------|--------|----------|
| D1 | **S1** | **Live bitmaps are saved while still being edited.** `saveArtwork` passes the layers' own mutable `Bitmap`s to an IO coroutine and returns. The UI thread keeps drawing into them while `compress()` and `LayerCompositor.draw` read them, which can store a half-drawn stroke. `CanvasViewModel` also recycles replaced bitmaps on the main thread (`:202`, `:448`), so the background save can hit a recycled bitmap and fail. | `CanvasActivity.kt:670-699`, `CanvasViewModel.kt:202,448` |
| D2 | **S1** | **A failed load leads to the layered project being overwritten.** If `loadEditableProject` throws for any reason (one missing layer, a size mismatch), the activity silently falls back to the flattened gallery PNG as a single layer. The next edit plus `onPause` saves that single layer over `project.json` under the same project key. The layered document is gone, and its layer files are orphaned. | `CanvasActivity.kt:230-247` then `:644-684` |
| D3 | **S1** | **Saves can mix old and new files.** Layer PNGs are overwritten in place at fixed paths (`layers/<hash(layerId)>.png`), and `project.json` is written last. A process kill between them leaves the old JSON pointing at a mix of new and old layer pixels. If the canvas was resized or cropped, the old JSON's `pixelWidth/Height` no longer match the new PNG, and `loadLayerBitmap` throws. That throw then triggers D2. | `ProjectDocumentStore.kt:46-85`, `:129-135` |
| D4 | S2 | The flattened image and thumbnail are written with a plain `FileOutputStream`, which is not atomic. For **legacy artworks** (`documentPath == null`) that PNG *is* the artwork, so a kill mid-write destroys it. | `CanvasActivity.kt:702-712` |
| D5 | S2 | The only save trigger is `onPause`. A crash or ANR while drawing loses everything since the last pause. (Covered by 1B; noted here because 1A must provide a snapshot API cheap enough for 1B to call often.) | `CanvasActivity.kt:632-635` |
| D6 | S3 | Layer files for deleted layers are never removed, so projects grow without bound. | `ProjectDocumentStore.save` |
| D7 | S3 | There is no free-space check before writing, and a failure only shows a hard-coded Arabic toast. There is no retry and no persistent "unsaved" indicator. | `CanvasActivity.kt:738-747` |
| D8 | S3 | `Artwork.createdAt` is overwritten with `savedAt` on every save. | `CanvasActivity.kt:722` |
| D9 | S3 | Room uses `exportSchema = false` and `fallbackToDestructiveMigrationFrom(true, 1)`, so there are no schema snapshots to test migrations against. | `ArtworkDatabase.kt:71,111` |

What is already good and must be kept: per-file `AtomicFile`; path-traversal checks
(`resolveAsset`, `ownedDocumentFile`); schema-version decoding (`ProjectDocumentCodec.kt:37-60`); the
revision counter that only marks the exact saved revision clean (`CanvasViewModel.kt:130-135`); the
single app-scoped save mutex.

## Scope

1. **Immutable save snapshot (fixes D1).** On the main thread, `CanvasViewModel.createSaveSnapshot()`
   copies each layer's bitmap and mask (`Bitmap.copy`, or `asShared()` on API 31+ when the source is
   frozen) together with the revision number. The background save uses only that snapshot. The UI
   thread cost is measured and budgeted (see AC6).
2. **Generation-based commit (fixes D3 and D6).** Every save writes into a new generation directory
   `generations/<n>/` (layers, masks, flattened preview, thumbnail, `project.json`). The commit point
   is one atomic write of a small pointer file `HEAD` (`{"generation": n, "sha256": "..."}`). After a
   successful commit, generations older than *n − 1* are deleted, so the previous valid generation
   always stays as the recovery copy. Unchanged layers can be hard-linked or copied from the previous
   generation; at this stage a copy is acceptable.
3. **Safe load and no silent downgrade (fixes D2).** `load()` reads `HEAD`, verifies every asset of
   that generation, and on failure tries generation *n − 1*. If both fail, the activity opens the
   flattened preview **read-only, marked as a recovery copy**, and saving it creates a **new** project
   key. It never writes over the damaged project, and the damaged directory is kept for 1C.
4. **Legacy flattened artworks (fixes D4).** The first open of a legacy artwork converts it into a
   generation-0 project under a new key. The legacy PNG is never rewritten.
5. **Pre-flight and reporting (fixes D7 and D8).** Before writing, check that free space is at least
   2 × the estimated uncompressed size (w × h × 4 × layers), or at least 64 MB. On failure keep the
   document dirty, retry with backoff, and show a persistent in-canvas "not saved" state from
   string resources in Arabic and English. `createdAt` is set once.
6. **Storage-layer tests** (see matrix) that run on the JVM without a device wherever possible.

## Non-goals

- Autosave cadence and idle detection (1B), the recovery chooser UI (1C), Room migration testing and
  the failure-injection harness (1D), and cloud sync (Plan 6).
- Changing the `project.json` field schema beyond what the generation layout needs. The document
  schema version increments by one, and the old layout stays readable.

## Technical design

```
project_documents/<projectKeyHash>/
  HEAD                          # atomic pointer: {"generation":7,"sha256":"<of project.json>"}
  generations/6/project.json    # previous valid generation (recovery copy)
  generations/6/layers/*.png
  generations/7/project.json    # current
  generations/7/layers/*.png
  generations/7/preview.png
  generations/7/thumb.png
  quarantine/                   # 1C: damaged generations moved here, never deleted automatically
```

- Write order inside one save: write every file into `generations/<n+1>/` (a plain write is
  sufficient here because nothing references it yet); `fsync` each file and the directory; compute
  the SHA-256 of `project.json`; write `HEAD` atomically; then remove generations older than *n*.
- **Crash model:** a kill at any point leaves `HEAD` naming a complete generation. A partial
  `generations/<n+1>` without `HEAD` pointing to it is garbage and is cleaned on the next open.
- **Reading the old layout:** if `HEAD` is absent and `project.json` sits at the project root,
  treat it as generation 0 in place. The first successful save migrates it to the new layout without
  deleting the root files until `HEAD` is committed.
- Room `Artwork.documentPath` keeps pointing to the project directory's stable path. The gallery
  thumbnail path becomes `generations/<n>/thumb.png`, resolved through `HEAD` by a small
  `ProjectPaths` helper, so Room never points at a file that will be deleted.
- The snapshot copy happens on the main thread by design, so the copy is consistent. If AC6 fails on
  the 20-layer 4096² scenario, the fallback is copy-on-write per dirty tile using the existing
  undo-tile machinery (`CanvasViewModel.snapshotUndoTile`). The Executor must report which approach
  was used.

## Data migration and rollback

- Forward: old-layout projects open unchanged and migrate on their first save.
- Rollback: an older build cannot read the generation layout. To make rollback safe, this phase
  **also writes a root-level `project.json` copy of the current generation** for one release cycle,
  and only the new build writes `HEAD`. Remove that compatibility copy in 1D.
- Legacy PNG artworks are never modified.

## Risks and mitigations

- **Storage doubles temporarily during a save.** Mitigated by the pre-flight free-space check and by
  keeping only two generations.
- **Main-thread snapshot cost on large canvases.** Measured (AC6), with a documented tile-level
  fallback.
- **Hard-coded paths elsewhere** (export, collaboration, gallery). All must go through `ProjectPaths`,
  and a grep test forbids `"project.json"` literals outside `project/`.

## Acceptance criteria

1. 100/100 automated save → reopen cycles on a 3-layer document with a mask preserve layer order,
   names, visibility, opacity, blend mode, and pixel-exact layer content.
2. Kill injection at each of the checkpoints *after layer k written*, *after project.json written*,
   *after fsync*, *before HEAD*, *after HEAD* and *during cleanup*: reopening yields either the
   complete previous revision or the complete new one, never a mix (checked by per-layer checksums).
3. A document whose current generation has one missing or undersized layer opens the previous
   generation and reports it. The damaged generation is kept, and the project key is never
   overwritten by a flattened fallback.
4. Opening a legacy flattened artwork and saving never modifies the original PNG (unchanged checksum).
5. Drawing a stroke **while** a save is running never changes the saved pixels of that save (checked
   by the snapshot checksum), and no "recycled bitmap" failure occurs across 1,000 randomized
   save/draw/undo interleavings.
6. The snapshot's UI-thread cost is at most 8ms p95 for 2048² × 5 layers on the reference flagship.
   The 4096² × 20 scenario is reported with its measured value, and the tile fallback is used if it
   exceeds 16ms.
7. With simulated low storage, the save is refused before any write, the document stays dirty, and a
   visible localized state is shown. The previous generation is untouched.
8. After 50 saves that add and delete layers, the project directory contains at most two generations
   and no orphan files.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `ProjectGenerationStoreTest`: commit, crash checkpoints through an injectable `FileOps` fault hook, generation cleanup | JVM (Robolectric for `Bitmap`) | AC1, AC2, AC8 |
| `ProjectLoadFallbackTest`: missing, undersized or corrupt layer; corrupt `HEAD`; hash mismatch | JVM/Robolectric | AC3 |
| `LegacyArtworkImmutabilityTest` | JVM/Robolectric | AC4 |
| `SaveSnapshotConcurrencyTest`: randomized draw/undo/save interleavings | instrumented | AC5 |
| `SaveSnapshotBenchmark` | Microbenchmark | AC6 |
| `LowStoragePreflightTest` | JVM | AC7 |
| Extend the existing `ProjectDocumentValidatorTest` and `ProjectDocumentCodecInstrumentedTest` for the generation pointer | JVM/instrumented | regression |

## Device/manual test matrix

- On the reference phone: draw, then force-stop from Settings during a save (use a debug build flag
  that slows the save to 3s), and reopen. Repeat 10 times.
- Fill storage until less than 64 MB is free, attempt a save, free space, and confirm the save
  succeeds.
- Open a legacy artwork from a pre-1.9 install, draw and save. The gallery shows the new project and
  the legacy PNG is unchanged.
- Rotate and switch apps mid-stroke during a save.

## Implementation evidence

To be completed by the Executor.

## Review outcome

To be completed independently. The reviewer must re-run AC2 and AC5 rather than rely on the
Executor's report.

## Product-owner sign-off and rollback

Pending. Decisions needed: (1) accept the two-generation storage cost; (2) the wording of the
"recovery copy" state (English and Arabic).
