---
title: "Phase 1: Baseline and regression harness"
status: done
---

# Phase 1: Baseline and regression harness

## Overview

Create deterministic correctness and operation-count coverage before changing the renderer or Swing surfaces. Record the sandbox Gradle limitation and use a writable Gradle home when feasible.

## Requirements

- [ ] Characterize transcript output and block fingerprints for mixed, multi-turn activity histories.
- [ ] Add a deterministic projection-work metric or benchmark that detects repeated full-list scans without fragile absolute timing alone.
- [ ] Add component/helper tests for narrow viewport sizing, semantic colors, focusability, and keyboard activation where platform harness support permits.

## Implementation Steps

1. Add test fixtures for 100/1,000+ ordered items across multiple turns and verify current renderer behavior.
2. Capture pre-change render duration after warm-up when Gradle can run; use relative before/after values only.
3. Add focused tests for the UI defects before production edits.

## Todo

- [ ] RED test: indexed projection performs bounded classification/grouping work.
- [ ] RED test: embedded ownership does not subscribe twice.
- [ ] RED test: width calculation respects narrow viewport.
- [ ] RED test: custom controls expose semantic theme/focus/keyboard behavior.

## Success Criteria

The test suite demonstrates the current defects or records why a Swing integration assertion needs manual `runIde` verification; no production behavior has changed.

## Related Files

- `src/test/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRendererTest.kt`
- New focused UI/helper tests under `src/test/kotlin/dev/haibachvan/codexintellij/ui/`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRenderer.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt`

## Risks

- Wall-clock tests are noisy under JIT and CI. Gate algorithmic work with deterministic counters/shape plus reported timings, not a hard millisecond threshold.
- Plain unit tests do not constitute interactive IntelliJ UI coverage.
