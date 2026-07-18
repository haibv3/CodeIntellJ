---
phase: 2
title: "UI state delivery bridge"
status: completed
priority: P1
dependencies: [1]
---

# Phase 2: UI state delivery bridge

## Context Links

- [Phase 1 baseline](./phase-01-start.md)
- [Brainstorm architecture](../reports/260718-1116-multi-agent-performance-ui-brainstorm.md)
- `ServerStateStore.kt`: authoritative lossless normalized state owner.
- `CodexWorkspacePanel.kt`: current per-snapshot `SwingUtilities.invokeLater` fan-out.
- `CodexChatPanel.kt`: current embedded/standalone subscription split and 40 ms transcript coalescing.

## Overview

Thêm bridge sau `ServerStateStore` để giữ latest snapshot, hợp nhất burst và giới hạn pending EDT work. Workspace refresh theo surface change; embedded và standalone chat dùng cùng delivery semantics. Reducer, event pump và store notification semantics không đổi.

## Requirements

- Tối đa một scheduled/pending delivery cho mỗi bridge.
- Latest snapshot thắng trong replaceable state lane; final delivered `lastArrivalSeq` phải bằng snapshot mới nhất.
- Active transcript cadence target 50 ms; workspace chrome target 100 ms. Giá trị có thể chỉnh sau baseline nhưng result visibility phải dưới 250 ms.
- `tasks`, `agents`, `title`, `busy` và `transcript` chỉ refresh khi projection key liên quan đổi.
- Dispose dừng timer/scheduler, remove listener và cấm callback muộn.
- Server requests/approval vẫn được state event hiện có đánh thức; post-decision banner refresh trực tiếp không bị coalesce.
- Không sửa `ServerStateStore`, `ConversationReducer`, `ConversationController` hoặc `ApprovalStateMachine` nếu RED tests không chứng minh cần.

## Architecture

```text
ServerStateStore listener (BGT)
       |
       v
UiStateBridge latest mailbox
       |  atomic latest state + dirty surfaces + disposed flag
       |  one armed EDT timer/update
       v
UiStateDelivery
       |-- transcript/busy: 50 ms budget
       `-- tasks/agents/title: 100 ms budget
       |
       v
CodexWorkspacePanel / standalone CodexChatPanel
```

`UiStateBridge` là UI-owned `Disposable`, không phải state owner. Change keys được tạo từ immutable maps và selected thread; không đưa Swing component vào bridge. Scheduler/clock seam phải inject được trong test nhưng production default dùng IntelliJ/Swing EDT scheduling.

## Related Code Files

| Action | Absolute path | Purpose |
|---|---|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/UiStateBridge.kt` | Latest-state mailbox, surface keys, cadence và metrics |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexWorkspacePanel.kt` | Replace direct listener/invokeLater fan-out with bridge delivery |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt` | Standalone bridge ownership; embedded remains parent-fed |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/UiStateBridgeTest.kt` | Deterministic scheduler, latest-state, cadence, disposal tests |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiAgentRenderScaleTest.kt` | Replace source-string ownership assertion with behavior-based delivery assertions |

## Tests Before

Write RED tests before `UiStateBridge`:

- `5000 offers keep at most one scheduled EDT delivery`.
- `drain delivers latest arrival sequence and never regresses`.
- `item delta does not invalidate task list projection`.
- `agent delta invalidates agent and transcript surfaces only`.
- `selected thread change forces title agent and transcript refresh`.
- `dispose removes store listener and suppresses late callback`.
- `embedded chat has exactly one state-delivery owner`.
- `standalone chat uses bounded delivery rather than direct invokeLater per event`.

Use fake scheduler/clock; không dùng sleep hoặc absolute wall-clock assertion trong unit test.

## Refactor

- Implement minimal `UiStateBridge` with latest mailbox, surface key comparison, cadence buckets and read-only counters.
- Move workspace `stateListener` responsibilities behind bridge callback.
- Split `refreshTasks`, `refreshAgents`, `syncChatTitle`, `syncBusyFromState` and `chat.renderExternal` by delivered surface set.
- Keep explicit `chat.renderExternal(snapshot)` calls after user actions, thread open/delete and local notices; route them through one bridge offer API where ordering permits.
- Migrate standalone `CodexChatPanel` subscription to an owned bridge; embedded chat stays parent-fed.
- Dispose bridge before child transcript components.

## Tests After

- Multi-agent fixture: final UI state reflects event 5.000.
- Pending-delivery high-water mark equals 1.
- Chrome delivery count remains within deterministic cadence budget.
- Approval request state triggers banner refresh within one transcript delivery interval.
- Decision action still refreshes banner immediately.
- Two panels keep draft/queue isolation and independent selected-thread projection.

## Implementation Steps

1. Add RED bridge unit tests with fake scheduler and Phase 1 workload.
2. Define smallest surface-key set required by existing refresh methods.
3. Implement bridge mailbox, cadence and disposal without touching store semantics.
4. Replace workspace direct listener and validate each refresh method's true dependencies.
5. Replace standalone chat direct subscription; preserve embedded ownership.
6. Remove obsolete per-panel coalescing only if bridge makes it redundant and tests preserve terminal flush; otherwise keep transcript single-flight seam.
7. Capture after-profile using Phase 1 procedure; compare offered, merged và delivered counts.

## Todo

- [x] RED bridge tests committed before production edit.
- [x] One pending delivery invariant GREEN.
- [x] Latest state/terminal ordering GREEN.
- [x] Surface-selective refresh GREEN.
- [x] Embedded and standalone ownership GREEN.
- [x] Disposal/leak behavior GREEN.
- [x] Full unit suite GREEN.
- [x] Runtime report updated with before/after delivery counts.

## Success Criteria

- [x] 5.000 state updates do not create 5.000 EDT runnables.
- [x] Final state and approval/terminal visibility preserved.
- [x] No change to reducer/store public behavior.
- [x] Workspace task/agent/title projections do not rerun for unrelated content deltas.
- [x] Pending UI delivery/workspace never exceeds one in deterministic test.

## Regression Gate

```bash
./gradlew test --tests '*UiStateBridgeTest' --tests '*MultiAgentRenderScaleTest' --tests '*MultiPanelChatTest' --console=plain
./gradlew test --console=plain
```

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Coalescing che transient state | Coalesce immutable view snapshots only; assert latest terminal and approval visibility |
| Timer race sau dispose | Atomic disposed gate, stop timer, remove listener, late-callback test |
| Surface key sai gây stale UI | Characterize dependencies of every refresh method before key design |
| Bridge thành state owner thứ hai | No mutable domain facts; latest snapshot only |
| Cadence làm result chậm | 50 ms target, runtime visibility gate dưới 250 ms |

## Security Considerations

- Metrics chỉ chứa counts/durations; không log prompt, tool output, file content hoặc approval payload.
- Không thay approval fingerprint/epoch semantics.

## Next Steps

Phase 3 chỉ bắt đầu khi bridge invariants, full unit suite và runtime delivery comparison pass.
