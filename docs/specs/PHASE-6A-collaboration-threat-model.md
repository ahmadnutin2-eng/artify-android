# Phase 6A — Collaboration threat model and pre-release hardening

## Specification metadata

- Status: Draft. Needs product-owner approval. **Scope part A blocks any public release that ships
  the "Online" entry.**
- Planner owner: Claude (Planner). Source: `INVENTORY-0C` finding P1, expanded after reading the
  relay and the client.
- Executor owner: Local Codex / Antigravity agent (Android), plus whoever deploys the relay
- Reviewer owner: Independent. A security-focused review is required before part B ships.
- Base commit SHA: `5941ab1c48a384ed2d3ff073e538398da56fa98f`
- Dependencies: part A has none. Part B needs Plan 6B/6C (identity), so it is specified here only as
  the target the protocol must meet.
- Affected areas: `collaboration/**`, `ui/gallery/GalleryActivity.kt` (the entry point),
  `ui/canvas/CanvasActivity.kt:319-380` (collaboration wiring), `canvas/CanvasViewModel.kt:790-850`
  (patch export and import), `collaboration-server/**`, `app/build.gradle` (a feature flag)

## Problem and user value

Live collaboration is meant to be the strongest paid feature (Plan 6). Today the relay
**pairs two anonymous strangers** and lets each push pixels into the other's saved artwork. That is
a privacy, safety and store-policy problem, and it has integrity and crash defects that hurt ordinary
users too.

## Current design (as built)

```
Gallery "Online" → CollaborationClient.search(name, w, h)
  → wss://…onrender.com  {type:search, clientId, name, width, height}
relay: FIFO queue → pairs the two longest-waiting clients → {type:matched, sessionId, partner…}
while drawing: {type:stroke …} every ≥32ms (live preview)  +  {type:patch, png(base64)} per stroke
relay forwards {...message, participantId, participantName} to the partner; keeps nothing on disk
receiver: decode PNG → write into a locked layer owned by the partner → markDirty() → saved in project
```

## Threat model

Assets: the user's artwork, the user's device stability, the user's display name, the relay's
availability, and the product's store listing. Actors: an honest partner, a malicious partner, a
network attacker, and a relay-flooding client.

| ID | Sev | Threat or defect (evidence) | Actor |
|----|-----|----------------------------|-------|
| T1 | **S1** | **Matching with anonymous strangers.** There is no invite, account, age gating, report or block. Anyone can push arbitrary images into your canvas (`server.js:47-79`). This conflicts with `AGENTS.md` ("every network event must be authenticated") and with store policies that require report/block for user-generated content. | malicious partner |
| T2 | **S1** | **A partner's pixels are saved into your project.** `configureCollaboration` and `applyRemoteCollaborationPatch` add a partner layer named with the remote display name, and the patch path calls `markDirty()` (`CanvasViewModel.kt:779-786, 817-850`), so a stranger's content, and their chosen display name as the **layer name**, persist in the user's file and gallery thumbnail. | malicious partner |
| T3 | **S2** | **Remote crash through image dimensions.** The receiver calls `BitmapFactory.decodeByteArray` on partner bytes, with no bounds check first (`CanvasActivity.kt:354-357`). A small PNG that declares a huge size makes the device allocate it and then `copy()` it again. That is out-of-memory from one message. The relay's 9 MB base64 cap limits bytes, not decoded pixels. | malicious partner |
| T4 | **S2 (integrity)** | **Joining a session, and every received patch, scrambles your layer order and renames a layer.** `configureCollaboration` tags every local layer with the local id, **renames the first one to the user's display name**, and re-sorts all non-background layers by owner and then by random UUID `id` (`CanvasViewModel.kt:775-790`). `applyRemoteCollaborationPatch` repeats the same sort after every patch (`:848`). The user's carefully ordered layers end up in random order. | honest partner |
| T5 | S2 | **Identity is self-asserted.** `clientId` and `name` come from the client (`server.js:115-123`). A client can claim another's id, and names are unfiltered free text. | malicious client |
| T6 | S2 | **No rate or size limits** beyond `maxPayload` 10 MB. The queue is unbounded, and there is no per-connection message rate, so the free relay is easy to exhaust. | flooding client |
| T7 | S3 | **Pass-through fields.** The relay forwards `...message` verbatim apart from overwriting participant fields, so unknown fields reach the partner. Today's client ignores them, but future clients might not. | malicious partner |
| T8 | S3 | **No ordering, idempotency, resume or late join.** There are no sequence numbers or operation ids, a disconnect ends the session (`CollaborationClient.kt:71-83`), and the relay is in-memory. This conflicts with the Plan 6 exit gate. | network |
| T9 | S3 | **Mirrored and QuickShape strokes aren't shown as live previews** (only the final patch is correct). This is cosmetic, and handled by 2B1/2B2. | honest partner |
| T10 | S4 | `/health` reveals queue and room counts publicly. | anyone |

Already good, and to keep: release builds refuse non-TLS URLs (`CollaborationClient.kt:39`); the
relay persists nothing; there is a heartbeat and dead-socket cleanup; patches are clipped to the
layer bounds on apply (`CanvasViewModel.kt:835-837`).

## Part A — Pre-release hardening (small; ships before any public release)

1. **Feature flag, off by default in release.** Add `artify.collab.enabled` (BuildConfig, default
   `false` for `release`, `true` for `debug`/sidecar). When it is off, the gallery hides the "Online"
   entry entirely. No other behaviour changes.
2. **Decode safely (fixes T3).** First read the bounds with `inJustDecodeBounds`. Reject the patch if
   `width × height` exceeds the partner layer's clipped target rectangle, or 4096×4096, or the PNG
   header's type isn't PNG. Decode with `inPreferredConfig = ARGB_8888` and `inMutable = true`
   directly, so there is no second `copy()`.
3. **Keep the user's layer order and names (fixes T4).** Never re-sort or rename local layers, in
   either `configureCollaboration` or `applyRemoteCollaborationPatch`. Insert the partner layer once,
   directly above the user's active layer (or at the top), and leave the order unchanged on later
   patches. Ownership tagging stays, because it is needed for patch export.
4. **Keep partner content out of the user's file unless they choose (fixes T2).** Partner layers are
   marked `isEphemeralCollaboration = true`. They are excluded from `saveArtwork` and the gallery
   thumbnail, and removed when the session ends. When the session ends, a prompt offers **Keep
   partner's layer** (converting it to a normal layer named "Partner", never the remote name) or
   **Discard**.
5. **Relay limits (fixes T6, T7, T10):**
   - An allow-list of message types, with fields re-built server-side (no `...message` spread).
   - Per-connection token bucket: 40 stroke messages/s, 4 patches/s, 2 MB/s.
   - Waiting-queue cap of 500 (after that, return `busy`), and a 30-minute idle session timeout.
   - `/health` returns only `{ok:true}`.
   - Names are length-limited, and control and bidi-override characters are stripped.
6. **An in-session report/block button** (a stopgap until part B): it ends the session immediately,
   discards the partner's layer, and adds the partner's `clientId` to a local block list, so the relay
   is asked not to re-match that pair (a new `block` message, honoured in `matchAvailable`).

## Part B — Target protocol for paid collaboration (for Plans 6B–6D; recorded here as requirements)

- **Invite-only rooms.** A signed invitation link or code created by an authenticated account, with
  no random matchmaking in the paid product. Room roles are owner, editor and viewer.
- **Authentication.** A short-lived access token (≤ 15 min) is sent on the WebSocket upgrade, never
  in a URL query string. Refresh uses the account session, and tokens are never logged (`AGENTS.md`).
- **Operation envelope:** `{v, roomId, opId (UUIDv7), actor, seq (per actor, monotonic), baseRev,
  type, payload}`. The relay assigns a global `rev`. Clients apply operations in `rev` order and
  deduplicate by `opId`, so retries are idempotent.
- **Snapshots for late join and reconnect:** the room keeps the last snapshot plus the operation log
  since it. A reconnect sends `lastRev`, and the relay replays from there or sends the snapshot.
- **Payload rules:** patches ≤ 1024×1024 pixels and ≤ 1.5 MB each, and stroke previews ≤ 60/s per
  actor, with the server signalling backpressure (`slow_down`).
- **Moderation and policy:** report and block per user, account deletion removes room membership,
  and the retention policy is defined in the Plan 6 data inventory.

## Non-goals

- Building accounts, sync, or the part-B relay in this phase. Part A only makes the current feature
  safe to leave dormant, or to test in closed beta.

## Acceptance criteria (part A)

1. The release build has no visible "Online" entry, and the flag's default is covered by a unit test
   on `BuildConfig`.
2. A crafted PNG declaring 30000×30000 in a 2 KB file is rejected without an allocation above 1 MB
   (instrumented test measuring the Java and native heap delta), and the app keeps running.
3. With 5 local layers in a known order and with known names, starting a session and then receiving
   20 patches leaves those 5 in the same relative order with unchanged names (JVM/Robolectric test
   on `configureCollaboration` + `applyRemoteCollaborationPatch`).
4. After a session, a saved project contains no partner layer unless *Keep* was chosen. The thumbnail
   excludes partner pixels before that choice. No layer is ever named with the remote display name.
5. Relay tests (Node, `node:test`): unknown message types are dropped; forwarded messages contain only
   allow-listed fields; the rate limits trigger at the stated thresholds; the queue caps at 500;
   `/health` returns only `ok`; names with control or bidi characters are cleaned.
6. Report/block ends the session within 1 s, and the same two client ids are not re-matched.

## Automated test matrix

| Test | Level | Covers |
|------|-------|--------|
| `CollaborationFlagTest` | JVM | AC1 |
| `RemotePatchDecodeGuardTest` | instrumented | AC2 |
| `RemotePatchLayerOrderTest` | JVM/Robolectric | AC3 |
| `EphemeralPartnerLayerSaveTest` | instrumented | AC4 |
| `collaboration-server/test/*.test.js` | Node | AC5, AC6 (server side) |

## Rollback

Part A is additive and flag-gated. Rolling back the relay means redeploying the previous image. Old
clients keep working with the hardened relay because the message shapes are unchanged, and extra
fields are simply no longer forwarded.

## Implementation evidence / Review outcome / Sign-off

To be completed per `docs/specs/README.md`. Product-owner decisions: (1) confirm that collaboration
is off in release until part B; (2) whether random matchmaking should exist at all in the final
product, or invite-only is the model; (3) the partner-layer "Keep/Discard" wording.
