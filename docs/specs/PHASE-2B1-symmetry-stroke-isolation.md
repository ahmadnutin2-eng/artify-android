# Phase 2B1 — Symmetry stroke isolation (defect fix)

## Specification metadata

- Status: Draft. Needs product-owner approval. Branch name when implemented: `fix/2B1-symmetry-strokes`.
- Planner owner: Claude (Planner). Source: review finding H1 in
  `docs/reviews/REVIEW-2026-09-26-brush-library.md`.
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Dependencies: none. Schedule it before 0D's golden screenshots and before 0B's stroke baselines, or
  those baselines will record the defect.
- Affected areas: `canvas/DrawingView.kt` (the stroke path only: `ACTION_DOWN/MOVE/UP` drawing
  branch, `feedSample`, `settleStabilizerAtLift`, the tap-dot branch), `canvas/BrushEngine.kt`
  (no behaviour change; reuse only), new `canvas/SymmetryStroke.kt`, new unit tests.

## Problem and user value

With symmetry on, every sample is fed to **one** `BrushEngine` twice (vertical/horizontal) or four
times (quad): first the real point, then each reflection (`DrawingView.kt:3670-3694`). The engine
keeps a single 4-point Catmull-Rom window (`BrushEngine.kt:141-152, 265-280, 318-338`) and paints
the span between its middle points. With interleaved samples it therefore paints spans **from the
real stroke to its reflection and back**, which shows up as straight streaks across the symmetry
axis. The reflected calls also drop tilt and azimuth, so barrel-following nibs don't mirror. A tap
lays only the real dot (`DrawingView.kt:3427`), and the lift flush (`endStroke`) only finishes
whichever branch was pushed last.

Symmetry is a headline feature for ornament and Arabic geometric work, so a broken symmetry stroke is
a primary-workflow defect (S2).

## Current evidence

- Reachable from `ActionsPanel.kt:337` (`drawingView?.symmetryMode = next`).
- The guide lines are drawn at the canvas centre (`DrawingView.kt:4875-4888`), and reflection uses
  `target.width/2` and `target.height/2` (`:3676-3679`).
- Static reading only. **Step 0 of the Executor is a device reproduction** (a vertical symmetry slow
  curve 200px from the axis), with a screenshot attached to the evidence section.

## Scope

1. **One engine per branch.** Add `SymmetryStroke`, which owns 1, 2 or 4 `BrushEngine` instances. The
   primary engine is the existing `brushEngine`, and the mirror engines copy its `properties`,
   `color`, `renderScale`, `stylusAzimuthAvailable` and `beforeWrite` at `ACTION_DOWN`. Each branch
   has its own spline window, distance accumulator and dirty rect.
2. **Correct reflection of every axis.**
   - Position: x′ = 2c<sub>x</sub> − x and/or y′ = 2c<sub>y</sub> − y.
   - Tilt: unchanged (magnitude).
   - Azimuth: for a vertical mirror, θ′ = π − θ; for a horizontal mirror, θ′ = −θ; for quad, apply
     both. Wrap to (−π, π].
   - The stroke direction angle used by `orientToStroke` follows automatically from the reflected
     positions.
3. **Taps and lifts.** A tap stamps one dot per branch. At lift, `settleStabilizerAtLift` and
   `endStroke` run for every branch.
4. **A stable axis during a stroke.** Capture c<sub>x</sub> and c<sub>y</sub> at `ACTION_DOWN`. If an open
   canvas grows mid-stroke, shift the captured centre by the same offset that
   `offsetActiveStroke(dx, dy)` applies (`BrushEngine.kt:251-258`), so the axis stays glued to the
   artwork rather than to the new bitmap centre.
5. **Smudge and blur.** They mirror through the same per-branch "last point" state (today they use a
   single `lastSmudgeX/Y`). The Kufic grid pen mirrors cell fills. QuickShape is out of scope and is
   documented as "not mirrored" in the UI hint.
6. **Undo and collaboration.** Keep one undo step per gesture: the union of all branches' dirty rects
   feeds `strokeDirty`, and `commitPixelEdit` is called once. The collaboration patch already covers
   the whole of `strokeDirty`, so the partner receives the mirrored pixels too. Add a test for that.

## Non-goals

- New symmetry modes (radial n-fold, rotational), a movable axis, or mirroring QuickShape.

## Technical design

```kotlin
internal class SymmetryStroke(private val primary: BrushEngine) {
    private val mirrors = ArrayList<BrushEngine>(3)      // reused between strokes, no per-stroke alloc
    fun begin(mode: SymmetryMode, cx: Float, cy: Float, x: Float, y: Float, p: Float, tilt: Float, az: Float)
    fun strokeTo(canvas: Canvas, target: Bitmap, x: Float, y: Float, p: Float, v: Float, tilt: Float, az: Float)
    fun stampDot(canvas: Canvas, target: Bitmap, x: Float, y: Float, p: Float, tilt: Float, az: Float)
    fun end(canvas: Canvas?, target: Bitmap?)
    fun offset(dx: Float, dy: Float)                     // open-canvas growth
    fun unionDirty(out: RectF)
}
```

- The reflection maths lives in a pure function `SymmetryMath.reflect(mode, cx, cy, x, y, az)`, which
  returns up to 3 `(x, y, az)` triples. It is unit-tested on the JVM without `android.graphics`, the
  same way `StylusResponseTest` is.
- Mirror engines must use seeds derived from the primary engine's seed (see 0D E7), so jitter differs
  per branch but stays deterministic in tests.
- Performance: quad mode quadruples stamping. Budget it against 0B's `Stroke.process` p95. If it
  exceeds the target, the mitigation is to coalesce the dirty-rect invalidation (already per batch).
  Stamping is not reduced.

## Data migration and rollback

None. The fix reverts as one commit.

## Risks and mitigations

- **Mirrors sharing `beforeWrite` capture undo tiles 4×.** Undo tiles are keyed by tile. The
  Executor must verify that `captureBeforeEdit` is idempotent per tile within one gesture, and add a
  test if it is not.
- **Brushes with `wetness` sample the destination.** Each branch samples its own location, which is
  correct and needs no special case.

## Acceptance criteria

1. With vertical, horizontal and quad symmetry, a slow curve drawn at least 150px from both axes
   leaves **no** painted pixels in a 40px-wide band around the axis. The check is an instrumented
   test that composites the stroke into a bitmap and scans the band.
2. Each mirrored branch is a pixel-exact reflection of the primary branch when jitter is 0 (within
   one pixel of anti-aliasing tolerance).
3. A barrel-following reed brush draws mirrored thick and thin strokes correctly (the azimuth
   reflection test in `SymmetryMathTest`).
4. A tap produces 2 or 4 dots, and a lift flushes every branch (the end segment is present on all
   branches).
5. One gesture creates exactly one undo step, and undo restores all branches.
6. On an open canvas that grows during a symmetry stroke, the axis stays at the artwork position
   captured at stroke start.
7. Quad-mode `Stroke.process` p95 is recorded in the evidence and stays within the 0B target, or has
   a documented waiver.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `SymmetryMathTest` (positions, azimuth wrap, quad composition) | JVM | AC2, AC3 |
| `SymmetryStrokeInstrumentedTest` (axis band scan, mirror equality, tap, lift, undo) | instrumented | AC1, AC2, AC4, AC5 |
| `SymmetryOpenCanvasGrowthTest` | instrumented | AC6 |
| `StrokeBenchmark` quad variant (0B) | macrobenchmark | AC7 |

## Device/manual test matrix

On the phone and the stylus tablet: slow and fast strokes in all three modes; a tap; smudge, blur and
eraser with symmetry; undo and redo; save and reopen; a live collaboration session in which the
partner sees the mirrored result.

## Adjacent finding (not in scope, recorded for triage)

QuickShape commits with `strokeWidth = properties.size * currentScale()` and draws onto the layer
canvas (`DrawingView.kt:3400-3412`). The layer canvas is in canvas pixels, so multiplying by the view
zoom makes a snapped shape's line weight depend on the zoom level at which it was drawn. It also uses
a plain `Paint`, not the brush engine, so textured brushes lose their texture on snap. Proposed as
Plan 2B2.

## Implementation evidence / Review outcome / Sign-off

To be completed per `docs/specs/README.md`.
