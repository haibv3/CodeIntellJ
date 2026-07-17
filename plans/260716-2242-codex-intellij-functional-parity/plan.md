---
title: "Codex IntelliJ Functional Parity"
description: "Build a native IntelliJ client for documented Codex IDE workflows over the public app-server contract."
status: completed
priority: P1
effort: "XL (8 runnable phases)"
tags: [feature, intellij, kotlin, app-server, experimental]
blockedBy: []
blocks: []
created: 2026-07-16
---

# Codex IntelliJ Functional Parity

## Overview

Create an internal Linux plugin for IntelliJ IDEA Community 2026.1.4 (`261.26222.65`) with native Tool Window, actions, context, approvals, review, settings, slash commands, and agent visibility. `codex app-server` 0.144.5 over stdio is authoritative; stable APIs default on, experimental APIs require opt-in and proof, and unknown envelopes remain non-fatal.

## Package / Build Assumptions

- One Gradle module; package seams under `dev.haibachvan.codexintellij`.
- Java 21, Kotlin 2.3.20, IntelliJ Platform Gradle Plugin 2.18.1, exact IDEA `2026.1.4`, `sinceBuild=261`, `untilBuild=261.*`; complete verified Gradle 9.0.0 wrapper committed (required by Platform plugin 2.18.1).
- Handwritten minimal wire DTOs plus `JsonObject` compatibility boundary; exact method/schema map validated against full stable/experimental trees under `protocol-schema/codex-0.144.5`; sanitized goldens under `src/test/resources/fixtures/appserver/0.144.5`.

## Scope Boundary

In: full documented local parity, all 6 IDE actions and 22 slash commands, multi-panel chat, typed unsaved-buffer context, approvals/diff/review, stable account/MCP/settings, custom agents and subagent activity/control where proven. Project/context targets are restricted to canonical IntelliJ content roots. Out: proprietary UI/assets, Marketplace/signing/telemetry, broad OS/IDE, arbitrary external directories, remote WebSocket/private APIs. Cloud and `/worktree` stay visible-disabled without a public contract; `/plan` and `/memories` are X-gated; `/side` needs a captured semantic trace.

## Phases

| # | Phase | Status | Depends on |
|---|---|---|---|
| 1 | [Bootstrap and Capability Contract](./phase-01-start.md) | Completed | — |
| 2 | [App Server Lifecycle and Capabilities](./phase-02-app-server-lifecycle-and-capabilities.md) | Completed | 1 |
| 3 | [Conversation State and Native Chat](./phase-03-conversation-state-and-native-chat.md) | Completed | 2 |
| 4 | [IDE Context and Editor Actions](./phase-04-ide-context-and-editor-actions.md) | Completed | 3 |
| 5 | [Execution Approvals, Review, and Diff](./phase-05-execution-approvals-review-and-diff.md) | Completed | 3, 4 |
| 6 | [Settings, MCP, and Slash Commands](./phase-06-settings-mcp-and-slash-commands.md) | Completed | 5 |
| 7 | [Agents and Advanced Execution Targets](./phase-07-agents-and-advanced-execution-targets.md) | Completed | 5, 6 |
| 8 | [Resilience, Parity Audit, and Documentation](./phase-08-resilience-parity-audit-and-documentation.md) | Completed | 7 |

## Dependencies / Success Criteria

- Runtime: revalidated, user-confirmed `codex` 0.144.5 binary; local stdio; app-server owns auth/config/conversation state. Each phase requires prior tests and runnable `runIde` behavior.
- [x] `./gradlew test uiTest verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin` passes on Java 21 from a clean checkout.
- [x] Fresh 261.26222.65 sandbox passes prompt → stream → approval → patch → diff, restart/resume, multi-panel, signed-out/login, context, MCP, and delegated-agent flows with zero EDT/internal-mode log failures.
- [x] Exact 6-action/22-command semantic contracts pass; unresolved/X/G routes are visible-disabled and never raw prompts.
- [x] Full schema-tree hashes, reviewed binary provenance, exact method/schema map, context encoded JSON, and sanitized fixtures pass CI; no auth/token or unredacted content persists/exports.

## References

[Product](../reports/260716-2232-codex-intellij-functional-parity.md) · [App server](../reports/260716-2240-app-server-capability-research.md) · [Platform](../reports/260716-2240-intellij-platform-research.md) · [Tests](../reports/260716-2240-parity-test-research.md)

## Red Team Review

Session: 2026-07-16. Consolidation: 26 raw findings → 15 accepted, 0 rejected. Severity: 2 Critical, 12 High, 1 Medium.

| # | Sev | Accepted decision | Phases |
|---|---|---|---|
| 1 | C | Complete, checksum-verified wrapper and clean-checkout gate | 1, 8 |
| 2 | C | Executable trust/revalidation and reviewed provenance | 1, 2 |
| 3 | H | Minimal handwritten wire boundary + exact full-tree schema map | 1, 2 |
| 4 | H | Process epochs, keyed/fingerprinted approval outcome states | 2, 5 |
| 5 | H | Single sequencer, watermark, monotonic snapshot merge | 2, 3 |
| 6 | H | Drain-first framing/backpressure in transport | 2 |
| 7 | H | Serialized follow-up queue FSM; no ambiguous replay | 3 |
| 8 | H | One normalized server owner; narrow projections/recovery ownership | 3, 5, 7, 8 |
| 9 | H | Stable account lifecycle and auth-required retry | 6 |
| 10 | H | Trace-backed diff baseline precedence and warning fallback | 4, 5 |
| 11 | H | Per-command semantic fixtures and sealed domain routes | 6, 7 |
| 12 | H | Exact context-field → `turn/start` encoding contract | 4 |
| 13 | H | Worktree disabled; project targeting restricted to canonical content roots | 6, 7 |
| 14 | H | Config allowlist, MCP consent keys, early redaction/feedback byte equality | 2, 6 |
| 15 | M | Pinned Starter/Driver `uiTest` harness and log oracle | 1, 8 |

### Whole-Plan Consistency Sweep

Re-read `plan.md` plus all 8 phase files after applying 15 decision deltas and the validation decisions below. Searched superseded ownership, route, DTO, worktree, recovery, approval-key, redaction, and external-target terms; reconciled inventories/interfaces/tests/risks. Unresolved contradictions: 0.

## Validation Log

Confirmed by user on 2026-07-17 under HOLD SCOPE:

- `/cloud`, `/cloud-environment`, and `/worktree` remain visible-disabled until a public contract exists; `/side` remains disabled until an accepted semantic trace exists.
- `/project` and all context/target selection are restricted to canonical IntelliJ content roots; no arbitrary external-directory override is implemented.
- The complete stable and experimental Codex 0.144.5 schema trees are committed and hash-verified for reproducible build/test behavior.

<!-- slug: codex-intellij-functional-parity -->
