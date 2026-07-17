---
phase: 7
title: "Agents and Advanced Execution Targets"
status: completed
priority: P1
dependencies: [5, 6]
---

# Phase 7: Agents and Advanced Execution Targets

## Overview

Add advisory custom-agent discovery, pure parent/child activity projection, schema-permitted controls, hardened project targets, trace-gated side tasks, and X-gated plan/memory services. Runnable result: delegated work is visible/openable/controllable and every advanced target is proven or visible-disabled.

## Context Links

- [Phase 5 keyed approvals](./phase-05-execution-approvals-review-and-diff.md); [Phase 6 route contracts](./phase-06-settings-mcp-and-slash-commands.md); [agent/X evidence](../reports/260716-2240-app-server-capability-research.md); [VS-08/10/12](../reports/260716-2240-parity-test-research.md)
- Gate: Phase 6 command/account/config/MCP/feedback contract tests green.

## Requirements

- Discovery: scan `~/.codex/agents/*.toml` and project `.codex/agents/*.toml` read-only with precedence/invalid-file diagnostics. Descriptors are advisory; never create live nodes or authority.
- Single state owner: `ConversationReducer` already owns parent/role/nickname, collaboration call, subagent activity, status, and child approval facts. `AgentTreeProjection` is a pure function; no agent event reducer/store.
- UI/control: show parent, role/nickname, Active/Waiting/Done/Error, last activity, approval origin; open child; individual read/steer/interrupt and stop-all only when current normalized thread/turn plus method map permits. Re-check at click and return per-child outcomes.
- Targets: `/local` uses the current canonical IntelliJ content root. `/project` lists canonical content roots only and revalidates project/root identity immediately before `thread/start`/`turn/start.cwd`. Arbitrary external directories are unavailable in this scope.
- `/worktree`, `/cloud`, `/cloud-environment`: visible-disabled; no Git worktree creation/selection and no local emulation. Public contract requires a future plan.
- Advanced: `/side` stays disabled until a sanitized 0.144.5 trace proves ephemeral fork isolation/lifecycle. `/plan` and `/memories` require X opt-in, warning, exact method probe, fixtures, and Phase 6 route semantics; no prompt emulation or handler replacement.

## Architecture

`AgentConfigDiscovery` emits advisory catalog. `AgentTreeProjection.from(NormalizedServerState)` returns immutable view nodes; `AgentController` sends permitted normal thread/turn operations only. `ExecutionTargetService` returns `LocalContentRoot`, `ProjectContentRoot`, or `Unavailable`; it revalidates root ownership at dispatch. `ExperimentalFeatureService` implements Phase 6 deferred specs through injected service capabilities.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/agents/AgentConfigDiscovery.kt` | 180 LOC | precedence/parser tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/agents/AgentModels.kt` | 120 LOC | advisory/view separation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/agents/AgentTreeProjection.kt` | 150 LOC | pure normalized-state view |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/agents/AgentController.kt` | 190 LOC | control revalidation/outcomes |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/AgentTreePanel.kt` | 230 LOC | open-child/control UI |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/targets/ExecutionTargetService.kt` | 170 LOC | content-root/gated targets |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/experimental/ExperimentalFeatureService.kt` | 230 LOC | side/X service gates |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/agents-interleaved.jsonl` | 140 lines | parent/child normalized facts |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/experimental-plan-memory.jsonl` | 90 lines | X request/state golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/side-ephemeral-trace.jsonl` | captured/sanitized | semantic acceptance gate |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/agents/AgentTreeProjectionTest.kt` | 260 LOC | pure tree/activity projection |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/agents/AgentControllerTest.kt` | 220 LOC | stale control/stop-all outcomes |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/targets/ExecutionTargetServiceTest.kt` | 260 LOC | root/override/unavailable matrix |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/experimental/ExperimentalFeatureServiceTest.kt` | 240 LOC | opt-in/probe/trace gates |

## Functions and Interfaces Checklist

- [x] `AgentConfigDiscovery.scan(globalDir,projectDir): AgentCatalog`; read-only, canonical/symlink safe, errors sanitized; catalog never changes normalized server state.
- [x] `AgentTreeProjection.from(state, rootThreadId): AgentTreeView`; pure/deterministic; duplicate/late event handling remains Phase 3 reducer responsibility.
- [x] `AgentController.openChild/interruptChild/steerChild/stopAll`; each checks current epoch/thread/turn/method availability; stop-all returns independent keyed outcomes and never claims atomicity.
- [x] `ExecutionTargetService.contentRoots(project)` returns canonical owned roots; `revalidateAtDispatch(target)` rejects missing, changed, cross-project, or external targets; worktree/cloud kinds always `Unavailable(reason)`.
- [x] `ExperimentalFeatureService.startSide` requires accepted trace assertions; `setPlanMode/setMemoryMode/resetMemory` require X opt-in + exact probe and consume existing Phase 6 route specs.

## Implementation Steps

1. Test agent discovery precedence, invalid TOML, symlink/out-of-root, file update, and sanitized descriptor; keep it advisory/read-only.
2. Build pure tree projection from normalized facts and tests for interleaved parent/children/activity/error/approval. Do not subscribe to gateway or retain mutable agent state.
3. Implement tree panel/open-child and control service; revalidate state/epoch on click. For stop-all, issue only schema-proven interrupts and report each success/error/outcome-unknown.
4. Implement the content-root target picker and immediate dispatch-time project/root identity revalidation. Reject arbitrary external paths without presenting an override workflow.
5. Keep worktree/cloud routes disabled. Capture `/side` trace and enable only if parent transcript/cwd isolation, ephemeral cleanup, and returned thread semantics pass. Implement X plan/memory service behind existing specs.

## Todo

- [x] Implement advisory agent catalog and pure tree projection.
- [x] Implement open-child and schema-permitted controls.
- [x] Implement content-root-only targets and dispatch-time ownership revalidation.
- [x] Keep worktree/cloud disabled; validate side trace and X services.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Interleaved child activity/approval | pure projection matches normalized facts; correct origin |
| Critical | State/epoch changes before control click | control disabled/rejected; no stale request |
| Critical | `/worktree`/cloud selected | visible-disabled; no filesystem mutation or fallback RPC |
| High | External, cross-project, or changed target supplied | dispatch rejected; no thread/turn request sent |
| High | Side trace isolation | parent transcript/cwd unchanged; ephemeral lifecycle proven before enable |
| High | X off/probe fails | plan/memory disabled; stable chat unaffected |
| Medium | Invalid agent TOML | advisory diagnostic only; server tree unaffected |

## Success Criteria

- [x] `./gradlew test --tests '*AgentTreeProjectionTest' --tests '*AgentControllerTest' --tests '*ExecutionTargetServiceTest' --tests '*ExperimentalFeatureServiceTest'`
- [x] `./gradlew verifyProtocolContract verifyPluginProjectConfiguration verifyPluginStructure`
- [x] `./gradlew runIde`: delegated child tree/approval/open/control works; project targets are rooted/revalidated; worktree/cloud remain honest disabled states.

## Risk Assessment / Security

A second agent store would diverge from conversation facts; projection must stay pure. Controls bind current epoch/thread/turn and expose partial stop-all outcomes. External cwd can expose arbitrary files, so only canonical owned content roots are selectable and dispatch revalidates their identity. Worktree/cloud remain disabled rather than implementing unproven filesystem/service behavior.

## Dependency Map / Next Steps

Requires Phase 5 keyed approvals and Phase 6 sealed route specs. Phase 8 audits pure ownership, stale controls, target revalidation, side/X gates, and disabled worktree/cloud behavior.
