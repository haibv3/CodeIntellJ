---
date: 2026-07-17
session: codex-intellij-functional-parity-planning
---

# Journal: 2026-07-17 — Codex IntelliJ Parity Planning

## Context

Planned an internal IntelliJ IDEA client providing documented Codex IDE functional parity over `codex app-server` 0.144.5. Scope remained frozen under HOLD SCOPE; no implementation started.

## What Happened

- Produced 8 executable phases with 104 tasks covering native chat, IDE context, approvals, review/diff, settings, MCP, slash commands, agents, resilience, and parity verification.
- Red-team review consolidated 26 raw findings into 15 accepted changes and 0 rejected findings.
- User validated the final scope decisions on 2026-07-17.
- `ak plan validate` passed; plan status remains pending at 0/8 phases and 0/104 tasks.

## Reflection

The plan now favors reproducible public contracts and explicit disabled states over guessed behavior. This reduces false parity claims and makes implementation gates testable, while accepting repository size and deferred advanced targets as deliberate trade-offs.

## Decisions Made

| Decision | Rationale | Impact |
|---|---|---|
| Use app-server 0.144.5 as authority | Match the supported rich-client protocol | Stable baseline for transport, state, fixtures, and tests |
| Keep cloud, cloud-environment, and worktree visible-disabled | No accepted public contract | Features remain discoverable without unsafe private-API emulation |
| Gate side on an accepted semantic trace | Semantics are not yet proven | No raw-prompt fallback or false command parity |
| Restrict project/context targets to canonical IntelliJ content roots, without external override | Preserve HOLD SCOPE and filesystem trust boundary | No arbitrary external-directory access |
| Commit and hash-verify full stable and experimental schema trees | Reproducible protocol review and CI | Larger repository, deterministic schema validation |
| Accept all 15 consolidated red-team findings | Close lifecycle, security, ownership, and test gaps | Plan is internally consistent and ready for phased execution |

## Next Steps

- Start Phase 1 only after explicit implementation handoff.
- Preserve visible-disabled and content-root restrictions unless a future reviewed plan changes scope.

## Unresolved Questions

- None within the validated HOLD SCOPE.
