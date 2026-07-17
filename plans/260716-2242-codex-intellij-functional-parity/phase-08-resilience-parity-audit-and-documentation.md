---
phase: 8
title: "Resilience, Parity Audit, and Documentation"
status: completed
priority: P1
dependencies: [7]
---

# Phase 8: Resilience, Parity Audit, and Documentation

## Overview

Verify cross-layer recovery, disposal, privacy, parity, and a pinned full-product Driver harness; document automated versus manual acceptance. Runnable result: a clean Linux 261.26222.65 sandbox passes supported workflows and failure oracles with explicit X/G states.

## Context Links

- [Plan/red-team sweep](./plan.md); [Phase 7](./phase-07-agents-and-advanced-execution-targets.md); [G0–G5 strategy](../reports/260716-2240-parity-test-research.md); [platform test/thread risks](../reports/260716-2240-intellij-platform-research.md)
- Gate: all Phase 1–7 focused tests and runnable sandbox slices green.

## Requirements

- Recovery ownership: Phase 2 gateway/lifecycle actor alone owns epochs/reconnect transport. Phase 3 `ConversationController` alone owns `list/read/resume` snapshot merge. Panel models retain drafts/queue actors. No cross-layer recovery state owner; any orchestration helper is stateless sequencing only.
- Verification: crash/EOF at handshake/idle/stream/approval/patch; old epoch and stale watermark; no unsafe replay; queue/approval ambiguous outcomes; Phase 2 drain/coalescing/redaction stress; project/panel/plugin disposal and leaks.
- Driver dependency: use IntelliJ Platform Gradle Plugin 2.18.1 `testFramework(TestFrameworkType.Starter, configurationName="uiTestImplementation")`, which supplies JetBrains Starter/Driver; pin IDEA `2026.1.4` build `261.26222.65`, lock resolved `uiTestRuntimeClasspath`, and checksum it in dependency verification. No imaginary direct Driver coordinate.
- UI harness: dedicated `src/uiTest`, `uiTest` task, exact built plugin injection, Linux fake app-server executable through normal trust-confirmation UI, per-scenario protocol scripts, global 8-minute/startup 3-minute/action 30-second/shutdown 60-second timeouts, and deterministic artifact collection.
- Log oracle: run IDE internal mode; fail automated test on uncaught IDE/plugin exception, EDT assertion, `SlowOperations`/blocking `waitFor` on EDT/read lock, disposal/leak marker, thread dump timeout, or unredacted secret-corpus marker in `idea.log`/artifacts.
- Audit/docs: exact six actions/22 command contracts, full schema map/tree hashes, account signed-out flows, context bytes, approval/diff evidence, agent projection, targets/X gates, privacy and unsupported list.

## Architecture

Production recovery is composition, not a new store: `AppServerGateway.reconnect()` establishes epoch; stateless `RecoveryWorkflow` invokes `ConversationController.reconcile(threadIds)`; reducer merge rules decide facts; panels keep drafts/ambiguous queues. Test process uses JetBrains Starter/Driver to install built plugin into exact IDEA, supplies `FakeAppServerFixture`, drives accessible UI, then `IdeaLogOracle` evaluates collected artifacts.

## Automated vs Manual Acceptance

| Layer | Automated `uiTest` | Manual sandbox checklist |
|---|---|---|
| Core | startup, trust confirmation, prompt/stream, context bytes, approval, patch/diff, two panels | visual usability, shortcuts, native diff legibility |
| Failure | fake EOF/restart/stale epoch/watermark, signed-out/login, MCP failure, child activity, X-off | kill real 0.144.5, inspect recovery messaging |
| Oracle | accessibility assertions, state labels, `idea.log`, exceptions, EDT/internal-mode, leaks, artifacts | human review of warnings, disabled reasons, consent wording |
| External | deterministic local fake only; no network/account dependency | real Codex login/MCP/account and accepted `/side` trace when available |

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/build.gradle.kts` | +120 LOC | `uiTest` source/task/Starter dependency/timeouts |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/gradle/verification-metadata.xml` | generated/reviewed | Starter/Driver artifact checksums |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradle.lockfile` | generated/reviewed | exact resolved test stack incl. `uiTestRuntimeClasspath` |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/AppServerGateway.kt` | +40 LOC | reconnect epoch API |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/ConversationController.kt` | +70 LOC | authoritative reconcile workflow |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/RecoveryWorkflow.kt` | 60 LOC | stateless ordering only |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/CodexProjectService.kt` | +50 LOC | compose/dispose existing owners |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/crash-restart-resume.jsonl` | 150 lines | recovery golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/session/RecoveryWorkflowTest.kt` | 280 LOC | epoch/controller/panel ownership |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ParityContractTest.kt` | 340 LOC | G0–G5/inventory audit |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/CodexDriverSmokeTest.kt` | 300 LOC | automated full-product flows |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/FakeAppServerFixture.kt` | 220 LOC | deterministic stdio scenarios |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/IdeaLogOracle.kt` | 180 LOC | EDT/exception/secret oracle |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/UiTestArtifacts.kt` | 130 LOC | screenshots/logs/dumps/transcripts |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/resources/fake-app-server/fake-codex.sh` | 80 lines | Linux trusted fake executable |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/resources/projects/basic/.gitkeep` | 0 LOC | local deterministic content root |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/README.md` | 190 LOC | setup/run entry point |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/system-architecture.md` | 240 LOC | ownership/data flow |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/capability-parity.md` | 240 LOC | feature/command contracts |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/privacy-and-security.md` | 180 LOC | trust/redaction/consent policy |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/troubleshooting.md` | 170 LOC | version/recovery guidance |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/internal-release-checklist.md` | 160 LOC | automated/manual G0–G5 |

## Functions and Interfaces Checklist

- [x] `RecoveryWorkflow.reconnectAndReconcile(gateway,controller,threadIds)` is stateless; gateway creates epoch, controller merges snapshots, and no panel draft/queue moves ownership.
- [x] `ConversationController.reconcile` marks in-flight mutation outcomes unknown, lists/reads/resumes, and applies Phase 3 watermark/terminal merge; never retries turns/approvals/config/MCP/controls.
- [x] Gradle creates `uiTest` source set/configuration; `TestFrameworkType.Starter` resolves Starter/Driver for exact IDEA; dependency lock/verification changes require review; task depends on `buildPlugin`.
- [x] `FakeAppServerFixture` copies/chmods a per-test executable, exposes its hash, drives trust confirmation normally, supports scripted chunks/EOF/delay/server requests, and stores only sanitized transcript.
- [x] `IdeaLogOracle.assertClean(artifacts)` checks `idea.log`, error directory, thread dumps, internal-mode/EDT/read-lock markers, unhandled exceptions/leaks, and secret sentinels.
- [x] Artifacts land under `build/ui-test-artifacts/<test>/`: JUnit XML, screenshot on failure, `idea.log`, IDE stdout/stderr, thread dump on timeout, redacted fake transcript, environment key names—not values.

## Implementation Steps

1. Configure `uiTest` per JetBrains Starter/Driver guidance with exact IDEA version/build, JUnit Platform, dependency locking/verification, built-plugin path, test source/resource roots, and fixed timeouts/artifact directory.
2. Implement fake executable fixture and scripted scenarios. Exercise normal binary trust confirmation; do not add production bypass. Seed unique secret sentinels and prove Phase 2 redaction before artifact capture.
3. Implement stateless recovery workflow/tests across EOF points, old epoch/ID, stale snapshot watermark, approval/queue outcome unknown, multiple panels/children; assert no mutation replay and correct ownership.
4. Implement Driver critical flow with accessibility-stable selectors and bounded waits; collect screenshot/log/dump/transcript artifacts on failure. Add `IdeaLogOracle` because IDE-process failures do not automatically fail Driver tests.
5. Run parity audit linking VS-00–13, six actions, 22 command semantic fixtures, schema map/tree, signed-out account, consent keys, context JSON, diff trace, agent/target/X states to exact tests/docs.
6. Write docs and separate automated `./gradlew uiTest` from real-account/manual `./gradlew runIde` checklist; run clean checkout, unit/platform/UI/verifier gates.

## Todo

- [x] Complete stateless reconnect/controller merge verification.
- [x] Pin/configure Starter/Driver `uiTest`, fake server, timeouts, artifacts, log oracle.
- [x] Complete parity/privacy/ownership audits and docs.
- [x] Execute automated and manual G0–G5 checklists separately.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | EOF during approval/patch then reconnect | new epoch; unknown outcome; controller merge; no replay/second store |
| Critical | Driver test has IDE exception/EDT warning but UI assertion passes | `IdeaLogOracle` fails test with artifact link |
| Critical | Fake stream contains secret sentinel | absent from diagnostic ring, UI, idea.log, transcript/artifacts |
| Critical | Clean checkout `uiTest` | exact Starter/Driver lock + IDEA build; deterministic fake flow passes |
| High | Timeout/hang/disposal | bounded failure; screenshot/log/thread dump; no orphan IDE/app-server |
| High | Exact parity inventories | six actions + 22 semantic specs, schemas, fixtures, docs fully linked |
| Medium | Real-account/manual-only workflow absent in CI | documented manual gate; automated suite remains deterministic |

## Success Criteria

- [x] `./gradlew cleanCheckoutGate test uiTest`
- [x] `./gradlew verifyCodexSchemaManifest verifyProtocolContract verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin`
- [x] Automated `uiTest` uses IDEA 2026.1.4/261.26222.65, locked Starter/Driver, fake app-server, timeouts/artifacts, and passes `IdeaLogOracle` with zero EDT/internal-mode/secret failures.
- [x] Manual `./gradlew runIde` checklist separately passes real 0.144.5 login/MCP/sandbox vertical slice; unavailable worktree/cloud/X/side states remain explicit.
- [x] `docs/capability-parity.md` accounts for VS-00–13, all action/command semantics, agent/target/account/privacy gates, and zero mislabeled unsupported behavior.

## Risk Assessment / Security

Driver UI success alone misses IDE-process failures, so `idea.log`/error/thread-dump oracle is release-blocking. Dependency locking/checksums and exact IDE build prevent silent test-stack drift. Fake executable must traverse normal trust confirmation, never a production bypass. Recovery orchestration stays stateless: gateway owns epochs, controller owns server merge, panels own drafts/queue. Artifact collection accepts only sanitized diagnostics/transcripts and fails on secret sentinels.

## Dependency Map / Next Steps

Requires Phase 7 and complete chain 1→2→3→4→5→6→7→8. Completion yields internal artifact only; Marketplace/signing, remote WebSocket, public worktree/cloud contracts, or broad compatibility require a future plan.
