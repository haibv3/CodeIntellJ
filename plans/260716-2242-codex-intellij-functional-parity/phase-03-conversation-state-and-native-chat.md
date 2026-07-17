---
phase: 3
title: "Conversation State and Native Chat"
status: completed
priority: P1
dependencies: [2]
---

# Phase 3: Conversation State and Native Chat

## Overview

Create the single normalized server-state owner and native multi-panel chat: thread lifecycle, stream, queue/steer, interrupt, history, patches, and agent facts. Runnable result: two panels conduct/resume independent tasks with deterministic event/snapshot reconciliation.

## Context Links

- [Phase 2 sequencer/epochs](./phase-02-app-server-lifecycle-and-capabilities.md); [state model](../reports/260716-2240-app-server-capability-research.md); [VS-01/02/04](../reports/260716-2240-parity-test-research.md)
- Gate: Phase 2 lifecycle, sequencing, framing, schema, and redaction tests green.

## Requirements

- Ownership: `ConversationReducer` is the only writer of normalized app-server facts keyed by thread/turn/item; this includes patch/diff facts, parent/child agent facts, approvals references, status, token usage, and unknowns. Review/agent UI later uses pure projections.
- Sequenced merge: consume only `SequencedEvent(epoch, arrivalSeq, requestWatermark, payload)`. A snapshot response may update state as of its request watermark; live events after that watermark win. Stale/old snapshots only fill missing historical entities. Terminal states merge monotonically and never regress to active.
- Lifecycle: start/list/read/resume/archive/fork; send/steer/interrupt; item started/delta/patch/completed and thread/turn/status/token events; completed item payload is authoritative unless a later same/new-epoch authoritative terminal snapshot advances it.
- Follow-up queue: one panel-owned serialized actor; entries transition `Draft → Dispatching → Acknowledged → Consumed`, or `Dispatching/Acknowledged → OutcomeUnknown` on ambiguous disconnect. Never auto-replay `OutcomeUnknown`; user must reconcile then explicitly retry/copy.
- UI: panel owns draft, queue actor, follow-up mode, and thread selection; normalized server state is shared by thread ID. Native transcript/status only; Swing on EDT, reduction/merge off EDT.

## Architecture

Phase 2 sequencer → `ConversationController` (RPC issuance and snapshot merge ownership) → pure `ConversationReducer` → `ServerStateStore<StateFlow<NormalizedServerState>>`. `ConversationMergePolicy` compares epoch/watermark/arrival and terminal rank. `PatchProjection` and `AgentTreeProjection` are pure views of normalized facts; introduced in later phases. Each `ChatPanelModel` owns a `FollowUpQueueActor` and draft only.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ConversationModels.kt` | 320 LOC | normalized patch/agent/thread facts |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ConversationReducer.kt` | 320 LOC | sole fact mutation/terminal merge |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ConversationMergePolicy.kt` | 150 LOC | epoch/watermark snapshot rules |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ServerStateStore.kt` | 100 LOC | serialized state flow |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ConversationController.kt` | 270 LOC | RPC/snapshot/recovery merge owner |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/FollowUpQueueActor.kt` | 190 LOC | queue FSM/ambiguous outcome |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ChatPanelModel.kt` | 140 LOC | draft/queue/panel isolation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt` | 270 LOC | platform/smoke behavior |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRenderer.kt` | 220 LOC | pure state rendering |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexToolWindowFactory.kt` | +80 LOC | content tabs/history |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/thread-turn-stream.jsonl` | 100 lines | lifecycle golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/snapshot-watermark-permutations.jsonl` | generated | stale/live merge golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/resume-interrupt-steer.jsonl` | 80 lines | edge golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/session/ConversationReducerTest.kt` | 340 LOC | all normalized fact permutations |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/session/ConversationMergePolicyTest.kt` | 260 LOC | epoch/watermark/terminal tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/FollowUpQueueActorTest.kt` | 220 LOC | FSM/no-replay tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiPanelChatTest.kt` | 180 LOC | platform isolation |

## Functions and Interfaces Checklist

- [x] `ConversationReducer.reduce(state, SequencedEvent): NormalizedServerState`; pure; only location that changes server facts, including `PatchFact` and `AgentFact`.
- [x] `ConversationMergePolicy.mergeSnapshot(state, SnapshotEnvelope(epoch, requestWatermark, value))`: post-watermark live facts win; stale snapshot fills missing history only; terminal rank cannot regress.
- [x] `ConversationController.startThread/resume/fork/archive/startTurn/steer/interrupt`; owns request watermark correlation and reconnect `list/read/resume` merge, but not panel drafts or gateway epochs.
- [x] `ServerStateStore.dispatch` serializes reducer calls; exposes immutable snapshots/projection inputs.
- [x] `FollowUpQueueActor` accepts enqueue/edit/move/delete/dispatch/ack/consume/reconcile/retry commands; only `Draft` is editable; `OutcomeUnknown` is never auto-dispatched.
- [x] Domain union covers user/agent/reasoning/plan, command/file/patch/MCP/tool/collaboration/subagent/web/image/sleep/review/compaction/approval-reference/unknown.

## Implementation Steps

1. Write reducer tests for item lifecycle, patch/agent facts, duplicates, interleaved threads, terminal ranks, unknowns, and completed payload authority.
2. Write request-watermark permutations: snapshot requested before live events but returned after; old/new epochs; partial history; terminal snapshot/event conflicts. Prove stale snapshots fill missing history only.
3. Implement controller/store/reducer on one serialized state lane; gateway remains epoch owner and controller remains snapshot/recovery merge owner.
4. Implement panel-local queue actor and transition tests, including disconnect before write, after write/before response, after ack, steer mismatch, compact/review non-steerable. Mark ambiguous cases `OutcomeUnknown`; require explicit reconciliation/action.
5. Build native transcript/composer/history/tabs/queue status and sandbox start, stream, queue/steer, interrupt, fork, archive/resume in two panels.

## Todo

- [x] Complete sole normalized reducer/store and snapshot merge policy.
- [x] Complete lifecycle/turn/reconnect controller.
- [x] Complete panel-owned queue FSM and native multi-panel chat.
- [x] Pass permutation, ambiguity, platform, and sandbox gates.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Snapshot response arrives after newer live terminal | live/terminal state preserved; snapshot fills only missing history |
| Critical | Old epoch snapshot/event after restart | cannot regress current state or mutate new pending work |
| Critical | Queue write outcome ambiguous | `OutcomeUnknown`; no automatic replay; explicit reconcile/retry |
| High | Patch/agent events interleave with chat | one reducer yields coherent facts; projections need no stores |
| High | Two panels drafts/queues/threads | panel state isolated; shared thread facts consistent |
| Medium | Archive/fork/history error | typed error; existing state remains monotonic/useful |

## Success Criteria

- [x] `./gradlew test --tests '*ConversationReducerTest' --tests '*ConversationMergePolicyTest' --tests '*FollowUpQueueActorTest' --tests '*MultiPanelChatTest'`
- [x] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure`
- [x] `./gradlew runIde`: two panels stream independently; stale snapshots/restart cannot regress state; ambiguous queue item is never replayed.

## Risk Assessment / Security

Two normalized owners would silently diverge, so patch/agent/review facts live only in `ConversationReducer`. Watermark rules prevent delayed reads from erasing live events. Terminal monotonicity must be schema-fixture tested, not inferred. Queue ambiguity can duplicate costly/destructive turns; default to `OutcomeUnknown` and require user decision. Panel drafts remain local and are never merged from server snapshots.

## Dependency Map / Next Steps

Requires Phase 2 epoch/sequencer/gateway. Phase 4 maps typed inputs; Phase 5 and Phase 7 add pure patch/agent projections over this state; Phase 8 tests controller-owned recovery without another state owner.
