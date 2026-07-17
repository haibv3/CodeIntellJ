---
phase: 5
title: "Execution Approvals, Review, and Diff"
status: completed
priority: P1
dependencies: [3, 4]
---

# Phase 5: Execution Approvals, Review, and Diff

## Overview

Deliver epoch-safe approval outcomes and evidence-labeled review/diff projections over normalized state. Runnable result: prompt → command approval → patch → honest native/unified diff and `/review` completes inside IDEA.

## Context Links

- [Phase 3 state owner](./phase-03-conversation-state-and-native-chat.md); [Phase 4 stamped context](./phase-04-ide-context-and-editor-actions.md); [approval/diff schema](../reports/260716-2240-app-server-capability-research.md); [diff/VFS APIs](../reports/260716-2240-intellij-platform-research.md); [VS-05/06](../reports/260716-2240-parity-test-research.md)
- Gate: Phase 3 normalized patch facts/merge and Phase 4 snapshot/wire tests green.

## Requirements

- Approvals: modern command/file/permission/tool plus legacy decode-only payloads; exact origin/command/cwd/scope/amendments. Key exclusively by `ServerRequestKey(epoch,id)` and payload fingerprint. Unknown/stale/fingerprint-mismatch/disposed requests reject.
- Outcome FSM: `Pending → Chosen → Sending → Sent → Resolved`; disconnect/write uncertainty from `Sending` or `Sent` becomes `OutcomeUnknown`, not “denied” or safe retry. Resolution records app-server outcome. One user choice and one send attempt unless authoritative reconciliation proves unsent.
- Global UX: inactive-panel/child approvals surface without focus theft; banner shows epoch-origin and payload fingerprint prefix; UI never widens sandbox/permission; `serverRequest/resolved` closes the keyed prompt.
- Diff evidence: before implementing native side-by-side attribution, capture/sanitize a real 0.144.5 trace proving ordering and meaning of `item/fileChange/patchUpdated`, `turn/diff/updated`, item completion, and disk/VFS mutation.
- Source precedence: (1) explicit server before+after/patch facts; (2) pre-turn stamped unsaved `Document` baseline; (3) pre-turn stamped disk baseline. If these cannot authoritatively construct both sides, render server unified diff with warning and no “Codex changed from X to Y” attribution.
- Ownership: `ConversationReducer` remains the only server patch fact owner. `PatchProjection` is pure. `DiffBaselineCache` is narrow local pre-turn content/stamp/hash only; it cannot accept server events or become a second patch store.

## Architecture

Phase 2 server request → `ApprovalStateMachine` keyed/fingerprinted → response through gateway current epoch → resolved/unknown result. Normalized `PatchFact`s → pure `PatchProjection`; `DiffBaselineCache` supplies optional pre-turn local evidence. `DiffEvidenceResolver` applies precedence and returns `NativeBeforeAfter` or `WarningUnifiedDiff`. `NativeDiffService` refreshes VFS asynchronously only after baseline/evidence capture.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ApprovalModels.kt` | 170 LOC | keyed/fingerprinted FSM |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ApprovalStateMachine.kt` | 240 LOC | chosen/sending/sent/unknown tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ExecutionActivityProjection.kt` | 150 LOC | pure command/file/tool rows |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/review/PatchProjection.kt` | 120 LOC | pure normalized-state projection |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/review/DiffBaselineCache.kt` | 150 LOC | narrow pre-turn document/disk cache |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/review/DiffEvidenceResolver.kt` | 180 LOC | precedence/warning contract |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/review/ReviewController.kt` | 150 LOC | target/delivery routing |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/NativeDiffService.kt` | 220 LOC | native/unified/VFS tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/FileNavigationService.kt` | 100 LOC | line/path navigation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ApprovalBanner.kt` | 190 LOC | FSM/origin/unknown UI |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ReviewPanel.kt` | 200 LOC | evidence warning/tree UI |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/approval-epochs-outcomes.jsonl` | 140 lines | keyed outcome golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/review-evolving-diff.jsonl` | 110 lines | review golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/diff-source-precedence-trace.jsonl` | captured/sanitized | empirical 0.144.5 gate |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/session/ApprovalStateMachineTest.kt` | 320 LOC | ambiguity/fingerprint matrix |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/review/DiffEvidenceResolverTest.kt` | 280 LOC | trace/precedence/attribution |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/platform/NativeDiffServiceTest.kt` | 240 LOC | VFS/diff edge cases |

## Functions and Interfaces Checklist

- [x] `ApprovalStateMachine.onRequest(ServerRequestKey, fingerprint, payload)`, `choose(key,fingerprint,decision)`, `sending`, `sent`, `resolve`, `disconnect(epoch)` enforce legal monotonic transitions.
- [x] Response call is `gateway.respond(key, expectedFingerprint, body)`; never numeric ID alone. `OutcomeUnknown` disables resend until app-server reconciliation or explicit new request.
- [x] `PatchProjection.from(NormalizedServerState, threadId, turnScope)` is pure and labels server fact provenance/sequence.
- [x] `DiffBaselineCache.captureBeforeTurn(project,thread,turn,path,document?,disk?)`; immutable entry contains source, bytes/hash, document/disk stamp; bounded/cleared by turn/project.
- [x] `DiffEvidenceResolver.resolve(patchProjection, baseline): NativeBeforeAfter | WarningUnifiedDiff`; exact precedence enforced; no synthetic before-content.
- [x] `ReviewController.start(ReviewTarget, ReviewDelivery)` returns review thread; `NativeDiffService.show(result)` captures evidence before async refresh and renders warning when non-authoritative.

## Implementation Steps

1. Write approval transition tests for double click, fingerprint mismatch, reused numeric ID/new epoch, resolved-before-send, disconnect before/during/after write, child/inactive panel, legacy payload, and amendments.
2. Implement global approval UI and keyed state machine; make chosen/sending/sent visible; persist no approval authority; show `OutcomeUnknown` with reconcile guidance, never automatic resend.
3. Capture real 0.144.5 diff trace against controlled saved/unsaved edits. Record timestamps/sequence, request watermark, file hashes/stamps, server notifications, and VFS observations; sanitize content.
4. Implement pre-turn baseline capture and pure patch projection/evidence resolver. Test precedence: server explicit sides > unsaved stamped document > stamped disk > warning unified diff.
5. Implement async refresh/native diff/file navigation/review. Binary/delete/rename/conflict and missing/stale baseline get explicit evidence labels; Git stage/revert remain separate user actions.

## Todo

- [x] Complete epoch/fingerprint approval FSM and unknown-outcome UI.
- [x] Capture/approve 0.144.5 diff timing/source trace.
- [x] Complete pure patch projection and narrow baseline precedence.
- [x] Complete native/warning unified diff, navigation, and review flow.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Numeric ID reused after restart | epoch key/fingerprint prevents stale response |
| Critical | Disconnect in sending/sent | `OutcomeUnknown`; no retry or false denial/resolution |
| Critical | No authoritative before-content | warning unified diff; no false side-by-side attribution |
| High | Unsaved pre-turn baseline + server patch | stamped document wins over disk; evidence label visible |
| High | Rename/delete/binary/conflict | explicit supported/warning state; no fabricated content |
| Medium | Non-Git review/file vanished | typed disabled/error; chat remains usable |

## Success Criteria

- [x] `./gradlew test --tests '*ApprovalStateMachineTest' --tests '*DiffEvidenceResolverTest' --tests '*NativeDiffServiceTest'`
- [x] Reviewed `diff-source-precedence-trace.jsonl` proves the selected 0.144.5 source rules before native attribution is enabled.
- [x] `./gradlew runIde` shows approval FSM/unknown outcome and prompt → patch → evidence-labeled native or warning unified diff plus `/review`.

## Risk Assessment / Security

Delivery ambiguity is not denial: preserve `OutcomeUnknown` and block duplicate authority. Fingerprint normalized payload bytes before display/choice; the same fingerprint must be sent. Misleading diffs can overwrite or expose unrelated unsaved work: cache only bounded pre-turn baselines, never infer authorship from post-hoc disk, and warn when evidence is insufficient. Server patch facts never enter a second mutable store.

## Dependency Map / Next Steps

Requires Phase 3 normalized facts and Phase 4 stamped content. Phase 6 routes `/review`/`/approve`; Phase 7 reuses keyed child approvals; Phase 8 stress-tests epoch disconnect and trace-backed attribution.
