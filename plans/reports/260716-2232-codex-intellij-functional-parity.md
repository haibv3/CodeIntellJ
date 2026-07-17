---
title: Codex IntelliJ Functional Parity Brainstorm
date: 2026-07-16
status: approved
mode: markdown
target: personal-internal
---

# Codex IntelliJ Functional Parity Brainstorm

## Summary

Build a personal/internal IntelliJ IDEA plugin that provides functional parity
with the Codex VS Code extension. Use native IntelliJ UI and `codex app-server`;
do not copy proprietary source, assets, branding, or VS Code-specific UI.

Target environment: Linux, IntelliJ IDEA Community 2026.1.4, Codex CLI 0.144.5.
Agents and slash commands are first-class requirements, not later add-ons.

## 1. Solution-Jumping Diagnosis

“Clone Codex VS Code” compresses the real pain: Codex has a rich editor-attached
workflow in VS Code, while IntelliJ users must leave their normal IDE workflow or
accept weaker context, review, and agent visibility.

## 2. Underlying Problem

An IntelliJ user needs the same local Codex workflow—context capture, agent work,
approvals, review, and session controls—without switching editor or terminal.

## 3. Assumption Challenges

| Assumption | Risk if wrong | Validation |
|---|---|---|
| UI must match VS Code | High effort with no workflow value | Native IntelliJ usability test |
| All VS Code features are public | First-party/experimental APIs may block parity | Capability matrix against generated CLI schema |
| One release can deliver everything | Large coupled scope, brittle result | Phase gates with runnable vertical slices |
| Slash commands are prompt text | Incorrect state transitions and weak UX | Typed routing per command |
| Agent activity can be inferred from logs | Fragile and incomplete | Consume typed collaboration items/events |

## 4. Problem Statement

- User/context: personal/internal IntelliJ IDEA user on Linux.
- Struggle: Codex IDE workflow exists in VS Code but not in the preferred IDE.
- Cause: editor integrations, commands, review UI, and agent activity are client-specific.
- Consequence: context switching, weaker IDE context, poorer diff/approval experience.
- Success: Codex tasks can be completed and reviewed entirely inside IntelliJ.

## 5. Alternative Framings

### A. Chat panel problem

Embed chat only. Cheap, but inferior to the existing terminal and not accepted.

### B. Agent workflow problem

Integrate tasks, streaming, approvals, context, file changes, and agents. Strong
value, but incomplete without the VS Code control surface.

### C. Functional parity problem — selected

Recreate documented workflows and capabilities with native IntelliJ conventions,
including agents and slash commands. Gate unavailable features by CLI/account
capabilities instead of faking support.

## 6. Evidence Status

Medium-to-strong technical evidence:

- OpenAI documents app-server as the interface powering rich clients such as the
  VS Code extension.
- Current CLI-generated schema exposes thread/turn lifecycle, streaming,
  approvals, config, review, MCP, collaboration items, subagent activity, and
  parent-child thread relationships.
- Product demand is validated for one known internal user; broader market demand
  is intentionally irrelevant.

## 7. Validation Plan

- Generate stable and experimental schemas from the installed Codex CLI.
- Maintain a parity matrix for every documented IDE feature and slash command.
- Build vertical slices: prompt → stream → command/patch → approval → diff.
- Run the plugin in IntelliJ IDEA Community 2026.1.4 sandbox.
- Kill criterion: a required workflow is only available through inaccessible
  first-party APIs and has no honest capability-gated fallback.

## 8. Stakeholder Message

We will reproduce Codex VS Code functionality, not its proprietary implementation
or visual branding. Features unavailable to third-party clients will be shown as
capability-gated with an explicit reason rather than silently omitted.

## Requirements

### Expected Output

A Kotlin IntelliJ Platform plugin with a native Codex Tool Window, app-server
integration, IDE context, review UI, agents, slash commands, settings, and tests.

### Acceptance Criteria

- Run inside IntelliJ IDEA Community 2026.1.4 on Linux.
- Start/connect/restart app-server and resume threads safely.
- Stream messages, reasoning/activity, commands, patches, plans, and tool events.
- Support approvals, denial, interruption, queue/steer, history, and multiple panels.
- Add selection, current file, and automatic IDE context.
- Show native diffs and navigate to changed files/lines.
- Show custom agents and subagent parent/child activity with available controls.
- Autocomplete and type-route every documented IDE slash command.
- Mark account-, version-, or experimental-gated features explicitly.
- Never crash on unknown protocol events or block the IntelliJ EDT.

### Scope Boundary

Out of scope: Marketplace publishing, signing, telemetry, broad IDE/OS compatibility,
pixel-perfect VS Code UI, proprietary assets, and reverse-engineered private APIs.

### Non-Negotiable Constraints

- Kotlin with IntelliJ Platform Gradle Plugin 2.x.
- Native IntelliJ UI; app-server is the agent source of truth.
- Protocol types tied to the installed CLI schema behind a compatibility adapter.
- No embedded credentials or duplicated Codex authentication state.

### Touchpoints

Repo is empty. Work creates the complete build, source, test, resource, docs, and
plan structure; no existing public contract or implementation requires migration.

## Evaluated Approaches

| Approach | Advantages | Costs | Decision |
|---|---|---|---|
| Native IntelliJ + app-server | Best editor/diff/action integration | More native UI work | Selected |
| JCEF web UI + app-server | Visually closer to VS Code | Heavy, weak IDE integration | Rejected |
| Embedded Codex terminal | Fast | Not functional parity | Rejected |

## Approved Architecture

1. Native Tool Window and IntelliJ actions collect user input and IDE context.
2. Conversation state store reduces typed app-server events into UI state.
3. Slash command registry routes commands to app-server methods or client actions.
4. Agent registry models custom agents, collaboration calls, and child threads.
5. Capability registry gates stable, experimental, account, and version features.
6. Typed protocol adapter owns JSONL/JSON-RPC and unknown-event compatibility.
7. Process manager owns executable discovery, lifecycle, restart, and diagnostics.

Suggested boundaries: `process`, `protocol`, `session`, `agents`, `commands`,
`ide-context`, `review`, `settings`, and `ui`.

## Delivery Strategy

1. Protocol/process foundation and compatibility fixtures.
2. Thread/turn/chat vertical slice with streaming and interruption.
3. Commands, approvals, file changes, and native diff.
4. IDE context, settings, history, and multiple panels.
5. Agents/subagents and complete slash command routing.
6. Worktree/cloud/memories/advanced features where capabilities permit.
7. Parity audit, sandbox verification, resilience, and documentation.

## Risks

- App-server experimental schema changes: isolate behind adapter and fixtures.
- Scope size: phase by runnable vertical slice; no big-bang UI build.
- UI freezes: all process/protocol work off EDT; UI updates marshalled to EDT.
- First-party-only behavior: capability gate and document; never impersonate VS Code.
- Version drift: record CLI/schema version and fail with actionable compatibility UI.

## Success Metrics

- 100% documented slash commands represented in the command registry.
- 100% documented IDE features represented in parity matrix.
- All supported core workflows pass integration fixtures and sandbox smoke tests.
- Unknown events degrade safely and are diagnosable.
- No EDT violations in tested interaction paths.

## References

- [Codex App Server](https://learn.chatgpt.com/docs/app-server.md)
- [Codex IDE commands](https://learn.chatgpt.com/docs/developer-commands.md?surface=ide)
- [Codex IDE settings](https://learn.chatgpt.com/docs/developer-settings.md?surface=ide)
- [IntelliJ Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)

## Next Steps

Choose standard planning or TDD planning. Planning must turn the delivery strategy
into phase files, exact file ownership, protocol fixtures, and verification gates.

## Unresolved Questions

- Planning mode: standard or TDD.
