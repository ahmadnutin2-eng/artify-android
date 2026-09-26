# Artify design tokens: measured state and proposed single scale (Plan 0 / 3B input)

- Role: Planner (Claude). Measured at base commit `5941ab1c48a384ed2d3ff073e538398da56fa98f`.
- Purpose: the roadmap asks for "a single design token sheet for surfaces, spacing, type, radii,
  icons, elevation and motion". This file records what the code **actually** uses today and proposes
  one scale to converge on. Nothing here changes code. Plan 3B migrates to it.

## 1. What exists today

**Declared tokens.** `res/values/colors.xml` has 35 colours, and `res/values/dimens.xml` has a
4-based spacing scale (`space_xs` 4 → `space_xxl` 32dp), `touch_target_min` 48dp, three icon sizes
(16/20/26dp) and three radii (8/14/20dp). This is a good base.

**What the code actually uses** (scripted count):

| Dimension | Declared | Used in code/drawables | Gap |
|-----------|----------|------------------------|-----|
| Type sizes (sp) | none declared | **20 distinct values**: 4.2, 7, 10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 15.5, 16, 17, 18, 19, 20, 22, 24. The most common are 13 (40), 12 (33), 14 (27) and 15 (22). | No type scale; half-steps everywhere |
| Corner radii (dp) | 8, 14, 20 | **12 distinct values** in drawables: 3, 6, 7, 8, 9, 10, 12, 14, 18, 20, 22, 24 | Radii are chosen per drawable |
| Colours | 35 named | **124 distinct inline `Color.rgb/argb`/hex literals** in Kotlin (153 uses) | Most panels bypass the palette |
| Theme | `Theme.MaterialComponents.DayNight`, `forceDarkAllowed=false`, no `values-night` | The app is dark-only in practice | 0D's "dark/light" acceptance needs a light palette to exist first |
| Naming | `procreate_accent`, `procreate_accent_dark`, `procreate_accent_light` | used by the theme | Brand-neutral names needed (Plan 0A2 hygiene) |

## 2. Proposed canonical scale

### Colour roles (dark theme values are today's; the light theme is new)

| Role token | Dark (today) | Light (proposed) | Replaces |
|------------|--------------|------------------|----------|
| `artify_bg` | `#111113` | `#F6F6F8` | `background_dark` |
| `artify_bg_deep` | `#0B0B0D` | `#ECECF0` | `background_darker` |
| `artify_surface_1…4` | `#1B1B1E` `#242427` `#2D2D31` `#38383D` | `#FFFFFF` `#F2F2F5` `#E8E8EC` `#DDDDE2` | `surface_1…4` |
| `artify_panel_glass` | `#DD101116` | `#E6FFFFFF` | `brush_glass`, `panel_background` |
| `artify_accent` | `#397EF6` | `#2F6FE0` (4.7:1 on white) | `procreate_accent` |
| `artify_accent_strong` | `#245FC4` | `#1E54B5` | `procreate_accent_dark` |
| `artify_accent_soft` | `#33397EF6` | `#262F6FE0` | `accent_soft` |
| `artify_on_surface` | `#FFFFFF` | `#141417` | `text_primary` |
| `artify_on_surface_2` | `#B3FFFFFF` | `#B3141417` | `text_secondary` |
| `artify_on_surface_3` | `#7AFFFFFF` | `#A3141417` | `text_hint`. Today's `#73FFFFFF` on `surface_1` measures **4.48:1**, just under WCAG AA (4.5:1) for small text. `#7AFFFFFF` gives about 4.8:1, and the light value keeps at least 4.7:1 up to `surface_4` |
| `artify_outline` | `#1FFFFFFF` | `#1F141417` | `outline_subtle`, `divider_color`, `hairline_color` |
| `artify_danger` | `#E15554` | `#C8393A` | `danger` |
| `artify_brand_gradient` | the launcher mark's gradient, never tinted | same | new |

Migration rule: add the new names as **aliases** of the old ones first
(`<color name="artify_accent">@color/procreate_accent</color>`), move call sites, then invert the
alias. No colour disappears in one step.

### Type scale (sp): 7 steps

| Token | Size / line height | Use | Absorbs today's |
|-------|--------------------|-----|-----------------|
| `type_caption` | 11 / 14 | facts row, badges | 10.5, 11, 11.5 |
| `type_label` | 12 / 16 | rail labels, chips | 12, 12.5 |
| `type_body_s` | 13 / 18 | dense panel body | 13, 13.5 |
| `type_body` | 15 / 20 | default body, buttons | 14, 14.5, 15, 15.5 |
| `type_title_s` | 17 / 22 | panel titles | 16, 17, 18 |
| `type_title` | 20 / 26 | dialog titles | 19, 20, 22 |
| `type_display` | 24 / 30 | gallery headers | 24 |

The values 4.2 and 7 are canvas-overlay annotation sizes that scale with zoom; they stay computed and
are excluded from the scale. Arabic text uses the same sizes with line height +2sp.

### Radii (dp): 4 steps

`radius_xs` 6 (chips, small tags) · `radius_s` 10 (rows, inputs) · `radius_m` 14 (cards, panels)
· `radius_l` 22 (sheets, large cards). Absorbs today's 3–24 set.

### Spacing, touch and icons

Keep the existing `space_*` scale and `touch_target_min` 48dp. Icons use one family, drawn on a 24dp
grid with a 1.75dp stroke, rendered at `icon_inline` 16, `icon_toolbar` 20 or 24 (proposed: raise
toolbar icons to 24 for legibility at 48dp targets) and `icon_feature` 26.

### Elevation and motion

- Elevation: `0` canvas, `1` floating toolbar, `2` panels, `3` dialogs. Glass panels use blur where
  available (already implemented in `BrushPanel`) and a solid fallback.
- Motion: `motion_fast` 120ms (selection, press), `motion_medium` 200ms (panel open and close),
  `motion_slow` 320ms (sheet expand). Use a standard ease-out for entering and ease-in for exiting.
  Keep the existing `PanelMotion.kt` as the one place durations live.

## 3. Enforcement (Plan 3B)

- Lint baseline: count inline colour literals and `textSize = N f` literals in `ui/`, `brushes/`,
  `layers/` and `color/`. The count may only go down, checked by a JVM test that scans the source the
  same way `BrushAssetReferenceTest` does.
- Screenshot tests (Plan 0B) render every panel in both themes once the light palette lands.
