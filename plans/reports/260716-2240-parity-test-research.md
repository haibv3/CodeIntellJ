---
title: Codex IntelliJ Functional-Parity Test Research
date: 2026-07-16
status: complete
priority: P1
tags: [feature, intellij, protocol, testing, security]
sources:
  - plans/reports/260716-2232-codex-intellij-functional-parity.md
  - /tmp/openai-docs-cache/codex-manual.md
  - codex-cli-0.144.5-help
---

# Codex IntelliJ Functional-Parity Test Research

## Summary

- Goal: reproduce documented Codex IDE workflows with native IntelliJ actions, tool-window contents, editor context, diff viewers, settings, and notifications; never copy VS Code UI/assets.
- Evidence: official manual sections for App Server, IDE commands/settings/slash commands, review, environments, memories, goals, MCP, and subagents; local `codex-cli 0.144.5` confirms stable/experimental JSON Schema generation.
- Evidence gap: repo contains no generated schema. Phase zero must generate both bundles and freeze their hashes. No protocol identifier absent from the manual or generated bundle may enter implementation.
- Primary transport: child-process stdio JSONL. Initialize once, then thread → turn → item state. WebSocket/remote transport is not required for v1.
- Release unit: each slice below ends in a runnable IntelliJ sandbox behavior, not a protocol-only layer.

## Parity Matrix

| Slice | Documented workflow → native IntelliJ concept | Independently verifiable acceptance |
|---|---|---|
| VS-00 Capability contract | Generate stable + `--experimental` 0.144.5 schemas; compatibility registry and unknown-message envelope | Hash/version recorded; every planned request/event classified stable, experimental, account/host, platform, or absent; absent calls cannot be sent |
| VS-01 Process + session | app-server lifecycle → project service/status widget; initialize/initialized; thread start/list/resume/archive/fork | Start, reconnect, restart, resume, and archive work; pre-init/re-init errors and process death become actionable UI; no EDT blocking |
| VS-02 Commands + panels | `addToThread`, `addFileToThread`, `newChat`, `newCodexPanel`, `openCommandMenu`, `openSidebar` → editor actions, context menu, tool-window action, content tabs | All six actions searchable/bindable; selection/file context reaches only the chosen task; two panels keep isolated thread/composer state |
| VS-03 Context + composer | selection, current/open files, `@` file references, automatic IDE context → editor/file chooser/context chips | Chips preview source/range and can be removed; unsaved text policy explicit; `/ide-context` affects future turns only; closed/unreadable files fail visibly |
| VS-04 Turn control | streaming turn/items, interrupt, follow-ups → transcript/activity rows, stop action, queued chips | Deltas order correctly; duplicate/late events are idempotent; queue supports edit/reorder/send/delete; steer uses active turn; modifier inverts `queue`/`steer` once |
| VS-05 Approvals | command/file/tool approval requests → modal/banner tied to source task/agent | Approve/deny exactly once; stale/duplicate answers blocked; command, cwd, affected scope and source thread visible; inactive-panel approvals surface globally without silently switching context |
| VS-06 Review + diff | `/review` base/uncommitted; transcript file changes → Git change tree + native diff/navigation | Non-Git hides review; base/uncommitted choices route correctly; file/line opens editor; last-turn scope distinguishes Codex edits from full repo; stage/revert remain explicit IDE Git operations, never implicit |
| VS-07 Settings | shared `config.toml` + IDE-local settings → Settings pages and project service reload | Preserve config precedence; cover CodeLens TODO, open-on-startup, follow-up mode, Enter behavior, inline/detached review, locale, CLI path, chat/code font sizes; invalid values do not corrupt config |
| VS-08 Execution targets | `/local`, `/worktree`, `/cloud`, `/cloud-environment`, `/project` → target selector/new-task dialog | Local binds canonical project cwd. Worktree requires Git and schema-proven host support. Cloud/project/environment require advertised account capability; unsupported targets explain why and never emulate locally |
| VS-09 Slash registry | typed autocomplete/dispatch in composer | Registry contains all 22 documented commands; filtering, keyboard selection, argument dialogs, visibility gates, and non-literal routing tested |
| VS-10 Goal, side, memory | `/goal`, `/side`, `/memories`, `/compact`, `/status` → goal progress row, temporary content tab, task controls/status popup | Goal start/steer/pause/resume/edit/clear only if schema-proven; side chat does not mutate main transcript; memory use/generate choices are separate and host-owned; compact/status show confirmed result/error |
| VS-11 MCP + personalization | `/mcp`, `/model`, `/reasoning`, `/personality`, `/fast` → status/selector popups | Values come from server/config, never hard-coded; MCP shows enabled/connected/OAuth-needed without secrets; unsupported model tier/personality is disabled with reason; restart prompt shown after MCP config change |
| VS-12 Agents/subagents | custom agents + parent/child threads/activity → expandable agent strip/tree and thread content | Show agent type/nickname, parent, Active/Done/error, source approval, open child, stop-all; individual stop/steer only when schema proves it; child isolation and inherited permission display verified |
| VS-13 Remaining commands | `/approve`, `/feedback`, `/init`, `/fork`, `/plan`, `/review` → typed actions/dialogs | `/approve` exists only for eligible auto-review denial; feedback is preview/redact/opt-in; init previews target and overwrite decision; fork returns new task; plan/review modes visibly change routing |

Slash inventory (must equal registry test fixture): `/approve`, `/cloud`, `/cloud-environment`, `/compact`, `/fast`, `/feedback`, `/fork`, `/goal`, `/ide-context`, `/init`, `/local`, `/mcp`, `/memories`, `/model`, `/personality`, `/plan`, `/project`, `/reasoning`, `/review`, `/side`, `/status`, `/worktree`.

Capability policy:

- Stable schema: enabled after handshake/version check.
- Experimental schema: off by default; requires `experimentalApi: true`, explicit setting, warning badge, and fixture coverage.
- Account/host advertised: cloud, cloud environment, project, fast tier, personality, memories, auto-review approval, and OAuth UI stay hidden/disabled until runtime evidence exists.
- Platform gated: WSL setting hidden on Linux; Git-only review/worktree hidden outside Git; actions respect dumb mode and disposed projects.
- Explicit v1 out of scope: remote WebSocket/non-loopback auth, Marketplace/signing, telemetry, broad IDE/OS support, desktop-only local-environment actions/handoff/worktree management unless app-server schema exposes them, GitHub PR sidebar enrichment, pixel parity, proprietary branding/private APIs.

## Validation Strategy

| Scenario | Unit | Protocol fixture | IntelliJ platform test | IDEA 2026.1.4 sandbox smoke |
|---|---|---|---|---|
| Schema/capability drift | classify stable/experimental/unknown | validate request + notification corpus against both bundles | settings/command visibility updates after negotiated capabilities | start with 0.144.5; start with deliberately incompatible fixture/version |
| Thread/turn/stream | reducer ordering, idempotence, request correlation | initialize; start/resume/fork; deltas; complete/interrupt; malformed/unknown event | two content tabs, restart, archive/resume, EDT assertions | send, steer, queue, interrupt, reopen project |
| IDE context | range/path encoding, redaction, size policy | captured turn input snapshot | selection, whole file, unsaved editor, deleted/renamed file, multi-root ownership | add selection/file; toggle automatic context; inspect removable chips |
| Approval + tool activity | one-shot state machine, stale decision rejection | command/file/MCP approval from main and child threads | global notification, correct task focus, deny path, disposed project | approve safe command; deny write; interrupt while prompt open |
| Review/diff | scope model and last-turn attribution | file-change/patch/review item corpus | non-Git/Git, base picker, untracked/binary/rename/conflict, file/line navigation | run `/review`; inspect changed files; verify no mutation by review |
| Slash/settings/targets | 22-command registry snapshot and typed routes | success/error/capability responses for every server-backed route | completion, keyboard, configurable persistence, detached review, disabled reasons | execute every supported command; confirm each gated command message |
| Goals/memory/MCP/agents | task isolation and permission inheritance | schema-proven lifecycle/status/activity plus unknown future fields | goal row, memory choices, MCP status, agent tree/open child/stop-all | run delegated task; answer child approval; verify memory/MCP data redaction |
| Failure/security | bounded buffers, truncation, path checks, secret scrub | crash/restart, partial JSONL, oversized output, duplicate IDs, overload/error | cancellation/disposal, no UI freeze, logs redacted | kill server mid-turn; reopen; inspect diagnostics export before sharing |

Acceptance gates:

1. **G0 Evidence:** generated bundles committed to the plan's declared schema location; hashes/version recorded; every matrix row linked to exact schema types or marked client-only/gated.
2. **G1 Protocol:** stable fixture corpus passes; unknown events preserve raw diagnostic metadata but cannot crash; all I/O off EDT; restart/resume deterministic.
3. **G2 Core workflow:** VS-01–07 pass unit + fixture + platform tests and one sandbox prompt → stream → approval → patch → diff flow.
4. **G3 Advanced workflow:** every slash command represented; VS-08–13 either passes all applicable layers or shows a tested capability-disabled state.
5. **G4 Security/privacy:** no token/auth duplication, no secret-bearing logs/snapshots, approvals fail closed, memory/MCP/feedback controls are explicit, path/context previews match transmitted data.
6. **G5 Parity release:** command and slash inventories are 100%; no unknown event/EDT violation in smoke; unsupported list published; fresh sandbox on Linux + IDEA Community 2026.1.4 passes.

Security/privacy boundaries:

- app-server remains source of truth for authentication, configuration, conversation state, sandbox, approvals, memories, MCP, and agents. Plugin stores only IntelliJ-local UI preferences and thread references.
- Default to local stdio. Never expose a listener, print bearer/OAuth tokens, duplicate `auth.json`, or place secrets in command lines. Identify the client honestly; do not use `codex_vscode`.
- Approval UI never broadens sandbox/permission mode. Display request origin and fail closed on timeout, disconnect, unknown category, or disposed project.
- Canonicalize paths and bind context/actions to the owning IntelliJ project. Preview transmitted selection/files; cap/truncate deliberately; exclude unrelated open projects.
- Treat model text, diffs, command output, MCP content, memory content, and external context as untrusted display data. Escape rendering; require user action for links/files/processes.
- Analytics stays disabled unless separately opted in. Feedback/log export must show included data, redact credentials/prompts by policy, and never send automatically.

## Risks

- Manual describes IDE slash workflows but not every app-server method; schema generation may prove some first-party-only. Mitigation: gated UI, no guessed RPCs, kill criterion for required inaccessible workflow.
- Worktree docs describe desktop-only management while IDE documents `/worktree`. Mitigation: treat command presence as intent only; enable execution after schema/runtime proof, otherwise explicit unavailable state.
- Multi-panel and subagent concurrency can misroute events/approvals. Mitigation: key all state by project + thread + turn + item/agent and test inactive-panel prompts.
- Config/schema drift across CLI upgrades can silently change behavior. Mitigation: versioned adapter, golden fixtures for stable + experimental bundles, startup compatibility diagnostics.
- Diff/context may expose unrelated user edits or secrets. Mitigation: label repo-wide versus last-turn state, preview context, redact diagnostics, never imply authorship from Git state alone.

## Unresolved Questions

- Which stable/experimental 0.144.5 schema methods implement goal pause/resume/edit/clear, queue persistence, cloud/worktree target selection, memory controls, MCP status/auth, and individual subagent control?
- Is `/feedback` expected to submit through a public app-server method, or should v1 provide a local diagnostics-preview/link flow only?
- Must stage/unstage/revert and inline diff comments match the wider review-pane workflow, or is IDE parity limited to `/review`, diff inspection, and file/line navigation?
- Where should generated schema bundles and sanitized protocol goldens live in the eventual plan, given the currently empty repository?
