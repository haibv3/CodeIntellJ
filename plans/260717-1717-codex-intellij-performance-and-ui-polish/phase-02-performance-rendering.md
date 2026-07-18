---
title: "Phase 2: Performance rendering"
status: done
---

# Phase 2: Performance rendering

## Overview

Reduce repeated transcript work while preserving the pure renderer contract and EDT-only Swing mutation.

## Requirements

- [ ] Build one render-local projection index for sorted thread items, turn groups, interim-agent flags, activity sections, patches, and subagents.
- [ ] Embedded chat has one state-delivery owner; standalone chat keeps its direct subscription and busy-state synchronization.
- [ ] Projection scheduling is single-flight with only the newest pending state retained.
- [ ] Keep 40 ms streaming coalescing, stale-result rejection, fingerprint reuse, native block order, and scroll behavior.

## Implementation Steps

1. Extract a small internal projection/index builder in `TranscriptRenderer` and route `renderBlocks` through it.
2. Keep public helper semantics stable and compare rendered blocks against characterization fixtures.
3. Make direct store subscription conditional on `embedded`; parent delivery remains authoritative for embedded chat.
4. Replace overlapping pooled submissions with a single-flight latest-state loop.
5. Re-run the large-history harness and record before/after work count and timing.

## Todo

- [ ] Projection classification/grouping is O(n) after the initial O(n log n) sort.
- [ ] One embedded store update requests one transcript render.
- [ ] At most one background projection runs concurrently.
- [ ] All existing transcript/UI tests remain green.

## Success Criteria

Large activity-heavy histories show at least a material improvement (target >=50% at the largest fixture) with unchanged rendered output and no added Swing work off EDT.

## Related Files

- `src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRenderer.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexWorkspacePanel.kt` only if parent delivery needs a small ownership adjustment
- Corresponding UI tests

## Risks And Rollback

- Interim-agent grouping is behavior-sensitive. Characterization tests must cover active, completed, tool-after-agent, and no-turn-id cases.
- If single-flight scheduling complicates disposal or terminal flush semantics, retain generation rejection and limit this phase to duplicate-subscription/index fixes.
