# Phase 0D — Brush library and theme bridge

## Specification metadata

- Status: Draft
- Planner owner: Codex Cloud
- Executor owner: Local Codex
- Reviewer owner: Independent Codex review
- Base commit SHA: TBD after repository initialization
- Implementation head SHA: TBD
- Dependencies: repository/clean-clone gate, Plan 0 screenshot protocol, design tokens
- Affected source areas: canvas shell, brush library, dropdowns, icons, theme/logo, brush preview

## Problem and user value

The current brush panel does not yet communicate a coherent professional product: proportions and
tonal hierarchy vary, some icons lack one visual language, selection/dismiss behavior is unstable,
and a brush preview can appear as two strokes. On small landscape phones, panel and slider geometry
also compete with the canvas.

This bridge fixes the visible trust issues already reported by the product owner while preserving an
original Artify identity. It is not a request to copy a competitor’s trade dress.

## Current evidence

- Product-owner reference images and observations in the project conversation.
- Required baselines: phone/tablet screenshots in Arabic and English, dark/light themes, with brush
  panel closed/open, selected row, color panel, and right-hand sliders visible.
- Before implementation, the Planner must measure positions and proportions from Artify screenshots;
  no geometry value may be chosen only by visual intuition.

## Scope

- One responsive floating-panel shell and tokens for surface, scrim, border, elevation, spacing,
  radius, typography, selected state, and animation.
- Brush categories and rows with one legible preview stroke per brush, name, favorite/metadata affordance,
  compact density, scrolling, and stable selection.
- Vector icons from one monochrome family; no emoji, arbitrary glyphs, or generated raster navigation
  icons.
- Dropdown arrow positioned next to its label and optically aligned with the text baseline.
- Theme-safe Artify logo that preserves approved brand colors/contrast rather than becoming white.
- Selection remains active and the panel remains open until outside tap, Back, or explicit close.
- Fix the doubled preview-stroke defect at its data/render source, not by visually hiding one stroke.
- Phone layout preserves the canvas and makes size/opacity controls reachable without clipping.

## Non-goals

- Rewriting the brush-rendering engine, inventing all premium brush packs, cloud sync, or matching any
  third-party screen pixel-for-pixel.
- Introducing a second panel framework or device-name-specific hard-coded layouts.

## UX behavior

- The panel anchors consistently to its trigger, respects safe areas/system bars, and chooses the
  side that preserves the most usable canvas.
- A compact phone presentation shows several categories/brushes without shrinking touch targets
  below 44×44dp; content scrolls independently.
- Active category and active brush are unambiguous through color, contrast, and shape—not color alone.
- Outside tap, Back, and explicit close dismiss the panel. Choosing a brush does not.
- Rotation, RTL/LTR change, font scale, dark/light theme, and app resume preserve a valid selection
  and never strand an off-screen panel.

## Technical design

- Use shared semantic tokens and responsive constraints derived from available window bounds.
- Use one source of truth for brush selection and exactly one preview request per brush row.
- Generate preview output with the same brush configuration consumed by the canvas; cache by brush
  definition/version and invalidate deterministically.
- Use vector assets with theme-aware tint rules and a separately approved multicolor brand mark.
- Add screenshot semantics/test tags without coupling production layout to test dimensions.

## Data migration and rollback

No project-format migration is expected. Preserve brush IDs and current user selection. The change
must be revertible as one feature branch; any new preference needs a safe default for older installs.

## Risks and mitigations

- Subjective imitation: compare against Artify tokens and user tasks, not third-party pixel values.
- Phone crowding: validate real available bounds and scroll behavior on the smallest reference phone.
- Preview jank: benchmark generation/cache misses and keep work off the drawing/UI critical path.
- RTL regressions: include mirrored screenshots and behavior tests from the first implementation.

## Acceptance criteria

- Golden screenshots pass on the named phone/tablet matrix in Arabic/English and dark/light themes.
- Panel, sliders, undo/redo, and triggering control never overlap or clip.
- Every brush row contains exactly one intended preview stroke and matches its canvas brush fixture.
- Selection remains visible and the panel remains open after choosing a brush; all three dismissal
  methods work.
- All navigation/action icons are approved vectors from one family; no emoji or raster AI icons.
- Dropdown arrows sit next to their labels and are optically centered at every supported font scale.
- Brand logo passes contrast checks while preserving approved brand appearance in both themes.
- Reference phone supports 44×44dp effective touch targets and scroll access to the full library.
- No regression in the Plan 0 frame-time and launch baselines beyond the approved tolerance.

## Automated test matrix

- Selection state and dismissal behavior.
- Exactly-one-preview request/render per row and deterministic preview fixture.
- Window-size classes, RTL/LTR, dark/light, font scale, rotation/state restoration.
- Screenshot tests for the agreed states and accessibility-node assertions.

## Device/manual test matrix

- Small landscape phone, flagship phone, 8–11 inch tablet, and stylus device.
- Open/scroll/select/dismiss panel; adjust both right sliders; rotate; hide/show system bars; switch
  language/theme; background/resume; draw slow/fast samples with selected brushes.

## Implementation evidence

To be completed by the Executor using the repository evidence contract.

## Review outcome

To be completed independently with severity-ranked findings and base/head commit range.

## Product-owner sign-off and rollback

Pending measured side-by-side review on the connected phone and tablet.
