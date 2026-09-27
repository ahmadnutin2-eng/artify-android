# Mobile canvas UX audit and improvement plan

## Scope and evidence

This is a read-only product-quality audit of the debug sidecar `artify.com.codexqa`; it does not
approve production changes. The physical reference device was a Samsung SM-S908E in landscape at
3088 x 1440 px, density 3.75 (about 823 x 384 dp), Arabic RTL, dark chrome and a white canvas.

Evidence is stored locally under:
`build/device-evidence/ux-audit-2026-09-27/final/ux-audit/`.

The audit opened the canvas and exercised Brushes, Layers, Actions, Adjustments, Filters, Selection,
Transform, the quick colour fan, the full colour picker and three slider values. The diagnostic
instrumentation passed. Production source was not changed.

## Measured baseline

| Surface | Measured geometry | Assessment |
| --- | --- | --- |
| Canvas viewport | 823 x 384 dp | Valid compact-landscape reference. |
| Each top toolbar | about 185 x 44 dp, 2 dp from top | Efficient and readable. Keep near this density. |
| Slider rail | about 42 x 177 dp | Slim enough, but the two active tracks are only about 65 dp tall each. |
| Undo/redo rail | about 48 x 109 dp | Usable but visually heavier than the slider rail. |
| Brush panel | about 330 x 332 dp; 40% of screen width | Leaves enough canvas, but the header/cover and category rail consume too much of the panel. It also intersects the toolbar's vertical band. |
| Layers panel | about 344 x 384 dp; 42% of width | Too wide for two layers, full-height, and row targets are near 43 dp instead of 48 dp. |
| Actions/Adjustments | about 390 x 384 dp; 47% of width | Oversized and visually inconsistent with Brush, Layers and Filters. |
| Selection popup | about 130 x 159 dp | Too narrow for a premium Arabic menu; rows are near 40 dp and lack icons/state. |
| Quick colour fan | diameter exceeds available height | Heavily clipped off-screen and overlaps the lock area; labels become too small. |
| Full colour picker | roughly 390 dp wide | Consumes nearly half the canvas; content scale is unbalanced and `255Alpha` is not localized or ordered correctly. |

## Findings

### P0 — Broken coherence and spatial hierarchy

1. Panels do not share one geometry system. Brush, Layers, Actions, Adjustments, Filters and the
   colour picker look like separate products.
2. Several panels begin inside the top-toolbar band. The trigger remains visible above the panel,
   but the overlap looks accidental rather than anchored.
3. Actions and Adjustments take almost half the phone canvas even when their content is sparse.
4. Filters uses a substantially narrower shell than Adjustments despite belonging to the same tool
   family.
5. Empty states occupy a full-height black slab without a useful next action.

### P0 — Colour interaction

1. The quick fan is larger than the short landscape viewport and intentionally places most of the
   wheel outside the screen. On this phone the result is not a deliberate peek; it is a severely
   cropped control.
2. The quick fan competes with the bottom-left canvas-lock control.
3. The full picker is too wide and its internal hierarchy is unbalanced: an oversized current-colour
   swatch, a very large square, a thin hue strip and clipped lower controls.
4. `255Alpha` mixes direction, language and value semantics. Opacity should be presented as an RTL
   label plus a stable numeric value, preferably percent for artists.

### P0 — Brush-library quality

1. The library now has real deterministic previews, but the panel still reads as a collection of
   large cards rather than a dense professional brush browser.
2. The bright green set cover dominates the panel and competes with the blue selection colour.
3. The category column is too wide; long names wrap while the actual brush list shows only a few
   rows.
4. Category icons vary in meaning and visual weight. Some look generic or unrelated to the set.
5. Preview strokes are useful, but the surrounding row has too much vertical padding and metadata
   noise. The preview should be the first thing the eye reads.
6. The selected set uses a very large blue pill. A quieter surface tint plus a strong leading marker
   would preserve hierarchy without turning navigation into the loudest element.

### P1 — Other panels and menus

1. Layers has a good dark foundation, but it wastes width and empty vertical space. English layer
   names and Arabic metadata create directional inconsistency.
2. Actions uses a different grey surface and larger spacing than every other panel.
3. Adjustments and Filters show similar empty content in different shells.
4. Selection is an opaque rectangular popup with no icons, active checkmark or explanation. It is
   functional but visually generic.
5. Transform failure appears as a large centre toast. It explains the problem but does not guide the
   user to the Layers control or provide an action.

### P1 — Sliders and canvas chrome

1. The top toolbars are now compact enough; shrinking them further would start harming touch
   accuracy. Their current 44 dp visual height should sit inside at least a 48 dp hit region.
2. The slider rail is acceptably narrow, but each track is short for fine brush-size control.
3. Low, middle and high fills render correctly and no white line is drawn over the blue fill. The
   remaining issue is precision and available travel, not the fill style.
4. The slider rail and undo/redo rail use different visual mass. They should share one width, corner
   radius, edge inset and surface opacity.

### P1 — Motion

1. Side panels currently travel a full panel width in 250–260 ms and exit in 200 ms. The duration is
   reasonable, but a full-width translation makes the large panels feel heavier than necessary.
2. Motion has no shared contract for anchored popups, side inspectors, colour fan and press feedback.
3. Screenshot capture synchronizes after Android's window animation, so smoothness is not proven by
   still images. Frame timing remains a mandatory device gate.

## Target system

### 1. Shared mobile panel shell

- Create one `PanelSpec`/placement system with three variants:
  - `Popup`: 180–224 dp wide, anchored below its trigger.
  - `Palette`: 300–336 dp wide on this 823 dp viewport.
  - `Inspector`: 320–352 dp only when dense controls require it.
- Preserve at least 58% of the canvas width whenever a single panel is open.
- Start side panels below the toolbar band: target top inset 52–56 dp, bottom inset 8–12 dp.
- Use one surface language: near-black translucent surface (approximately 92–96% opacity), 16–18 dp
  corner radius, subtle 1 dp border, restrained shadow and an opaque fallback where blur is not safe.
- Outside tap and Back close every transient panel. Choosing a brush/layer keeps its browser open.
- No panel may be draggable into dismissal; scrolling must remain internal.

### 2. Canvas chrome

- Keep toolbar visual height at 44 dp with a 48 dp minimum hit target and 8 dp edge inset.
- Standardize both vertical rails at 44 dp visual width and 48 dp hit width.
- Extend each brush slider's active travel to 76–92 dp on a 384 dp-high viewport by reducing excess
  internal gaps and slightly compacting undo/redo.
- Keep the visible slider track near 10–12 dp wide; do not add a thumb or a white overlay line.
- Show the live value beside the finger only while dragging; do not permanently occupy canvas space.

### 3. Premium brush library

- Use a 30/70 or 32/68 category/content split; cap the category rail near 104 dp.
- Category rows: 48 dp hit target, 20 dp monochrome icon, single-line label where possible, subtle
  selected tint plus a 3 dp accent marker.
- Replace the large promotional cover with a compact 72–88 dp set header containing set name,
  one truthful hero stroke and optional count/favourite status.
- Brush rows: 64–72 dp; preview area 44–52 dp high; one deterministic stroke on a quiet neutral
  background. Name and one short property line only.
- Use one original Artify icon family. No emoji, generated-looking symbols or unrelated pictograms.
- Add search, Recent and Favourites only after the geometry and preview hierarchy pass device review.

### 4. Layers, Actions, Adjustments and Filters

- Layers: 304–328 dp wide, 56–64 dp rows, 44 dp thumbnail, consistent RTL text and explicit active
  layer shape. Keep add/close/more controls in a compact 48 dp header.
- Actions: use the same palette shell, group commands into compact sections and remove the broad grey
  slab. Destructive actions must be visually separated.
- Merge the visual language of Adjustments and Filters. Both use the same header, width, internal
  padding, preview state and empty-state component.
- Empty states must include a concise cause and one useful action, such as “Draw on this layer” or
  “Select a visible layer”, without occupying a full-height empty panel.
- Selection popup: 192–224 dp wide, 48 dp rows, coherent icons, active checkmark and anchor alignment
  directly below the selection trigger.
- Transform failure: use a compact anchored message near Layers/Transform with an action to open
  Layers, not a large centre-screen toast.

### 5. Colour system

- Quick fan diameter: at most 70–74% of available viewport height (about 268–284 dp here).
- Keep at least 85–90% of the wheel visible and offset it inward from the trigger; never overlap the
  canvas-lock button or a vertical rail.
- Labels must remain legible at 12 sp minimum and should appear only for the active segment if the
  full ring cannot display them cleanly.
- Full picker: 304–336 dp palette shell; compact current-colour strip; balanced SV field and hue/
  opacity controls; safe bottom padding.
- Localize and reorder values: `الشفافية 100٪`, numeric fields forced LTR. Add Recent/Palette tabs
  after the core picker geometry is correct.

### 6. Motion contract

- Side palette: 180–220 ms, 16–24 dp edge translation plus opacity 0→1; avoid moving a full 330 dp.
- Anchored popup: 120–160 ms fade + 0.96→1 scale from the trigger edge.
- Dismiss: 140–180 ms and slightly faster than entry.
- Press scale: 0.94–0.97 for 70–90 ms; avoid an exaggerated 0.88 scale on small toolbar controls.
- Respect Android “remove animations”. No required information may exist only during motion.
- Performance gate: no panel-opening or brush-scroll frame over 32 ms; target 90% of frames below
  16.7 ms on the reference phone.

## Execution order

### Milestone A — Geometry foundation (highest value)

1. Build the shared panel shell and placement rules.
2. Migrate Actions, Adjustments, Filters, Layers and the full colour picker.
3. Fix toolbar-band overlap, edge/bottom insets and unified dismissal.
4. Add geometry tests for 360 x 640, 640 x 360, 823 x 384 and tablet sizes in RTL/LTR.

Exit gate: every panel is on-screen, visually related and leaves the required canvas area.

### Milestone B — Colour and slider interaction

1. Resize/reposition the quick fan and resolve lock/rail collisions.
2. Rebalance the full picker and localize opacity/value labels.
3. Extend slider travel while retaining the slim track and 48 dp hit target.

Exit gate: colour and slider controls can be selected accurately with a finger and stylus on the
reference phone without clipping.

### Milestone C — Brush-library premium pass

1. Compact the set header and category rail.
2. Redesign brush rows around the truthful preview stroke.
3. Finish the original unified category-icon family.
4. Verify selection persistence, scroll performance and one-stroke preview output.

Exit gate: at least five useful brush rows are visible at once on the 384 dp-high reference phone,
with no duplicate-looking stroke and no UI-thread preview stall.

### Milestone D — Secondary tools and motion

1. Refine Layers, Selection and Transform guidance.
2. Apply the shared motion contract and reduced-motion behavior.
3. Run frame metrics, accessibility-node target checks and screenshot comparisons.

Exit gate: all required interactions meet the frame, accessibility and dismissal rules.

## Verification matrix

- Arabic RTL and English LTR.
- White and dark artwork backgrounds; app dark/light themes where supported.
- 360 x 640 and 640 x 360 dp split-screen, reference phone 823 x 384 dp, and tablet landscape.
- Font scales 0.85, 1.0, 1.3 and 2.0.
- Finger and stylus: open, scroll, choose, drag slider, outside-dismiss and Back.
- Screenshots: canvas baseline plus every panel, quick fan and full picker.
- Automated: geometry, 48 dp accessibility targets, deterministic brush previews and selection
  persistence.
- Device performance: panel entry/exit and a full brush-list fling with frame timing.

## Product decision

Do not swap the two top toolbar groups. The tested layout follows the established professional
pattern: project/selection tools on one side and paint/layers/colour tools on the other. The present
problem is panel anchoring and proportion, not toolbar ownership. Revisit the side only if a
left-handed mode is added as an explicit user preference.
