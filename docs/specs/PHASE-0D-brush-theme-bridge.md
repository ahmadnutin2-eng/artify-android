# Phase 0D — Brush library and theme bridge

## Specification metadata

- Status: Draft (revision 2, code-evidence pass). Needs product-owner approval.
- Planner owner: Claude, standing in as Planner while the Codex Cloud quota is unavailable
- Executor owner: Local Codex / Antigravity agent on the Windows workstation
- Reviewer owner: Independent review (Codex Cloud or Claude, whichever did not implement)
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Implementation head SHA: TBD
- Dependencies: **0A asset-provenance gate (new, blocking; see below)**, Plan 0 screenshot protocol,
  design tokens
- Affected source areas: `brushes/BrushPanel.kt`, `brushes/BrushPreviewRenderer.kt`,
  `brushes/BrushPreviewView.kt`, `brushes/BrushStudioPanel.kt`, `brushes/BrushLibrary.kt`
  (set names and icons only), `ui/common/PanelUi.kt`, `res/drawable/ic_*`, `res/values*/strings.xml`

## Problem and user value

The brush panel does not yet read as one coherent professional product. Its proportions and tonal
hierarchy vary, its icons don't share one visual language, its geometry on small phones is
unstable, and the product owner has reported a brush preview that shows two strokes. This bridge
fixes those visible trust issues and keeps an original Artify identity.

## Current evidence (code inspection at base commit)

Line numbers refer to the base commit.

| # | Observation | Location |
|---|-------------|----------|
| E1 | The panel is sized twice. `PanelUi.dockSheet` computes a safe width (at most display − 24dp, and 52% of width on phones shorter than 520dp). `BrushPanel.onStart` then overrides it with `window.setLayout(max(330dp, 35.5% width))` plus a 64–94dp side gutter. On a 360dp-wide portrait phone, `x = width − 330dp − 64dp` is negative. | `BrushPanel.kt:74-115`, `PanelUi.kt:479-509` |
| E2 | In compact mode, category-rail rows are 34–42dp tall, below the 44×44dp target in `ACCEPTANCE_CRITERIA.md`. | `BrushPanel.kt:530-535` |
| E3 | Set names still contain emoji (e.g. `"✏️ الرسم الأولي"`). The rail strips them with a regex, but the cover title uses `set.name.trim()`, so the emoji is shown there. The code comment says it is stripped. | `BrushLibrary.kt:631`, `BrushPanel.kt:451-453`, `BrushPanel.kt:584-587` |
| E4 | Category icons are picked by substring match on the set id, so most sets fall back to the generic `ic_brush`, and "textures" maps to `ic_image`. The icons don't describe their categories. | `BrushPanel.kt:605-612` |
| E5 | Choosing a brush already keeps the panel open, and outside-tap dismissal is enabled. Long-press intentionally dismisses to open Brush Studio. Scope item "selection stays open" is therefore a **verify** item, not a build item. | `BrushPanel.kt:474-491`, `:85` |
| E6 | List previews are rendered synchronously on the UI thread inside `onBindViewHolder` (a 92-step textured stroke per row on a cache miss). | `BrushPanel.kt:716-742`, `BrushPreviewRenderer.kt:38-41` |
| E7 | Previews are not deterministic. `BrushEngine.rng` is seeded with `System.nanoTime()`, so jittered/scatter brushes render differently every time. That breaks golden tests and the "truthful preview" goal. | `BrushEngine.kt:133` |
| E8 | **Doubled preview: no second draw call exists in the list-row path** (`getPreview → renderStrokePreview`: one `startStroke`, one `stampDot`, then `strokeTo`, then `endStroke`). Remaining candidates, to be settled by reproduction: (a) Brush Studio pad keeps the automatic sample and adds the artist's hand strokes on top (`BrushPreviewView.kt:84-141, 143-209`); (b) the Studio pad paints half dark and half light (`BrushPreviewView.kt:308-318`), so one stroke can look like two differently coloured strokes; (c) a tip/shape texture that itself contains two marks; (d) the set-cover montage, which deliberately stacks up to three brushes (`BrushPreviewRenderer.kt:56-89`); (e) canvas symmetry (see the brush-library review, finding H1). | see cells |
| E9 | Launcher icon `mipmap-xxxhdpi/ic_launcher.png` is an original-looking mark. Only one density is provided, and there is no adaptive/monochrome icon. | `res/mipmap-xxxhdpi/` |

## New blocking dependency — 0A asset-provenance gate

`assets/brushes/` (about 24 MB, 233 textures and 125 thumbnails, plus `brushes_metadata.json` with
217 entries) and the built-in set taxonomy (Sketching, Inking, Drawing, Painting, Artistic,
Calligraphy, Airbrushing, Textures, Abstract, Charcoals, Elements, Spraypaints, Touchups, Retro,
Luminance, Industrial, Organic, Water, Earth) match the default library of a commercial iOS app.
Several brush ids use the prefix `proc_`, and `AI_CONTEXT_NOTE.md` states that textures were
extracted from that app's IPA.

`AGENTS.md` forbids third-party proprietary assets. Every visible 0D change sits on top of these
assets, so 0D cannot be accepted until a provenance inventory exists: each texture is marked
original, licensed (with the licence recorded), or to-be-replaced. That inventory is a separate
Planner task (`PHASE-0A2-asset-provenance.md`) and is not part of this spec.

## Scope

1. **One panel geometry system.** Remove the second `setLayout` in `BrushPanel.onStart` and extend
   `PanelUi.dockSheet` so it returns the final width, gutter and x/y from window bounds (WindowMetrics),
   clamped so the panel is always fully on-screen and leaves at least 40% of the canvas width visible
   on phones.
2. **Touch targets.** Compact category rows and brush rows get at least 48dp effective height
   (visual height may be smaller if `TouchDelegate`/padding supplies the target).
3. **Emoji-free names.** Remove emoji from `BrushSet.name` at the source. The rail regex becomes a
   defensive fallback only, and the cover uses the same cleaned name.
4. **One icon family.** Define a category icon per built-in set, all monochrome 24dp vectors with one
   stroke weight and one corner style. Add an explicit `iconRes` field on `BrushSet` instead of the
   substring mapping.
5. **Dropdown arrows.** Put each arrow next to its label and centre it on the text line at font scales
   0.85, 1.0, 1.3 and 2.0.
6. **Brand mark.** Add an adaptive launcher icon (foreground/background/monochrome), plus an in-app
   logo drawable that keeps the brand gradient in both themes.
7. **Doubled preview.** Fix it at its source once reproduction identifies the surface (E8). Ship the
   deterministic preview seed (E7) regardless.
8. **Preview off the UI thread.** Render row previews on a background dispatcher and show a neutral
   placeholder until the bitmap arrives. Keep the existing view-tag guard so a recycled row never
   receives the bitmap of the brush it previously showed.

## Non-goals

- Rewriting the brush engine, making premium packs, cloud sync, or matching any third-party screen
  pixel-for-pixel.
- A second panel framework, or layouts hard-coded to device names.
- Replacing textures. That belongs to 0A2, although this spec must not add new ones.

## UX behavior

- The panel anchors consistently to its trigger, respects safe areas and system bars, and chooses the
  side that keeps the most usable canvas.
- The compact phone layout shows several categories and brushes without shrinking touch targets, and
  its content scrolls independently.
- The active category and active brush are clear through colour, contrast **and** shape (for example,
  a leading bar), not colour alone.
- Outside tap, Back and explicit close dismiss the panel. Choosing a brush does not.
- Rotation, an RTL/LTR switch, font scale, dark/light theme and app resume keep a valid selection and
  never leave the panel off-screen.

## Technical design

- `PanelUi.dockSheet(dialog, side, preferredWidthDp): PanelPlacement` becomes the single source of
  geometry. `BrushPanel`, `BrushStudioPanel`, `LayersPanel` and `ColorPickerPanel` consume it (only
  the brush panels are required in this phase).
- Preview determinism: `BrushEngine` gets an injectable seed (`BrushEngine(seed: Long?)`). The preview
  renderer passes `brush.id.hashCode().toLong()`, and the canvas keeps its time-based seed.
- Preview jobs run on `lifecycleScope` of the panel with `Dispatchers.Default`, one in flight per
  row, and are cancelled on rebind. The cache key is unchanged.
- Studio pad: the automatic sample is redrawn on every property change, and hand strokes are cleared
  on property change, so the pad never shows two generations at once. The dark/light split background
  stays, but the sample stroke sits entirely on one half, or the split is removed. Product owner
  decides.

## Data migration and rollback

There is no project-format migration. Brush ids and the current user selection are preserved (set
names change, ids don't). Revert as one feature branch.

## Risks and mitigations

- Doubled-preview root cause may be in texture data (E8c). Mitigation: reproduction step 0 below
  before any code change.
- Background rendering adds flicker. Mitigation: a fixed-size placeholder with the same row height,
  and a cross-fade of at most 120ms.
- Phone crowding: validate real available bounds on the smallest reference phone.

## Acceptance criteria

1. The panel is fully on-screen at 360×640, 640×360, 800×1280 and 1280×800 dp in LTR and RTL. Panel,
   sliders, undo/redo and the triggering control never overlap.
2. All rail and brush rows have ≥ 48dp effective touch targets. This is asserted by an
   accessibility-node test.
3. No `BrushSet.name` in `BrushLibrary` matches `[\p{So}\p{Sk}]`. This is asserted by a unit test.
4. Every built-in set has an explicit icon, and all icons are vector drawables from one family.
5. Rendering the same brush preview twice at the same size produces byte-identical bitmaps (unit or
   instrumented test).
6. For the brush that reproduced the doubled preview: the rendered swatch has exactly one connected
   ink component above alpha 16 (instrumented test). The fix is recorded with root cause.
7. Scrolling the full library on the reference phone: no frame over 32ms caused by preview rendering
   (Macrobenchmark or `FrameMetrics`).
8. Selecting a brush keeps the panel open, and all three dismissal methods work.
9. The launcher and in-app logo pass contrast in both themes.

## Automated test matrix

| Test | Type | Covers |
|------|------|--------|
| `BrushSetNamesTest` | unit | AC3, AC4 |
| `PanelPlacementTest` (pure function of window bounds, side and layout direction) | unit | AC1 |
| `BrushPreviewDeterminismTest` | instrumented | AC5 |
| `BrushPreviewSingleStrokeTest` (connected-component count) | instrumented | AC6 |
| `BrushPanelAccessibilityTest` | instrumented | AC2, AC8 |

## Device/manual test matrix

- **Step 0 (before coding):** capture baseline screenshots of the brush panel closed and open, the
  Studio pad, and a set cover, in Arabic and English, dark and light, on phone and tablet. Record
  which surface shows two strokes and for which brush. Attach this to the Implementation evidence.
- After: repeat the same captures. Open, scroll, select and dismiss the panel. Adjust both right-hand
  sliders, rotate, hide/show system bars, switch language/theme, background/resume, and draw slow and
  fast samples with selected brushes.

## Implementation evidence

To be completed by the Executor: branch and exact base/head commits, clean-worktree state, commands,
results, device IDs/OS, screenshots or metrics, failures and approved waivers, deviations, and
remaining risks.

## Review outcome

To be completed independently.

## Product-owner sign-off and rollback

Pending. Open decisions: (1) keep or remove the dark/light split in the Studio pad; (2) approve the
category icon set; (3) approve the 0A2 asset-provenance gate as blocking.
