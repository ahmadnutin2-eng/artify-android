# Phase 1B — Autosave and lifecycle

## Specification metadata

- Status: Draft. Needs product-owner approval.
- Planner owner: Claude (Planner)
- Executor owner: Local Codex / Antigravity agent
- Reviewer owner: Independent
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Dependencies: **1A** (the immutable snapshot and generation commit make frequent saves safe and cheap)
- Affected areas: `ui/canvas/CanvasActivity.kt` (save triggers only), `canvas/CanvasViewModel.kt`
  (dirty/revision signals), new `project/AutosaveScheduler.kt`, `ArtifyApplication.kt`

## Problem and user value

The only save trigger today is `CanvasActivity.onPause` (`CanvasActivity.kt:632-635`). A crash, an
ANR, an out-of-memory kill during a long session, or a battery pull loses everything since the artist
last left the screen, which can be hours of work. `ACCEPTANCE_CRITERIA.md` requires autosave within
5 seconds of idle without blocking the UI thread for more than one frame.

## Current evidence

- `hasUnsavedContent` and the monotonic `contentRevision` already exist and are correct
  (`CanvasViewModel.kt:87-140`). 1B only adds *when* to save.
- Saves already run on an application scope with a mutex (`ArtifyApplication.kt:13-16`), so they
  survive activity destruction.
- There is no `onTrimMemory` handling and no save on `ACTION_UP` of long strokes.

## Scope

1. **Idle autosave.** After the last committed edit (`finishStroke`, layer operations, transforms,
   adjustments), wait **3 seconds** of no further edits, then save through 1A. Any new edit restarts
   the timer. The timer never fires while a finger or stylus is down.
2. **Work-based autosave.** Even without idle time, save after 60 seconds of continuous dirty state,
   but only between strokes (never mid-stroke).
3. **Lifecycle boundaries.** Keep `onPause`. Add `onTrimMemory(TRIM_MEMORY_UI_HIDDEN and above)` →
   save if dirty, and `onTrimMemory(RUNNING_CRITICAL)` → save if dirty. Configuration changes don't
   trigger an extra save if the revision is unchanged.
4. **Coalescing.** At most one save runs, and at most one more is queued (latest revision wins).
   Because 1A marks only the exact saved revision clean, an edit made during a save stays dirty and
   gets the next slot.
5. **A visible save state**, localised in both languages: a small unobtrusive indicator in the canvas
   chrome with the states *Saved*, *Saving…* and *Not saved (tap for details)*. It is never a toast
   and never shifts the layout.
6. **Budget guard.** If the 1A snapshot on the UI thread exceeds 8ms p95 for the current document,
   increase the idle delay to 5 seconds and log a trace section, so autosave never degrades drawing
   feel.

## Non-goals

- Recovery UI after a crash (1C), versions or history browsing (Plan 5/6), and cloud sync.

## Technical design

```kotlin
class AutosaveScheduler(
    private val scope: CoroutineScope,           // lifecycleScope of CanvasActivity
    private val clock: () -> Long,               // injectable for tests
    private val isPointerDown: () -> Boolean,
    private val save: suspend (reason: SaveReason) -> Unit,
) {
    fun onEditCommitted(revision: Long)
    fun onPointerUp()
    fun onLifecycleBoundary(reason: SaveReason)    // PAUSE, TRIM_HIDDEN, TRIM_CRITICAL
}
enum class SaveReason { IDLE, WORK_INTERVAL, PAUSE, TRIM_HIDDEN, TRIM_CRITICAL, MANUAL }
```

- The scheduler is pure Kotlin with an injected clock, so every timing rule is testable on the JVM
  with `kotlinx-coroutines-test` virtual time.
- `SaveReason` goes into a trace section, and into the 1C diagnostics file only as an enum. Never log
  artwork or paths with user names.

## Data migration and rollback

None. The feature flag `autosave_enabled` (default on) lets the product owner disable idle autosave
without shipping a new build if a device-specific problem appears. `onPause` saving stays
unconditional.

## Risks and mitigations

- **Battery and flash wear from frequent large saves.** Mitigations: the 3-second idle rule plus
  1A's unchanged-layer reuse (copy or hard link) mean only edited layers are re-encoded. Measure bytes
  written per hour in the 0B stroke scenario.
- **A frame hitch from the UI-thread snapshot.** Mitigated by the 1A budget and scope item 6.

## Acceptance criteria

1. With virtual time: an edit followed by 3s of idle gives exactly one save; edits every 2s for 70s
   give a save at about 60s (work interval); a pointer held down past 3s gives no save until the
   pointer is lifted.
2. At most one queued save, and the latest revision wins (JVM test).
3. On a device, draw, wait 4s, then `adb shell am kill` or force-stop and reopen: the stroke is there
   (10/10 runs).
4. During 60s of continuous drawing on the 0B large document with autosave active, the janky-frame
   percentage does not exceed the 0B baseline by more than 0.5 percentage points.
5. The save indicator shows all three states in Arabic and English and passes TalkBack announcement
   checks (announced on change to *Not saved* only).
6. Bytes written per hour of the scripted drawing scenario are recorded in the evidence.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `AutosaveSchedulerTest` (virtual time) | JVM | AC1, AC2 |
| `AutosaveKillRecoveryTest` (UiAutomator: draw, idle, kill, relaunch) | instrumented | AC3 |
| `StrokeBenchmark` with autosave on and off | macrobenchmark (0B) | AC4, AC6 |
| `SaveIndicatorAccessibilityTest` | instrumented | AC5 |

## Implementation evidence / Review outcome / Sign-off

To be completed per `docs/specs/README.md`. Product-owner decision: the idle delay (3s proposed) and
the placement of the indicator.
