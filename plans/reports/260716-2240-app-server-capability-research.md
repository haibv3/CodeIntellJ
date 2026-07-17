---
title: Codex app-server 0.144.5 capability research
date: 2026-07-16
status: complete
scope: IntelliJ functional parity planning
cliVersion: 0.144.5
---

# Codex app-server 0.144.5 Capability Research

## Summary

App-server is the correct integration seam for a rich IntelliJ client: OpenAI says it powers rich clients such as the VS Code extension and exposes auth, history, approvals, and streamed events. The command itself may change without notice; stdio JSONL is the default transport, while WebSocket is experimental/unsupported. [Official app-server manual](https://learn.chatgpt.com/docs/app-server.md)

Parity classification used below: **S** = present without `experimentalApi` in the 0.144.5 stable schema; **X** = requires `capabilities.experimentalApi=true`; **C** = IntelliJ-owned client behavior composed over S; **G** = account/service/first-party gated or no sufficient app-server contract. Evidence snapshots: `codex_app_server_protocol.v2.schemas.json`, `ClientRequest.json`, `ServerRequest.json`, and `ServerNotification.json` under `/tmp/codex-intellij-schema-{stable,experimental}-01445/`. Treat S as version-pinned, not permanent: schema output is CLI-version-specific and the app-server itself is experimental. [Protocol and schema generation](https://learn.chatgpt.com/docs/app-server.md#message-schema)

Recommended product boundary: ship full S+C local parity first; expose X only behind an explicit “Experimental app-server APIs” setting and runtime probes; render G honestly as unavailable with reason. Do not infer first-party cloud APIs or copy VS Code behavior not represented by public docs/schema.

## Findings

### Protocol and state model

- Connection: send `initialize`, then `initialized`; pre-handshake calls fail `Not initialized`, repeated initialization fails `Already initialized`. Requests/responses correlate by `id`; notifications have no `id`. Use stdio only for the first plugin release. [Official lifecycle](https://learn.chatgpt.com/docs/app-server.md#initialization); stable `v1/InitializeParams.json`, `ClientNotification.json`.
- Thread → turn → item is the authoritative hierarchy. Stable `Thread` includes status, session id, source, cwd, turns, `parentThreadId`, `agentRole`, and `agentNickname`; `Turn` has status/error and item view; `ThreadItem` is a tagged union. Stable `thread/read` can include turns, while independent `thread/turns/list` and `thread/items/list` are X. Stable aggregate definitions `Thread`, `Turn`, `ThreadItem`; experimental request schemas.
- S lifecycle: start/resume/fork/list/read/archive/unarchive/delete/unsubscribe/rollback/compact, turn start/steer/interrupt, and status/token/turn/item notifications. `turn/steer` requires `expectedTurnId`; review/compact turns can reject steering as `activeTurnNotSteerable`. Stable `ClientRequest.json`, `v2/TurnSteerParams.json`, `TurnError`.
- Items cover user/agent/reasoning/plan, command execution, file change, MCP/dynamic tools, collaboration, subagent activity, web/image/sleep, review-mode boundaries, and compaction. Reduce `item/started`, deltas, patch updates, then `item/completed`; completed item is authoritative. Stable `ThreadItem`, `ItemStartedNotification.json`, `ItemCompletedNotification.json`; plan item itself is marked experimental.

### Stable versus experimental

| Capability | Class | Evidence / boundary |
|---|---:|---|
| Core threads, turns, streaming, history, goals, compact, fork | S | Stable `ClientRequest.json`; `Thread*`, `Turn*`, item notifications |
| Command/file/permission approvals and user input | S/X | Modern approval requests are S; `item/tool/requestUserInput` payload is explicitly EXPERIMENTAL despite appearing in stable `ServerRequest.json`; retain legacy `applyPatchApproval`/`execCommandApproval` decoder only for compatibility |
| Diff and code review | S | `turn/diff/updated` carries latest aggregate diff; `item/fileChange/patchUpdated` carries per-item changes; `review/start` supports uncommitted/base/commit/custom and inline/detached. `fileChange/outputDelta` says server no longer emits it |
| MCP | S | OAuth login, reload, paged status, resource read, direct tool call/progress, startup updates, and bidirectional typed elicitation are stable methods/requests |
| Account, models, permissions, config | S | Login/cancel/logout/read, rate/usage updates, model/profile lists, layered config read, optimistic writes (`expectedVersion`), managed requirements and config warnings |
| Agent/subagent observation | S | `collabAgentToolCall` exposes spawn/send/resume/wait/close, sender/receivers/status; `subAgentActivity` and thread parent/role/nickname expose tree/activity. These are observed model tool items, not client RPCs |
| Custom-agent discovery/control | C/S | Official agents live in `~/.codex/agents/` or `.codex/agents/`; schema exposes resulting roles/threads but no custom-agent list RPC. Scan TOML read-only; use normal child thread read/steer/interrupt where valid. [Official subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents.md) |
| Extra history/search/settings/memory, collaboration presets | X | `thread/{search,turns/list,items/list,settings/update,memoryMode/set}`, `memory/reset`, `collaborationMode/list`; X adds `collaborationMode` to turn start |
| Realtime, environments, remote control, process PTY, background terminals | X | Present only in experimental `ClientRequest.json`; exclude from local parity MVP |

The complete X-only client-method delta is grouped as: thread elicitation counters, settings/memory mode, background terminals, search/turn/item paging, and realtime; `memory/reset`; remote control; collaboration preset list; environment add/info; process PTY; fuzzy-search sessions; and the mock method. X also adds server request `currentTime/read`. Stable and X notification method lists are identical in these snapshots, so the stable decoder must still tolerate notifications whose initiating client method is unavailable. Evidence: diff of the two `ClientRequest.json`, `ServerRequest.json`, and `ServerNotification.json` snapshots.

### Typed IDE slash-command routing

The documented IDE list contains 22 commands. Registry acceptance test must assert exactly these names and one typed route each. [Official IDE commands](https://learn.chatgpt.com/docs/developer-commands.md?surface=ide)

| Commands | Class | Typed route |
|---|---:|---|
| `/approve` | S/G | `thread/approveGuardianDeniedAction`; enable only when automatic-review denial event exists |
| `/compact` | S | `thread/compact/start`; block steer/queue until its turn completes |
| `/feedback` | S | client dialog → `feedback/upload`; explicit consent before logs |
| `/fork` | S | `thread/fork` using thread id and optional last turn |
| `/goal` | S | `thread/goal/{get,set,clear}` plus goal notifications |
| `/mcp` | S | status UI from `mcpServerStatus/list`; OAuth/reload actions as available |
| `/model`, `/personality`, `/reasoning`, `/fast` | S/G | picker from `model/list`, then next `turn/start` overrides `model`, `personality`, `effort`, `serviceTier`; hide unsupported catalog values |
| `/review` | S | target/delivery dialog → `review/start`; use returned `reviewThreadId` |
| `/status` | S/G | thread/token status + `account/{read,rateLimits/read,usage/read}`; tolerate unavailable account fields |
| `/side` | C+S | infer temporary side task as `thread/fork(ephemeral=true)` in a new panel; validate semantics before claiming exact parity |
| `/ide-context` | C | local toggle; attach selection/file/project context as typed turn input, never as slash text |
| `/init` | C+S | client action that starts a normal typed turn with the documented scaffold intent; no dedicated RPC |
| `/local` | C | select local app-server execution mode; no RPC |
| `/project` | C | IntelliJ project/cwd picker; apply to new thread/turn `cwd` |
| `/worktree` | C | IntelliJ/Git worktree workflow, then new thread with worktree `cwd`; app-server has no worktree method |
| `/memories` | X/G | `thread/memoryMode/set` (and optional `memory/reset`); capability/account gate |
| `/plan` | X | `collaborationMode/list`, then `turn/start.collaborationMode` or `thread/settings/update`; do not emulate with prompt text |
| `/cloud`, `/cloud-environment` | G | no sufficient cloud-task contract in either schema; experimental `environment/*` is not evidence of cloud execution. Show unavailable, not a fake local mapping |

## Recommendations

1. Architecture: `ProcessManager` (binary/version/stderr), `JsonRpcTransport` (ids/pending/server requests), generated-wire DTO module, handwritten `ProtocolAdapter` (stable domain + X extensions + unknown envelope), event reducer/store keyed by thread/turn/item, capability registry, command registry, then IntelliJ UI/diff/context adapters. Never let generated DTOs leak into UI state.
2. Compatibility contract: minimum exact 0.144.5 fixture; initialize without X by default; derive capability from schema/version + method smoke probe + account response; unknown methods/items/enum values become diagnosable `Unknown`, never fatal. Keep secrets/auth in Codex state; never persist API keys or access tokens in plugin settings.
3. Phase order: (1) stdio handshake/process + schema fixtures; (2) S thread/turn/item reducer and resume; (3) approvals, command/file streaming, native diff/review; (4) account/config/model/MCP; (5) all C slash routes and IDE context/panels; (6) S agent tree plus read/steer/interrupt; (7) opt-in X commands; (8) restart/load, backpressure, EDT, privacy, and full parity audit.
4. Fixtures: golden JSONL for handshake/errors, start→delta→complete, interrupt/steer mismatch, resume partial/full items, interleaved parent/child items, every approval decision, MCP OAuth/elicitation/failure, config version conflict, detached review and evolving diff, EOF/restart, malformed/unknown event. Snapshot both supplied schema directories and assert X-only methods reject without opt-in.
5. Registry tests: all 22 commands represented; route class/method/required dialog asserted; G commands disabled with reason; X commands unavailable until opt-in; no slash command forwarded as raw prompt. Add reducer permutation/duplicate-event tests and IntelliJ EDT tests.

## Risks

- Protocol drift: CLI upgrade can add fields/enums/methods or change X contracts; pin 0.144.5, show detected version, and fail only unsupported operations.
- Ordering/concurrency: deltas, approvals, child threads, interrupt completion, and restart can interleave. Correlate by request id plus thread/turn/item/approval id; make reducer idempotent; never assume active panel owns an approval.
- Approval safety: stale callback, double response, `decline` versus `cancel`, session-wide approvals, exec/network amendments, and permission scopes have different effects. Present exact decision text and close prompt on `serverRequest/resolved`.
- Partial services: OAuth may cancel/timeout; MCP servers may fail independently; managed config may override writes; rate/account fields may be sparse. Degrade per capability, preserve chat.
- UI/process: stdout corruption, unexpected EOF, large command output/diff, and WebSocket `-32001` overload can freeze or lose state. Keep parsing off EDT, bound buffers, coalesce deltas, restart/resume explicitly; avoid WebSocket in v1.
- Custom-agent mismatch: TOML format may evolve and UI cannot directly invoke collab tools. Treat discovery as advisory and activity items as source of truth.

## Unresolved Questions

- Is X `/plan` and `/memories` required for first release, or acceptable behind a later experimental milestone?
- Should `/side` ship only after an empirical 0.144.5 trace confirms `thread/fork(ephemeral=true)` matches IDE semantics?
- Product decision needed for G `/cloud` commands: visible-disabled parity or omit until OpenAI exposes a public contract?
