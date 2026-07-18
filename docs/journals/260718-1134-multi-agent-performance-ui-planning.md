---
title: "Multi-agent performance and UI planning"
created: 2026-07-18
tags: [planning, performance, swing, ui]
---

# Multi-agent performance and UI planning

## Context

Người dùng báo IDE lag khi nhiều agent chạy, transcript/result scroll không mượt và visual controls thiếu nhất quán. Brainstorm chọn phương án B: UI delivery bridge, incremental transcript, bounded rendering và IntelliJ-native primitives.

## What Happened

- Xác nhận bottleneck chưa được test hiện tại bao phủ: per-event EDT delivery, layout/paint, component growth và scroll.
- Tạo TDD plan 5 phase với workload chuẩn 8 agent, 5.000 event, 2.000 block, 10 phút.
- Phát hiện fake app-server wrapper đang resolve sai `src/src/...`; đưa fix và process-level test vào Phase 1.
- Phân biệt deterministic CI gates với runtime JFR/internal-mode evidence.

## Decisions

- Giữ `ServerStateStore`, reducer và protocol semantics lossless.
- Không full virtualization rewrite trong plan này.
- Tối ưu performance trước khi migrate UI primitives.
- Chỉ tạo UI primitive khi có ít nhất hai call site thực tế.

## Next

- Hoàn tất manual visual matrix trên Light, Darcula và HiDPI trước khi đóng Phase 5.

## Implementation Follow-up

- Workload `runIde` 10 phút đạt 180,7 ms result visibility, 101,4 ms maximum EDT gap, 1,60 ms scroll-action p95 và 62,1 FPS.
- JFR xác nhận HTML parser/document allocation và component-tree layout là hot path; plain one-line agent messages chuyển sang recyclable native painted row.
- Materialized target được tune từ 200 xuống 40 theo profile, hard cap vẫn 250; retained heap sau full GC tăng khoảng 6,6 MB.
- Full test/UI/protocol/structure/build gate và plugin verifier trên `261.*`/`262.*` đều pass.
