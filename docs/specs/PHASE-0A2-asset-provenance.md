# Phase 0A2 — Asset provenance and original replacement library

## Specification metadata

- Status: Draft. Needs product-owner approval. **This phase blocks every public or Play release.**
- Planner owner: Claude (Planner)
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent review, plus the product owner for visual and legal decisions
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Implementation head SHA: TBD
- Dependencies: none. Every other visual phase (0D, 2D, 4A–4E) depends on this one.
- Affected areas: `app/src/main/assets/**`, `app/src/main/res/raw/**`, `app/src/main/res/mipmap-*/**`,
  `brushes/BrushLibrary.kt` (asset paths and set taxonomy), `canvas/BrushTextures.kt` (comments
  only), `AI_CONTEXT_NOTE.md`
- Tools delivered with this spec (both read-only toward the app):
  - `tools/asset_inventory.py`: measures every bundled asset and writes
    `docs/provenance/asset-inventory.csv`. With `--check` it is a CI gate.
  - `tools/texture_lab/generate_textures.py`: produces original, seeded, seamless replacement
    shapes and grains. Its review sheet is `docs/provenance/texture-lab-sheet.png`.

## Problem and user value

Artify cannot be sold, or even distributed publicly, while it ships images whose owner is unknown or
is another company. A takedown would remove the app and every paying user's access to it. An
original brush library is also the product's reason to exist (Plan 4).

## Current evidence (measured by `tools/asset_inventory.py` at the base commit)

| Measure | Value |
|---------|-------|
| Bundled asset files | 372 (24.2 MB) |
| Listed in `brushes_metadata.json`, an extraction mapping of `RawName → Shape/Grain/Thumbnail` that no code reads | **356** |
| Referenced by a brush whose id starts with `proc_` | **302** |
| Present in the APK but referenced by no code | 12 textures plus the metadata JSON (about 3.7 MB) |
| Two Arabic shape files are byte-identical (`tx-arabic-diwani-shape.png` = `tx-arabic-ruqah-shape.png`, sha256 `0a4336…7d20`) | unused and unverified origin |
| Embedded authorship metadata | a few thumbnails carry XMP `CreatorTool=Adobe Photoshop CC 2019 (Macintosh)`; there is no creator or rights field anywhere |
| Code comments that name the reference app | `BrushTextures.kt:149` ("authentic … preview thumbnail"), `:251` ("bundled … assets") |
| Agent guidance that encourages more extraction | `AI_CONTEXT_NOTE.md` "Next Steps" 5–6 |

By brush set (parsed from `BrushLibrary.kt`):

| Set | Brushes | `proc_` ids | Uses bundled textures |
|-----|--------:|------------:|:---:|
| Colour Flow, Square Kufic | 8 | 0 | **no, fully procedural (safe)** |
| Artify Originals | 8 | 0 | yes, and **all 12 of its textures are in the extraction metadata** |
| Arabic Calligraphy | 15 | 0 | yes, and **all 11 of its textures are in the extraction metadata** |
| The 19 sets that mirror the reference taxonomy (Sketching … Earth, Featured, Special) | 229 | 197 | yes |

So even the sets branded "original" and "Arabic" depend on the suspect textures. What is original
today: the engine, the 15 procedural `BrushTipType` generators and 8 procedural `GrainType`
generators in `BrushTextures.kt`, the Colour Flow and Square Kufic sets, and the launcher mark.
`res/raw/pencil_paper.mp3` is documented as CC0 ("Pencil #1", Joseph Sardin, bigsoundbank.com) in
`PencilSampleBank.kt:24`; keep that attribution. `assets/copic_358.csv` lists colour values under a
marker brand's product codes. That is factual colour data, but the brand name must not appear in
the UI or the store listing.

Brush preset ids are **not** persisted in projects or preferences (no match in `project/` or for
preference writes), so retiring ids doesn't break saved artwork.

## Scope

1. **Inventory and decisions.** Run `tools/asset_inventory.py`. The product owner fills `decision`
   for every row with one of `original`, `licensed` (with licence and source), `public-domain`,
   `replace`, or `remove-unused`. The CSV is committed. The script preserves decisions on re-run.
2. **CI gate.** `python3 tools/asset_inventory.py --check` runs in the Executor's required gate. It
   fails while any row is undecided or marked `replace`/`remove-unused`. Release builds require it to
   pass.
3. **Replacement library, in three tiers and preferred in this order:**
   - **Tier A, in-engine procedural:** re-point brushes to the existing `BrushTipType`/`GrainType`
     generators wherever the look is achievable. This adds no binary size.
   - **Tier B, Texture Lab originals:** approved outputs of `generate_textures.py` (currently 8
     seamless grains and 10 shapes, including an ahar calligraphy paper and a qalam nib), copied to
     `assets/brushes/artify/` with the generator's `manifest.json` (seed and sha256 per file).
   - **Tier C, commissioned or licensed:** only with a written licence stored under
     `docs/provenance/licences/` and referenced in the CSV.
4. **Re-author, don't rename.** Every brush in a retired set is either rebuilt on tier A/B/C assets
   with new parameters and an original name, or left out of the default library. New ids use the
   `art_` prefix. The default taxonomy is replaced by Artify's own (proposal: *Qalam & Reed*,
   *Ink & Pens*, *Pencils & Dry*, *Paint & Wash*, *Air & Spray*, *Texture & Grain*,
   *Ornament & Stamp*, *Colour Flow*, *Square Kufic*, *My Brushes*). Plan 4A validates it with users.
5. **Hygiene.** Neutralise the reference-app wording in the two `BrushTextures.kt` comments. Add a
   superseding notice to `AI_CONTEXT_NOTE.md` (done in this planning round, and nothing was removed).
   Make sure no agent guidance tells anyone to extract assets.
6. **Removal of retired files** happens only after scope 4 ships and only for rows the product owner
   marked `replace`/`remove-unused`, in a separate commit that lists every path. The Executor never
   bulk-deletes by pattern.

## Non-goals

- Legal advice. This spec produces evidence and a decision record. Whether a specific asset may be
  used is the product owner's decision, with counsel if needed.
- Premium brush packs (Plan 4E), and Brush Studio features.

## UX behavior

- The default library opens on the new Artify taxonomy. The Arabic sets are first in Arabic locale
  and in the first three in English.
- Imported brushes (`BrushsetImporter`) are unaffected. They belong to the user and stay private to
  their device.

## Technical design

- `BrushLibrary` asset paths move to `asset://brushes/artify/{shapes|grains}/…`. The generator's
  `manifest.json` ships alongside, so reviewers can recompute hashes.
- A unit test `BundledAssetProvenanceTest` parses `BrushLibrary` asset paths and asserts that each
  exists and has a decision of `original`, `licensed` or `public-domain` in the committed CSV. Build
  it the same way as the existing `BrushAssetReferenceTest` (`src/test/.../brushes/`), which
  already reads `BrushLibrary.kt` and the assets directory from disk.
- A unit test `NoReferenceAppNamingTest` fails on `proc_` ids, on `Procreate` in user-visible
  strings, and on the retired taxonomy names in `BrushSet` names.
- The grain tile is resampled to `GRAIN_TILE_PX = 256`. Texture Lab grains are exactly periodic at
  512, so the 2:1 downsample stays seamless.

## Data migration and rollback

There is no project migration (brush ids are not persisted). Rollback means reverting the library
commit. Retired files stay in git history, so they are recoverable if a licence is later proven.

## Risks and mitigations

- **Quality drop after replacement.** Mitigations: an A/B review sheet of old versus new strokes per
  brush, signed off by the product owner (Plan 4 rubric), and tier C for any brush that can't reach
  quality otherwise.
- **An already-uploaded Play build (versionCode 14) contains the assets.** Mitigation: the next
  upload must pass the gate. Whether to unpublish the internal track is the product owner's decision.
- **Public repository history keeps the files.** Mitigation: keep the repository private. If it was
  forked while public, record that in the decision log.

## Acceptance criteria

1. `docs/provenance/asset-inventory.csv` is committed with a decision for all rows, and `--check`
   exits 0.
2. No bundled asset referenced by code has a decision other than `original`, `licensed` or
   `public-domain`.
3. No `proc_` ids, no reference-app taxonomy names, and no reference-app name in user-visible strings
   or comments (enforced by tests).
4. Each Texture Lab file in the app matches the sha256 in its manifest, and re-running the generator
   with the recorded seed reproduces it byte-for-byte.
5. Every grain's `seam_ratio` is at most 1.15 (the generator reports it; the current outputs score
   0.87–1.01).
6. The product owner signs the old-versus-new comparison sheet for every re-authored brush.
7. `AI_CONTEXT_NOTE.md` carries the superseding notice, and no document instructs asset extraction.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `asset_inventory.py --check` | CI script | AC1, AC2 |
| `BundledAssetProvenanceTest` | JVM unit | AC2 |
| `NoReferenceAppNamingTest` | JVM unit | AC3 |
| `generate_textures.py` run twice, then diff of sha256 | CI script | AC4, AC5 |

## Device/manual test matrix

- For every re-authored brush: a short stroke, a long fast stroke, pressure ramp, and grain
  alignment when panning. Record before/after screenshots on the phone and the tablet.

## Implementation evidence

To be completed by the Executor.

## Review outcome

To be completed independently.

## Product-owner sign-off and rollback

Pending. Decisions needed: (1) the decision column for all 372 rows; (2) approve the proposed Artify
taxonomy; (3) approve or reject each Texture Lab texture (review sheet:
`docs/provenance/texture-lab-sheet.png`); (4) whether to withdraw the internal Play build.
