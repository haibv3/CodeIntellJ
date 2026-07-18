---
title: "Hiệu năng multi-agent và tính nhất quán UI cho Codex IntelliJ"
description: "Refactor theo TDD để chặn EDT backlog, render transcript tăng dần, giữ scroll ổn định và chuẩn hóa UI theo IntelliJ-native."
status: completed
priority: P1
effort: "large"
branch: main
tags: [refactor, frontend, performance, swing, tdd]
blockedBy: []
blocks: []
created: 2026-07-18
---

# Hiệu năng multi-agent và tính nhất quán UI cho Codex IntelliJ

## Overview

Triển khai phương án B đã duyệt trong [báo cáo brainstorm](../reports/260718-1116-multi-agent-performance-ui-brainstorm.md): giữ `ServerStateStore` và `ConversationReducer` lossless; hợp nhất state delivery dành cho UI; thêm stable transcript block identity, keyed reconciliation, bounded component window, scroll anchoring; cuối cùng chuẩn hóa design tokens và Swing primitives.

## Scope Guard

- Không thay app-server protocol, schema, reducer semantics hoặc conversation behavior.
- Không drop/reorder event trước state owner.
- Không full virtualization rewrite, external UI dependency, custom font hoặc hardcoded brand palette.
- Không coi wall-clock unit test là bằng chứng FPS/EDT freeze; runtime gate dùng `runIde`, internal mode và JFR/async-profiler.

## Cross-Plan Dependencies

Hai plan trước đều `completed`; không có plan đang mở hoặc dependency blocking:

- `260716-2242-codex-intellij-functional-parity`
- `260717-1717-codex-intellij-performance-and-ui-polish`

## Goals

| # | Goal | Priority |
|---|---|---|
| 1 | Một burst 5.000 state update không tạo EDT queue tăng theo event | P1 |
| 2 | Streaming delta chỉ update block thay đổi, không rebuild transcript tail | P1 |
| 3 | Transcript 2.000 block giữ component cap và scroll anchor | P1 |
| 4 | Button, chip, card, popup, radius và icon theo một IntelliJ-native contract | P1 |
| 5 | Có deterministic CI tests và runtime evidence tái lập được | P1 |

## Phases

| # | Phase | Status | Depends on |
|---|---|---|---|
| 1 | [Baseline và regression harness](./phase-01-start.md) | Completed | - |
| 2 | [UI state delivery bridge](./phase-02-ui-state-delivery-bridge.md) | Completed | Phase 1 |
| 3 | [Incremental transcript reconciliation](./phase-03-incremental-transcript-reconciliation.md) | Completed | Phase 2 |
| 4 | [Bounded rendering và scroll](./phase-04-bounded-rendering-and-scroll.md) | Completed (runtime verified) | Phase 3 |
| 5 | [IntelliJ-native UI primitives và verification](./phase-05-intellij-native-ui-primitives-and-verification.md) | Completed | Phase 4 |

## Global TDD Rules

1. Mỗi phase viết RED/characterization test trước production edit.
2. Không nới assertion hoặc đổi fixture để làm test xanh.
3. Mỗi phase chạy focused tests rồi `./gradlew test` trước khi chuyển phase.
4. Nếu runtime profile bác bỏ bottleneck giả định, dừng và cập nhật plan trước khi mở rộng refactor.
5. Rollback theo phase; không trộn UI primitive migration vào performance phases.

## Success Criteria

- [x] Workload chuẩn: 8 agent, 5.000 event, transcript 2.000 block, chạy 10 phút.
- [x] Không EDT gap trên 500 ms trong runtime stress run.
- [x] Scroll action latency p95 dưới 100 ms.
- [x] Streaming result visibility dưới 250 ms.
- [x] Scroll tối thiểu 45 FPS khi đọc transcript chuẩn.
- [x] Tối đa một pending UI delivery/workspace và một transcript projection/thread view.
- [x] Normal append/delta không rebuild transcript tail.
- [x] Materialized transcript component có hard cap 250; target được profile-tune xuống 40 theo Phase 4.
- [x] Retained heap ổn định sau task completion; editor/HTML host offscreen được dispose.
- [x] Automated keyboard và accessibility contract tests pass.
- [x] Light, Darcula, HiDPI, keyboard-only và UI Inspector manual matrix pass.
- [x] Unit, UI smoke, protocol, structure, build và plugin verifier gates pass.

## Handoff Boundary

Plan chỉ cho phép triển khai sau validation hoặc explicit user approval. Full virtualization chỉ được mở thành plan mới nếu Phase 4 không đạt metric sau khi có profile evidence.

<!-- slug: codex-intellij-multi-agent-performance-and-ui-consistency -->
