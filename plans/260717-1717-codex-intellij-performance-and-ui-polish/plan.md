---
title: "Codex IntelliJ performance and UI polish"
description: "Measured transcript rendering optimization and IntelliJ-native UI polish without changing protocol or conversation behavior."
status: completed
priority: P1
effort: "medium"
tags: [performance, ui, swing, intellij]
created: 2026-07-17
---

# Codex IntelliJ performance and UI polish

## Overview

Optimize the chat hot path and correct the highest-impact visual, responsive, and accessibility defects found in a full static audit. Changes remain behind the existing `ServerStateStore -> TranscriptRenderer -> CodexChatPanel` seam.

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Make transcript projection near-linear for long activity-heavy histories | P1 |
| 2 | Prevent redundant render scheduling and stale overlapping work | P1 |
| 3 | Make the primary chat/composer surfaces responsive, theme-safe, and keyboard accessible | P1 |
| 4 | Preserve protocol, normalized state, block order, scroll, and chat behavior | P1 |

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Phase 1: Baseline and regression harness](./phase-01-start.md) | Done |
| 2 | [Phase 2: Performance rendering](./phase-02-performance-rendering.md) | Done |
| 3 | [Phase 3: Native UI polish and verification](./phase-03-native-ui-polish-and-verification.md) | Done |

## Success Criteria

- [x] Large activity-heavy transcript projection is measurably faster and scales approximately linearly.
- [x] An embedded chat schedules one render path per store update and never runs more than one projection at a time.
- [x] Transcript content fits a 320 px viewport without horizontal clipping.
- [x] Attachment chips, menus, and action controls use Light/Dark-aware semantic colors.
- [x] Primary pointer actions have keyboard activation and visible focus.
- [x] Focused tests, full unit tests, protocol checks, and plugin build pass, or environment limitations are reported with exact evidence.

## Scope Boundaries

- No protocol schema, app-server sequencing, normalized-state, reducer, or public action/command contract changes.
- No external UI dependency, font, image asset, or framework replacement.
- Reducer delta batching and lazy offscreen code editors remain follow-up candidates pending runtime profiling.

<!-- slug: codex-intellij-performance-and-ui-polish -->
