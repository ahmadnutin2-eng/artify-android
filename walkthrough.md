# Urban CAD & UX Enhancements — Walkthrough

Summary of the work in `implementation_plan.md` / `task.md`: what was already in place, what was
broken, and what was completed in this pass.

## Build-blocking issue (fixed first)

`CanvasActivity.kt` called `UrbanToolbarDock.updateActiveTool(tool)` with one argument at the
manual toolbar-button call site, but the method had been changed to require a second
`UrbanScaleConfig` parameter. One of the two call sites was updated, the other wasn't — the
project would not compile at all until this was fixed.

**Fix:** the missing argument is `drawingView.urbanScaleConfig`, which already existed and was
already being passed correctly at the *other* call site.

## Task-by-task status

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | `UrbanScaleWidget.kt` — architectural scale bar | **Already complete** | North arrow, `1:N` ratio label, alternating black/white blocks, and `config.metersToPixels()` used directly with no clamping were all already in place. |
| 2 | `UrbanGridRenderer.kt` — dimension coordinate labels | **Completed** | The labels themselves already existed, but were drawn at a fixed canvas-space size/offset - meaning at high zoom they'd balloon to fill the screen, and at low zoom shrink to unreadable specks. Added a `screenScale` parameter so label text size and edge offsets are counter-scaled against the live canvas zoom, keeping them a constant, legible size on screen the way any CAD tool's annotations behave. The two export-time call sites (flattening to a fixed-resolution bitmap) intentionally keep the default `screenScale = 1f`, since there's no live zoom to counter there. |
| 3 | Move Legend & Tables to canvas-space | **Already complete** | Already positioned before `canvas.restore()` in `DrawingView.onDraw`, anchored using canvas-space coordinates (`canvasW`/`canvasH` from the sheet bitmap), under the `// ── Canvas-Space Anchored Overlays (zoom/pan with the plan sheet) ──` comment - not inside `drawHud()`. No change needed. |
| 4 | Dual-door entry (Urban CAD vs Digital Painting) | **Completed** | `GalleryActivity` previously had a single "+" FAB that only ever led to the painting-size dialog, with Urban CAD reachable only by manually tapping a toolbar button once already inside the canvas. Added two prominent entry cards above the artwork grid (🏗️ Urban Planning / 🎨 Digital Painting). The Urban card opens `CanvasActivity` directly on a 4000×3000 blank sheet with a new `EXTRA_URBAN_MODE` intent extra; `CanvasActivity` activates Urban mode automatically on launch when that extra is set, reusing the exact same `activateUrbanMode()` logic the manual toolbar button already used (extracted into a shared method rather than duplicated). The Painting card and the original FAB both still open the existing size/background dialog unchanged. |
| 5 | Compile & assemble APK | **Completed** | `gradlew assembleDebug` succeeds. Copied to `C:\Users\ENG ALI\Desktop\Procreate_UrbanDesign.apk`. |
| 6 | Update `walkthrough.md` | **Completed** | This file. |

## Files touched in this pass

- `app/src/main/java/com/procreate/android/ui/canvas/CanvasActivity.kt` — fixed the missing-argument
  compile error; extracted `activateUrbanMode()`; added `EXTRA_URBAN_MODE` handling.
- `app/src/main/java/com/procreate/android/urban/grid/UrbanGridRenderer.kt` — added zoom-invariant
  label sizing/offsets.
- `app/src/main/java/com/procreate/android/canvas/DrawingView.kt` — passes `currentScale()` into
  the live (non-export) `UrbanGridRenderer.renderGrid` call.
- `app/src/main/java/com/procreate/android/ui/gallery/GalleryActivity.kt` — dual entry cards +
  `openUrbanWorkspace()`.
- `app/src/main/res/layout/activity_gallery.xml` — dual entry row layout.
- `app/src/main/res/values/strings.xml`, `values-ar/strings.xml` — entry card copy.

## Not touched (outside this task list's scope)

- `DrawingAudioEngine.kt` exists, is fully implemented (procedural pencil-scratch synthesis
  reacting to stroke speed/pressure, plus a pen-lift transient), but is **not wired to any touch
  event** - the object is constructed but `startStroke()`/`updateMotion()`/`endStroke()`/`release()`
  are never called anywhere. Not part of this task list; flagged separately.
- No live device test was performed for this specific pass (no device was connected at the time);
  only `gradlew assembleDebug`/compiler verification.
