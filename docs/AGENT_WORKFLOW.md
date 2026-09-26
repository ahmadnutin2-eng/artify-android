# Codex Cloud ↔ local Codex workflow

## Recommended responsibility split

**Codex Cloud — Planner and independent reviewer**

- Reads the shared GitHub repository and roadmap.
- Produces one bounded phase specification at a time.
- Maps dependencies, risks, file areas, tests, and measurable acceptance criteria.
- Reviews the executor’s branch/PR against the approved specification.
- Does not claim physical stylus, phone, or tablet verification.

**Local Codex — Executor and device verifier**

- Implements the approved specification in the Windows/Android workspace.
- Runs Gradle tests, builds the sidecar APK, installs through ADB, and captures real-device evidence.
- Records test commands, screenshots, metrics, and remaining risks.

**Product owner — Decision authority**

- Approves product identity, visual direction, pricing, privacy, and release gates.
- Tests subjective drawing feel; agents can measure it but cannot replace the artist’s judgment.

## One task lifecycle

1. Cloud Planner creates `docs/specs/PHASE-NAME.md` on a planning branch and records the exact base
   commit SHA.
2. The specification must contain: problem, users, current evidence, scope, non-goals, dependencies,
   UX behavior, technical design, migration/rollback, acceptance criteria, and test matrix.
3. Product owner approves the specification; merge the planning-only change.
4. Local Executor starts a fresh implementation branch or worktree from that commit.
5. Executor implements only that specification, updates its evidence section, and pushes the branch
   with an exact head SHA and clean-worktree status.
6. Cloud Reviewer performs a separate review. High-risk findings return to the same implementation
   branch; it must not silently broaden scope.
7. Local Executor installs the reviewed build and completes device checks.
8. Reviewer compares the declared base/head range; merge only when automated, visual/device, and
   product-owner gates are satisfied.

## Branch naming

- Planning: `plan/NN-short-name`
- Implementation: `feature/NN-short-name`
- Fix: `fix/NN-short-name`
- Release preparation: `release/x.y.z`

Only one implementation branch may own a source area at a time. Parallel Cloud work is reserved for
independent areas such as documentation, backend protocol tests, export fixtures, or code review.

## Cloud connection prerequisites

The Android project is not currently a Git repository. Before Codex Cloud can share its state:

1. Create a new **private GitHub repository for the complete Artify application**. Do not reuse the
   server-only repository as if it contained the Android client.
2. Initialize Git in this project root and commit the sanitized source. Review `.gitignore` first;
   never add signing keys, `local.properties`, extracted reference applications, APKs, or the local
   JDK.
3. Prove the sanitized project builds/tests from a clean clone, then push the default branch.
4. In Codex/ChatGPT, connect GitHub and authorize only the Artify application repository.
5. Create a Codex Cloud environment for that repository. Use Linux-compatible setup commands such
   as `./gradlew :app:testDebugUnitTest`; cloud planning does not require an Android emulator.
6. Put durable repository rules in `AGENTS.md` (already prepared) and start Cloud tasks from a fresh
   branch/worktree.
7. Keep secrets in the environment’s secret store and grant network access only when a task needs
   it. Never copy API keys into prompts or committed files.

## First three Cloud tasks

1. **Planner:** refine `PHASE-0D-brush-theme-bridge.md` from measured screenshots without modifying
   production code.
2. **Planner:** create the Plan 1 atomic-save/recovery specification after inspecting the current
   persistence implementation and tests.
3. **Reviewer:** audit the current brush-library implementation against Plan 2/3 criteria and report
   concrete file/behavior gaps; do not redesign by opinion alone.

## Prompt templates

Planner:

```text
Role: Planner. Read AGENTS.md, docs/PRODUCT_ROADMAP.md, and docs/ACCEPTANCE_CRITERIA.md.
Create docs/specs/PHASE-XX-name.md for Plan X. Inspect the current implementation. Do not edit
production source. Define measurable acceptance criteria, dependencies, rollback, and a test matrix.
```

Executor:

```text
Role: Executor. Implement only docs/specs/PHASE-XX-name.md. Preserve unrelated changes. Run the
required tests, install the sidecar APK on the connected device when applicable, and update the
specification evidence section with exact base/head commits, commands, results, device/build IDs,
screenshots or metrics, failures/waivers, and remaining risks.
```

Reviewer:

```text
Role: Reviewer. Review the implementation branch against docs/specs/PHASE-XX-name.md and AGENTS.md.
Prioritize data loss, correctness, performance, security, lifecycle, accessibility, and missing
tests. Report findings by severity with file and line evidence. Do not implement fixes.
```

## Claude ↔ Codex collaboration (added 2026-09-26)

Claude (Anthropic) joins as a second independent agent, so no model reviews its own work. The
handoff medium is this GitHub repository; neither agent needs a direct connection to the other.

| Step | Default owner | Alternate |
|------|---------------|-----------|
| Plan / spec (`docs/specs/`) | Claude or Codex Cloud | the other |
| Implement + device evidence | Local Codex / Antigravity agent on Windows | — |
| Independent review (`docs/reviews/`) | whichever agent did **not** write the spec | — |
| Disagreement | Both positions recorded in the spec's "Review outcome"; product owner decides | — |

Rules:

- Each agent states its role and the exact base commit at the top of every document it writes.
- A reviewer answers a spec or diff in `docs/reviews/REVIEW-<date>-<topic>.md`. The author replies
  inline under each finding (`Response:`) rather than editing the finding away.
- Claude cannot build or install the APK from its cloud session (no Android SDK or device). Every
  runtime claim from Claude is marked "static reading" and must be confirmed by the Executor.
- When one agent's quota is exhausted, the other may take the Planner or Reviewer role, but never
  both roles on the same change.
