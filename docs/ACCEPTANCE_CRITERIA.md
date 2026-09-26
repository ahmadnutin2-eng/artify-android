# Artify acceptance criteria

These are release targets, not promises about the current build. Plan 0 must record the exact
reference devices and measured baselines before a phase is accepted.

## Evidence contract

- Every measurement records device/model, OS, build and commit, display refresh rate, run count,
  warm-up, test data, tool/version, capture/crop or mask rules, threshold, and pass/fail oracle.
- **Severity 1:** data loss, security/privacy breach, inaccessible owned artwork, incorrect charge, or
  collaboration corruption. **Severity 2:** crash/ANR or broken primary workflow without a safe
  workaround. **Severity 3:** degraded secondary workflow. **Severity 4:** cosmetic defect.
- A waiver must name its owner, reason, expiry, user impact, and rollback; undocumented exceptions do
  not count as a pass.

## Reliability

- Zero known severity-1 data-loss defects.
- 100/100 automated save/reopen cycles preserve document structure and visible output.
- Forced process death during save leaves either the prior valid revision or the complete new one.
- Autosave begins within 5 seconds of idle and does not block the UI thread for more than one frame.
- Every schema migration has forward-migration and corrupt-input tests.
- Project data, index, metadata, thumbnail, and history commit consistently or recover to the last
  valid revision; no mixed revision is presented as healthy.
- Full disk, revoked permission, interruption, reboot/process death, and corrupt/missing assets have
  deterministic tests and a user-visible recovery path.

## Drawing and performance

- Stroke event processing p95 under 4 ms on the reference flagship device.
- Visible input-to-pixel latency target at or below 35 ms on the reference stylus device, measured
  consistently with the same camera/procedure.
- Sustained drawing and canvas navigation meet the display refresh budget with less than 1% slow
  frames during the defined large-canvas scenario.
- Reference large-canvas scenario: 4096×4096, 20 raster layers, one textured brush, autosave active.
- No preview contains an unintended second stroke; preview and canvas golden renders remain within
  the agreed image-difference threshold.

## UX and accessibility

- Golden screenshots cover 360–480dp landscape height phones and 600dp+ tablets in Arabic and
  English layout directions.
- No panel covers its triggering control, the active slider rail, or required undo/redo controls.
- All actionable controls expose accessible names and effective targets of at least 44×44dp.
- Contrast meets WCAG AA for text and essential control states.
- Brush and layer selections remain visible until changed; selection panels close by outside tap,
  explicit close, or back—not by ordinary selection.

## Export

- PNG/JPEG dimensions and alpha match fixtures exactly.
- Layer-preserving formats retain order, visibility, opacity, names, and supported blend modes.
- Export runs off the drawing thread, reports progress, supports cancellation, and cleans partial
  output after failure.

## Cloud and collaboration

- All production traffic uses TLS and authenticated rooms; secrets never enter source or logs.
- Stroke events are ordered or idempotent and safe to retry.
- Same-region collaboration propagation p95 target below 150 ms on a stable connection.
- Reconnect target below 5 seconds after network return; late join reconstructs the same document.
- Offline conflicts create recoverable revisions and never silently discard either branch.
- Tenant-isolation tests prove one account/room cannot enumerate or retrieve another tenant’s data.
- CI produces dependency/license and software-bill-of-materials reports; critical known
  vulnerabilities block release unless a time-bounded security waiver is approved.

## Subscription

- Purchase, restore, renewal, expiry, grace, refund, reinstall, and offline-cache tests pass.
- Basic local drawing, basic export, and access to existing local artwork remain available without
  an active subscription.
- Account deletion and subscription cancellation are discoverable and tested.

## Launch health

- User-perceived crash-free sessions at least 99.5% during staged beta.
- ANR rate below 0.2% during staged beta.
- No unresolved severity-1 issue and an assigned owner/date for every severity-2 issue.
- Beta evidence includes first-stroke completion, successful save/export, day-7 retention, and
  collaboration completion; launch decisions state sample size and confidence rather than relying
  on anecdotal feedback.
- Phase 8B must freeze the numerator, denominator, cohort, observation window, minimum sample, and
  launch/stop threshold for every funnel and subscription metric before beta results are inspected.
