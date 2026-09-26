# Review — Brush library against Plans 2 and 3

- Role: Reviewer (Claude). No production code was changed.
- Base commit: `5941ab1c48a384ed2d3ff073e538398da56fa98f` (`main`)
- Method: static reading of the source. This environment has no Android SDK or device, so nothing
  below was reproduced at runtime. Each finding states what a device check must confirm.
- Severity scale: `docs/ACCEPTANCE_CRITERIA.md` (S1 = data loss, legal or security; S4 = cosmetic)

## Summary

| ID | Sev | Area | Finding | Evidence |
|----|-----|------|---------|----------|
| C1 | **S1 (legal/release)** | Assets | Bundled textures and the brush taxonomy appear to come from a commercial app | below |
| H1 | S2 | Canvas engine | Symmetry sends mirrored points into the **same** spline window as the real stroke | `DrawingView.kt:3670-3694` |
| H2 | S2 | Panel geometry | The brush panel's width is set twice, bypassing the safe clamp; the panel can be positioned off-screen on portrait phones | `BrushPanel.kt:74-115`, `PanelUi.kt:479-509` |
| M1 | S3 | Preview | Previews are not deterministic (the RNG is seeded from the clock) | `BrushEngine.kt:133` |
| M2 | S3 | Performance | List previews render synchronously on the UI thread when not cached | `BrushPanel.kt:728-742` |
| M3 | S3 | Truthfulness | Preview width ignores the preset's real size | `BrushPreviewRenderer.kt:124-138` |
| M4 | S3 | Accessibility | Compact category rows are 34–42dp tall | `BrushPanel.kt:530-535` |
| M5 | S3 | Plan 2 gaps | No preview golden tests, frame telemetry or input prediction | test tree |
| L1 | S4 | Identity | Emoji in set names; the cover title shows them | `BrushLibrary.kt:631`, `BrushPanel.kt:453` |
| L2 | S4 | Icons | Category icons are guessed from substrings of the set id | `BrushPanel.kt:605-612` |
| L3 | S4 | Brand | Package, namespace and root project are still named `procreate`, and brush ids use `proc_` | `app/build.gradle:53`, `settings.gradle` |

## Findings

### C1 — Third-party asset provenance (S1, release blocker)

- `app/src/main/assets/brushes/` holds about 24 MB: 233 textures, 125 thumbnails, and
  `brushes_metadata.json` with 217 `RawName` entries (for example `6B-Compressed`, `Artist-Crayon`,
  `Bamboo`, `Polar-Glow`).
- The built-in set list (`BrushLibrary.kt:630-2383`: Sketching, Inking, Drawing, Painting, Artistic,
  Calligraphy, Airbrushing, Textures, Abstract, Charcoals, Elements, Spraypaints, Touchups, Retro,
  Luminance, Industrial, Organic, Water, Earth) follows a commercial app's default library in order.
  Several brushes were renamed but kept ids such as `proc_peppermint` and the original textures.
- `AI_CONTEXT_NOTE.md` says textures and icons were extracted from that app's IPA.
- `AGENTS.md` forbids third-party proprietary assets, and `versionCode 14` has already been uploaded
  to Play Internal Test (`app/build.gradle:63-66`).
- **Required:** a provenance inventory (spec 0A2) that marks each file as original, licensed (with the
  licence recorded) or replace. Until replacements exist, stop any public or Play distribution.
  Remove `AI_CONTEXT_NOTE.md` guidance that tells agents to extract more textures from the IPA.
- **Repository exposure:** the GitHub repository was made public on 2026-09-26 for this review. It
  should be made private again.

### H1 — Symmetry corrupts strokes (S2)

`DrawingView` calls `brushEngine.strokeTo` for the real point and then again for each mirrored point
on the **same** `BrushEngine` instance. `strokeTo` pushes into one shared 4-point Catmull-Rom window
(`BrushEngine.kt:265-280, 318-338`) and draws the span between points 1 and 2. With symmetry on, the
window therefore alternates between original and mirrored samples, so the engine paints spans that
**join the real stroke to its reflection**: long diagonal or horizontal streaks across the axis. The
mirrored calls also drop tilt and azimuth. `endStroke` flushes only the last mirrored position.

- Device check: turn on vertical symmetry and draw a slow curve away from the axis. Expect streaks
  that cross the axis.
- Fix direction (for a spec, not this review): one `BrushEngine` per symmetry branch (1, 2 or 4),
  each with its own spline state, all started, fed and ended together.
- This may also explain some "two strokes" reports (see 0D, E8e).

### H2 — Panel geometry system is duplicated (S2)

`PanelUi.dockSheet` clamps the width to at most display − 24dp (and 52% on short phones).
`BrushPanel.onStart` then calls `window.setLayout(max(330dp, 35.5%·width), …)` again, and places
`x = displayWidth − panel − gutter(64–94dp)` with `Gravity.LEFT`. At 360dp that is 360 − 330 − 64,
so x is negative. Whether the window manager clamps or clips this must be checked on a device.
Either way the result isn't what the shared helper intends. This is covered by the revised 0D scope
item 1.

### M1 — Previews not deterministic (S3)

`private val rng = Random(System.nanoTime())`. Every preview of a brush with scatter or jitter
changes on re-render, which makes golden tests impossible (Plan 2 deliverable "deterministic
single-stroke previews"). Fix: an injectable seed for preview engines.

### M2 — UI-thread preview rendering (S3)

On a cache miss (a cold panel, or a new size after rotation), `bindPreview` → `getPreview` →
`renderStrokePreview` runs roughly 92 spline steps with textured stamps inside `onBindViewHolder`.
Several rows per frame on fling will drop frames. `AGENTS.md` requires input to stay responsive while
panels or previews are active. Measure it with `FrameMetrics` while flinging a cold library.

### M3 — Preview size normalisation (S3, product decision)

`previewSize` is a fixed fraction of the row height per brush type, and `engine.properties.size` is
overwritten. A 6px technical pen and a 120px wash look the same width. That keeps rows comparable but
conflicts with "must truthfully represent the preset". Suggestion: keep the normalisation and add a
small size indicator, or scale with a log curve of the real size. The product owner decides.

### M4 — Touch targets (S3)

Compact category rows are `(screenHeightDp·0.07).coerceIn(34, 42)` dp, below the 44dp release
requirement. Brush rows (54–66dp) pass.

### M5 — Plan 2 infrastructure gaps (S3)

Unit tests exist for smoothing, stylus response, colour flow and pencil grain, but there is:
no preview-vs-canvas golden render test; no connected-component test for "exactly one stroke"; no
frame-time or input-latency telemetry (`FrameMetrics`/`Choreographer` are unused); and no
`MotionEventPredictor`. These are expected Plan 2A/2B deliverables and are recorded so 2A starts
from this baseline.

### L1 to L3 (S4)

- L1: `bindCover` uses `set.name.trim()`, and its comment says the emoji is dropped, but it is not.
  The rail strips emoji with a regex, so the two surfaces disagree.
- L2: `categoryIcon(id)` maps by substring: `water → ic_blur`, `air/spray → ic_smudge`,
  `texture/charcoal/earth → ic_image`, everything else → `ic_brush`.
- L3: `namespace 'com.procreate.android'`, `rootProject.name = "ProcreateAndroid"`, and
  `applicationId "artify.com"`, which is a reversed-domain oddity; changing it after Play upload
  creates a new app listing. Renaming the namespace is safe, but changing `applicationId` is not.

## Items verified as already meeting criteria

- Selecting a brush keeps the panel open, and outside tap and Back dismiss it (`BrushPanel.kt:85,474-481`).
- The list-row preview path issues exactly one stroke per row (`BrushPreviewRenderer.kt:178-194`).
- Recycled rows are guarded against receiving another brush's bitmap (`BrushPanel.kt:729-741`).
- The preview cache is bounded (LRU of 56 strokes and 128 icons), and evicted bitmaps are not recycled
  while still displayed.

## Gate status

**Not approved for release.** C1 blocks distribution. H1 and H2 should be fixed before the 0D
golden screenshots are captured, or those baselines will record the defects.
