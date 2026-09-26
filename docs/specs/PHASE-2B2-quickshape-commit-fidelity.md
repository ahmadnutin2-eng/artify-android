# Phase 2B2 — QuickShape commit fidelity (defect fix)

## Specification metadata

- Status: Draft. Needs product-owner approval. Branch name when implemented: `fix/2B2-quickshape-commit`.
- Planner owner: Claude (Planner). Source: the "Adjacent finding" in `PHASE-2B1`, expanded here.
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Dependencies: **after 2B1**. Both touch the `ACTION_UP` branch of `DrawingView`, and only one
  branch may own a source area at a time.
- Affected areas: `canvas/DrawingView.kt` (the QuickShape commit branch around `:3393-3413`, the
  preview overlay around `:4891-4903`), `canvas/QuickShapeEngine.kt` (a new `sample()` helper),
  `canvas/CanvasViewModel.kt` (a new `restoreCapturedTiles()`), new tests.

## Problem and user value

QuickShape (hold at the end of a stroke to snap it to a line, circle, ellipse, rectangle or triangle)
is how artists draw clean guides, frames and geometric ornament. At commit time it does not honour
what the artist saw or chose.

## Current evidence (static reading at base commit; Executor step 0 reproduces each item on a device)

| # | Sev | Defect | Location |
|---|-----|--------|----------|
| Q1 | **S2** | **The hand-drawn stroke is not removed when the shape snaps.** During `ACTION_MOVE` the freehand stroke is already painted into the layer through `paintBatch` → `feedSample`. On `ACTION_UP` with `activeQuickShape` set, the code draws the perfect path **on top** and never restores the pre-stroke pixels. The result is the wobbly original **plus** the clean shape: two strokes. `captureBeforeEdit` already holds the before-image tiles for exactly that area (`CanvasViewModel.kt:504-540`), but nothing uses them to revert. | `DrawingView.kt:3328-3371` (paint), `:3393-3413` (commit) |
| Q2 | S2 | **Line weight depends on zoom.** The commit uses `strokeWidth = size * currentScale()` on the layer canvas, which is in canvas pixels. At 4× zoom the shape is 4× thicker than the brush, and at 0.25× it is a hairline. The live preview correctly uses `size * 1.05f` in canvas space, so **the preview does not match the result**. | `:3406` vs `:4896` |
| Q3 | S3 | **The brush is ignored.** The commit uses a plain `Paint`: no tip, grain, pressure, taper, colour flow, wetness or spacing. A charcoal or reed brush snaps into a flat vector-looking line. | `:3404-3412` |
| Q4 | S2 | **The eraser paints colour.** For `BrushType.Eraser`, the plain `Paint` draws `brushEngine.color` instead of removing pixels (the engine uses `DST_OUT`, `BrushEngine.kt:783-790`). | `:3404-3412` |
| Q5 | S3 | **Double opacity with uniform-coverage brushes.** `paintBatch` folds the scratch buffer at the brush opacity when `buildUp == false`, and the commit paint also sets `alpha = opacity`, so a 50% brush snaps at about 25%. | `:3410`, `paintBatch` notes at `:3706-3740` |
| Q6 | S4 | **The symmetry mode is ignored** on snap. It is documented as out of scope in 2B1, and handled here as scope item 5. | — |

Q1 is also a plausible source of the product owner's "two strokes" report (see `PHASE-0D` E8).
Investigate them together.

## Scope

1. **Revert, then render.** On snap commit, restore the captured before-tiles for the current gesture
   (`CanvasViewModel.restoreCapturedTiles(layerIndex)`, which is new and keeps the capture alive for
   the undo commit). Then render the shape. The gesture stays **one** undo step whose before-image is
   the pre-stroke state.
2. **Render the shape through the brush engine.** Sample the shape into a dense polyline
   (`QuickShapeEngine.sample(shape, spacingPx)`, with closed shapes returning to their start) and feed
   it to `BrushEngine.startStroke/strokeTo/endStroke` with the same colour, properties, render scale
   and `beforeWrite`. That fixes Q2–Q5 in one move, because size, eraser, opacity folding and texture
   all come from the one engine.
3. **Pressure along the shape.** Use the **median pressure of the freehand stroke** as a constant
   pressure, so the snapped weight matches what the artist was drawing. Optionally taper in and out
   over 4% of the length when the brush has taper enabled. Tilt and azimuth use the stroke's median
   values.
4. **Preview parity.** The preview overlay draws the sampled polyline at the width
   `BrushEngine.calculateDynamicSize(medianPressure, 0, medianTilt)` would produce (the function is
   private today; expose it as `internal fun previewSize(...)`), so preview and
   result have the same weight.
5. **Symmetry.** When `symmetryMode != NONE`, render the shape once per branch through 2B1's
   `SymmetryStroke`, and revert the captured tiles once before any branch renders.
6. **Collaboration.** The existing `finishStroke` patch covers the final pixels. Add a test that the
   partner receives only the snapped shape, not the freehand stroke.

## Non-goals

- New shape types, editing a shape after it snaps (handles, rotation), or changes to snap detection
  thresholds.

## Technical design notes

- `restoreCapturedTiles(layerIndex)` copies each `CapturedTile.before` back into the layer with
  `BlendMode.SRC`, invalidates the compositor region, and **does not** clear `editCapture`, so the
  later `commitPixelEdit` still records the correct before-image.
- Alpha Lock and uniform-coverage scratch buffers are re-initialised after the revert. Otherwise the
  scratch would still hold the freehand stroke. Reuse `resetAlphaLockBuffers()` and the scratch reset
  path already called at `ACTION_DOWN`.
- Sampling spacing: `max(0.5, stampSpacing / 2)` canvas pixels, which keeps the spline smooth on
  circles without excess stamps.

## Data migration and rollback

None. Revert the single commit to roll back.

## Acceptance criteria

1. After a snap, the pixels outside the snapped shape's stroke band match the pre-stroke layer
   exactly (instrumented pixel compare), so no freehand residue remains.
2. The committed line weight at 0.25×, 1× and 4× zoom is the same within 1 canvas pixel, and matches
   the preview weight within 1 canvas pixel.
3. The eraser plus QuickShape removes pixels along the shape and never adds colour.
4. A 50% opacity uniform-coverage brush snaps at 50% ± 2/255 (sampled at the shape's centre line).
5. A textured brush (grain on) produces a snapped stroke whose grain variance is within 20% of a
   freehand stroke with the same brush (proves the engine path).
6. One gesture is one undo step. Undo restores the pre-stroke state and redo restores the snapped
   shape.
7. With vertical symmetry, the snap produces a mirrored shape and no freehand residue on either side.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `QuickShapeSampleTest` (closure, spacing, arc length) | JVM | scope 2 |
| `QuickShapeCommitInstrumentedTest` | instrumented | AC1–AC6 |
| `QuickShapeSymmetryTest` | instrumented | AC7 |

## Device/manual test matrix

On the phone and the stylus tablet: draw and hold for each of the 5 shapes, at 3 zoom levels, with an
ink, a charcoal, a reed and the eraser; undo and redo; save and reopen.

## Implementation evidence / Review outcome / Sign-off

To be completed per `docs/specs/README.md`. Product-owner decision: whether a snapped shape should
keep the brush's taper (scope 3, optional).
