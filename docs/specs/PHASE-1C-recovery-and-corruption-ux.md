# Phase 1C — Recovery and corruption UX

## Specification metadata

- Status: Draft. Needs product-owner approval.
- Planner owner: Claude (Planner)
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Dependencies: **1A** (generations, quarantine directory, safe load), **1B** (the save-state
  indicator)
- Affected areas: `ui/gallery/GalleryActivity.kt`, `ui/canvas/CanvasActivity.kt` (load path),
  `project/ProjectDocumentStore.kt` (the read-only recovery API), new `ui/recovery/*`, `res/values*/`

## Problem and user value

When something does go wrong, the artist must see it plainly, keep the best copy, and never be pushed
into overwriting good work. Today a load failure becomes a toast plus a silent flattened fallback
(`CanvasActivity.kt:230-247`), there is no way to delete an artwork at all (`ArtworkRepository.delete`
exists but no UI calls it), and nothing tells the artist which copy they are editing.

## Scope

1. **Health badge in the gallery.** Each card shows nothing when healthy, *Recovered copy* when the
   current generation failed and the previous one was opened, or *Needs attention* when both failed.
   The badge has an icon **and** text, not colour alone.
2. **Recovery sheet.** Opening a *Needs attention* artwork shows a sheet instead of the canvas, with:
   - **Open last good version** (when any generation verifies), which opens it as a normal project.
   - **Open preview as a new copy**, which creates a new project from the flattened preview and
     never touches the damaged one.
   - **Export what can be read**, which saves each readable layer PNG to the user's Pictures folder
     via the share sheet.
   - **Keep for later**, which closes the sheet and changes nothing.
3. **Quarantine.** A generation that fails verification moves to `quarantine/<timestamp>/` inside the
   project (1A layout). It is never auto-deleted. It is included in *Export what can be read*.
4. **Safe deletion (new).** Delete from the gallery moves the project to an app-private
   `trash/` for 30 days, with *Undo* on a snackbar and a *Recently deleted* screen to restore or
   delete permanently. Permanent deletion is explicit and confirmed. Nothing is deleted without the
   user's action.
5. **Honest error copy.** Every failure message states what happened, what is safe, and what to do,
   in both languages. Error text never includes file paths or raw exception messages; those go to
   the diagnostics file (scope 6).
6. **Local diagnostics file.** Append-only `files/diagnostics/storage.log`, capped at 256 KB with
   rotation. It records the reason enum, generation numbers, sizes and exception class, but no
   artwork, user names or paths outside the app sandbox. The user can attach it to a support email
   through *Settings → Help* only when they choose to.

## Non-goals

- Cloud backup and cross-device restore (Plan 6), and version history browsing (Plan 5).

## UX behavior

- Recovery and deletion flows are reachable with TalkBack, the buttons are at least 48dp, and the
  layout works in RTL and LTR.
- The recovery sheet never appears during drawing. It only appears when opening a project.

## Technical design

- `ProjectDocumentStore.inspect(projectKey): ProjectHealth` reports `Healthy`,
  `RecoveredFromPrevious(generation)` or `Unreadable(readableLayers)`. It is read-only and has no side
  effects, so the gallery can call it lazily per visible card on `Dispatchers.IO`, with the result
  cached per `HEAD` hash.
- Trash is a sibling directory of `project_documents/`, plus a Room column `deletedAt` (added in the
  1D v5 migration). Gallery queries filter `deletedAt IS NULL`.

## Data migration and rollback

This depends on the 1D Room v5 migration (`deletedAt`, `modifiedAt`). Rollback leaves the trash
directory in place. Older builds ignore it, and projects in it stay recoverable by reinstalling the
newer build.

## Acceptance criteria

1. Each of the 1A fault fixtures (missing layer, undersized layer, corrupt `HEAD`, corrupt JSON)
   produces the correct badge and sheet options, verified by an instrumented test per fixture.
2. None of the recovery actions writes to the damaged project's directory, confirmed by comparing
   checksums of the directory before and after.
3. Deleting, undoing and reopening restores the artwork byte-identical. After 30 days (simulated
   clock) the item disappears from *Recently deleted* only after an explicit purge or the scheduled
   purge, which the user can see in settings.
4. All new strings exist in `values` and `values-ar`, and no user-visible message contains a file
   path (lint-style unit test).
5. `storage.log` never exceeds 256 KB and never contains the device owner's name or any absolute
   path outside the app's `filesDir` (unit test on the formatter).

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `ProjectHealthInspectTest` | JVM/Robolectric | AC1 |
| `RecoverySheetInstrumentedTest` (one per fixture) | instrumented | AC1, AC2 |
| `TrashLifecycleTest` (virtual clock) | JVM + instrumented | AC3 |
| `UserFacingErrorCopyTest` | JVM | AC4 |
| `DiagnosticsLogFormatterTest` | JVM | AC5 |

## Implementation evidence / Review outcome / Sign-off

To be completed per `docs/specs/README.md`. Product-owner decisions: the 30-day trash window, and the
Arabic and English wording of every recovery message.
