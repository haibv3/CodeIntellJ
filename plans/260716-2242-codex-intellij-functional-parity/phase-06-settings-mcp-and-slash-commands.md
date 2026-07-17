---
phase: 6
title: "Settings, MCP, and Slash Commands"
status: completed
priority: P1
dependencies: [5]
---

# Phase 6: Settings, MCP, and Slash Commands

## Overview

Add stable account/config/model/MCP surfaces and exact semantic contracts for all 22 slash commands using sealed route specs and small domain handlers. Runnable result: every command executes its fixture-proven typed sequence or remains visible-disabled with reason.

## Context Links

- [Phase 5 approval/review](./phase-05-execution-approvals-review-and-diff.md); [typed route evidence](../reports/260716-2240-app-server-capability-research.md); [VS-07/09/11/13](../reports/260716-2240-parity-test-research.md)
- Gate: Phase 5 keyed approval and trace/evidence diff tests green.

## Requirements

- Registry exactly: `/approve`, `/cloud`, `/cloud-environment`, `/compact`, `/fast`, `/feedback`, `/fork`, `/goal`, `/ide-context`, `/init`, `/local`, `/mcp`, `/memories`, `/model`, `/personality`, `/plan`, `/project`, `/reasoning`, `/review`, `/side`, `/status`, `/worktree`.
- Route model: sealed `CommandRouteSpec` (`Rpc`, `RpcSequence`, `ClientState`, `Deferred`, `Unavailable`) owns preconditions, typed args, exact request sequence, state/side effect, cancellation, and error semantics. Domain handlers are small; no catch-all handler or Phase 7 handler replacement.
- Account: stable `account/read`, login start, login cancel, logout, expiry/auth-required transitions, signed-out UI/fixtures, sparse rate/usage. Never persist tokens. On auth-required, only a user-confirmed idempotent read/list may retry once after successful login; mutations/turns/approvals/config/MCP calls never auto-replay.
- Config: local UI preferences only in `PersistentStateComponent`. Server writes use sealed `WritableConfigKey` allowlist plus `expectedVersion`; auth, sandbox, approval, trust, executable/environment, and other security paths are read-only or use a dedicated confirmation workflow—never arbitrary string path writes.
- MCP: status/OAuth/reload/resource/progress stable. Every direct call or elicitation consent is keyed by `(ProcessEpoch, serverId, toolName, payloadSha256)`, one-shot, and bound to immutable preview bytes/hash; epoch/payload change invalidates consent.
- Feedback: accepts only Phase 2 `RedactedBundle`. Preview displays exact bundle bytes and SHA-256 passed to `feedback/upload`; any metadata/envelope bytes are separately previewed. Cancel sends nothing; fallback is local save/link, never raw diagnostics.
- Gating: unresolved semantics are visible-disabled. `/worktree`, `/cloud`, `/cloud-environment` cannot execute; `/side` stays disabled until Phase 7 trace; `/plan`/`/memories` X-off by default.

## Per-Command Semantic Contract

| Command | Preconditions / args | Exact typed sequence | State / side effect; cancel / error |
|---|---|---|---|
| `/approve` | eligible guardian denial + key/fingerprint | `thread/approveGuardianDeniedAction` | keyed denial state; cancel no send; stale disabled |
| `/cloud` | no public contract | none | visible-disabled; never local fallback |
| `/cloud-environment` | no public contract | none | visible-disabled |
| `/compact` | active steerable normal thread | `thread/compact/start` | compact turn blocks queue; cancel before send; typed error |
| `/fast` | catalog advertises tier | `model/list` → stage `TurnOverrides.serviceTier` | next turn only; picker cancel no change |
| `/feedback` | `RedactedBundle` + consent | preview exact bytes/hash → `feedback/upload` | no automatic upload; cancel/local fallback |
| `/fork` | thread + optional last turn | `thread/fork` | bind returned thread to new panel; request failure leaves source |
| `/goal` | schema-proven get/set/clear subaction | `thread/goal/get|set|clear` | reducer owns result; unsupported subactions disabled |
| `/ide-context` | panel present | client `ContextAttachmentStore` toggle | future drafts only; reversible |
| `/init` | accepted versioned scaffold/target/overwrite fixture | deferred; then normal typed `turn/start` with fixture-proven encoded intent | visible-disabled until fixture; cancel no turn |
| `/local` | trusted local app-server | client target=`Local(contentRoot)` | future thread/turn cwd only |
| `/mcp` | signed in/capability | `mcpServerStatus/list`; chosen OAuth/reload/read/call route | keyed consent for call/elicitation; isolated errors |
| `/memories` | X opt-in + probe/account | `thread/memoryMode/set`; optional `memory/reset` separately | warning/confirmation; X-off disabled |
| `/model` | nonempty `model/list` | `model/list` → stage `TurnOverrides.model` | next turn; picker cancel unchanged |
| `/personality` | catalog support | `model/list` → stage `TurnOverrides.personality` | unsupported value disabled |
| `/plan` | X opt-in + probe | `collaborationMode/list` → stage `TurnStart.collaborationMode` | next turn; no prompt emulation |
| `/project` | canonical IntelliJ content root | client target selection → next `thread/start/turn/start.cwd` | canonical root revalidate; external paths rejected; cancel unchanged |
| `/reasoning` | catalog support | `model/list` → stage `TurnOverrides.effort` | next turn; unsupported disabled |
| `/review` | Git/target/delivery | `review/start` | bind returned review thread; cancel before request |
| `/side` | accepted 0.144.5 trace | deferred Phase 7 sequence | disabled until proof; never approximate |
| `/status` | connected; account may be signed out | local thread/token + `account/read`, `account/rateLimits/read`, `account/usage/read` | partial/signed-out state explicit |
| `/worktree` | no public contract | none | visible-disabled; no Git create/select workflow |

## Architecture

`SlashCommandRegistry` stores sealed specs loaded/verified against `slash-command-contracts.json`; autocomplete filters by live availability. `SlashCommandDispatcher` resolves a spec and delegates to `ThreadCommandHandler`, `PreferenceCommandHandler`, `ReviewCommandHandler`, `FeedbackCommandHandler`, `McpCommandHandler`, or explicit unavailable/deferred result. `AccountController`, `ServerConfigController`, and `McpController` use gateway epoch semantics.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/CommandRouteSpec.kt` | 190 LOC | sealed semantics contract |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/SlashCommandRegistry.kt` | 190 LOC | exact-22/spec snapshot |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/SlashCommandDispatcher.kt` | 150 LOC | domain delegation/no raw prompt |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/ThreadCommandHandler.kt` | 170 LOC | compact/fork/goal/init |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/PreferenceCommandHandler.kt` | 140 LOC | model/personality/reasoning/fast |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/ReviewCommandHandler.kt` | 90 LOC | review/approve bridge |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/FeedbackCommandHandler.kt` | 130 LOC | exact bundle upload |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/commands/McpCommandHandler.kt` | 110 LOC | MCP route/consent delegation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/settings/CodexSettingsState.kt` | 130 LOC | non-secret local persistence |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/settings/CodexConfigurable.kt` | 220 LOC | validation/reset test |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/settings/WritableConfigKey.kt` | 100 LOC | typed allowlist/security paths |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/settings/ServerConfigController.kt` | 190 LOC | precedence/conflict/confirmation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/account/AccountController.kt` | 220 LOC | read/login/cancel/logout/expiry/retry |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/AccountPanel.kt` | 150 LOC | signed-out/login UI |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/mcp/McpConsent.kt` | 110 LOC | epoch/server/tool/hash identity |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/mcp/McpController.kt` | 270 LOC | OAuth/call/elicitation consent |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/SlashCompletionPopup.kt` | 180 LOC | keyboard/filter tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/commands/slash-command-contracts.json` | 22 records | exact semantic source |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/commands/init-command-contract-0.144.5.json` | captured/reviewed | scaffold/target/overwrite gate |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/account-signed-out-login-expiry.jsonl` | 120 lines | stable auth lifecycle |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/mcp-config-feedback.jsonl` | 150 lines | consent/config/upload golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/commands/SlashCommandContractTest.kt` | 340 LOC | all semantic dimensions |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/account/AccountControllerTest.kt` | 260 LOC | signed-out/expiry/retry |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/settings/ServerConfigControllerTest.kt` | 230 LOC | writable/security key matrix |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/mcp/McpConsentTest.kt` | 260 LOC | one-shot/immutable preview |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/commands/FeedbackCommandHandlerTest.kt` | 180 LOC | preview/upload byte equality |

## Functions and Interfaces Checklist

- [x] `CommandRouteSpec` sealed variants encode `preconditions`, `ArgumentSpec`, `RequestStep[]`, `StateEffect`, `CancelEffect`, `ErrorEffect`; registry has exactly 22 unique fixtures/specs.
- [x] `SlashCommandDispatcher.dispatch(invocation,panel)` never forwards literal slash text and never replaces a handler later; unavailable/deferred is a first-class result.
- [x] `AccountController.read/startLogin/cancelLogin/logout`; `onAuthRequired(operation)` transitions signed out/expired and allows one confirmed retry only when `operation.retryClass == IdempotentRead`.
- [x] `WritableConfigKey` is the only normal write input; security-sensitive settings expose dedicated typed confirmation methods, not `write(path:String,...)`.
- [x] `McpConsentKey(epoch,serverId,toolName,payloadSha256)` plus immutable preview bytes/hash; consume once; resolved/disconnect/epoch/payload change invalidates.
- [x] `FeedbackCommandHandler.preview(bundle)` returns exact immutable upload bytes/hash; `upload(preview)` checks equality immediately before `feedback/upload`.

## Implementation Steps

1. Freeze 22 semantic records before handlers; schema-validate every request step and assert precondition/args/side effect/cancel/error coverage plus no raw slash route. Capture/review the versioned `/init` scaffold, target, overwrite, and response oracle; keep `/init` disabled if public evidence is insufficient.
2. Implement sealed registry/dispatcher and small handlers. Unsupported subactions/commands remain visible-disabled; Phase 7 supplies availability/effect services without replacing specs.
3. Implement account lifecycle/signed-out panel and fixtures: read, login started/completed/cancelled, logout, expiry, auth-required. Retry one confirmed idempotent read only; prove no mutation replay/token persistence.
4. Implement local settings and typed server config allowlist/version conflicts/managed overrides; security paths use read-only or dedicated confirmation.
5. Implement MCP status/OAuth/reload/read/progress and consent-keyed direct call/elicitation. Preview immutable bytes/hash and invalidate on epoch/server/tool/payload change.
6. Implement feedback from `RedactedBundle` only; byte/hash equality assertion at upload; command tour in signed-in and signed-out sandbox.

## Todo

- [x] Freeze exact 22-command semantic fixture and sealed specs.
- [x] Implement small domain handlers and completion UI.
- [x] Implement stable account lifecycle and signed-out/auth retry policy.
- [x] Implement typed config allowlist, MCP consent, feedback byte equality.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | 22-contract snapshot | each semantic dimension present; exact schema methods; no raw prompt |
| Critical | Auth expiry during operation | signed-out UI; only confirmed idempotent read retries once; mutation never replays |
| Critical | MCP payload/epoch changes after consent | consent invalid; new immutable preview required |
| Critical | Feedback preview then upload | exact same redacted bytes/SHA; mismatch/cancel sends nothing |
| High | Arbitrary/security config path | rejected or dedicated confirmation; managed conflict preserved |
| High | X/G/deferred command | visible-disabled reason; zero unsupported wire bytes |
| Medium | Sparse account/model/rate values | honest partial UI; no fabricated defaults |

## Success Criteria

- [x] `./gradlew test --tests '*SlashCommandContractTest' --tests '*AccountControllerTest' --tests '*ServerConfigControllerTest' --tests '*McpConsentTest' --tests '*FeedbackCommandHandlerTest'`
- [x] `./gradlew verifyProtocolContract verifyPluginProjectConfiguration verifyPluginStructure`
- [x] `./gradlew runIde`: all 22 commands satisfy contract in signed-in/signed-out/X-off states; MCP/feedback previews prove immutable byte hashes.

## Risk Assessment / Security

Command names do not prove semantics; fixture every request/cancel/error path and disable unresolved routes. Auth remains app-server-owned and tokens never enter settings/logs. Arbitrary config writes could weaken security, so use typed allowlist/dedicated confirmation. MCP/feedback consent binds immutable content and process identity; no session-wide consent or post-preview mutation.

## Dependency Map / Next Steps

Requires Phase 5 review/approval. Phase 7 implements target/agent/X services behind existing route specs, while `/worktree` remains unavailable. Phase 8 audits every semantic fixture and signed-out/consent flow.
