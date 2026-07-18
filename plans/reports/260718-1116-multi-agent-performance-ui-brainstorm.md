---
title: "Multi-agent performance and UI consistency brainstorm"
description: "Agreed architecture for responsive multi-agent streaming, smooth transcript rendering, and IntelliJ-native UI consistency."
status: approved
decision: "Option B - UI delivery bridge and incremental transcript"
created: 2026-07-18
tags: [brainstorm, performance, swing, transcript, design-system]
---

# Multi-agent performance and UI consistency brainstorm

## Summary

Chọn kiến trúc theo tầng thay vì tiếp tục tối ưu riêng `TranscriptRenderer` hoặc rewrite toàn bộ transcript. Giữ `ServerStateStore` và reducer lossless; thêm một UI delivery bridge hợp nhất snapshot, projection có stable block identity, keyed reconciliation, bounded component window, scroll anchoring, và bộ IntelliJ-native UI primitives.

Mục tiêu: với 8 agent, 5.000 event và transcript 2.000 block, UI không có EDT task trên 500 ms, action latency p95 dưới 100 ms, result mới xuất hiện trong 250 ms, và scroll đạt tối thiểu 45 FPS.

## Problem Statement

Pipeline hiện tại khuếch đại một protocol event thành nhiều công việc:

```text
Agent delta
-> reduce immutable state
-> notify listeners
-> enqueue EDT runnable
-> refresh task list, agent tree, title, busy state
-> project transcript
-> mutate Swing component tree
-> measure HTML, revalidate, repaint
```

Projection đã gần tuyến tính và transcript render đã có coalescing 40 ms cùng single-flight background work. Tuy nhiên mỗi store update vẫn có thể enqueue một workspace update riêng. Transcript vẫn giữ component tree tăng theo lịch sử, đo variable-height HTML và dispose/rebuild phần đuôi từ block khác biệt đầu tiên.

UI consistency hiện dừng ở một phần semantic color token. Button, chip, card, popup row, radius, spacing, icon và focus behavior vẫn được tạo phân tán.

## Requirements

| Requirement | Accepted value |
|---|---|
| Output | Báo cáo kiến trúc và roadmap brainstorm, chưa sửa code |
| Load profile | 8 agent, 5.000 event, transcript 2.000 block, chạy 10 phút |
| UI latency | p95 dưới 100 ms |
| Result visibility | Dưới 250 ms |
| Freeze budget | Không có EDT task trên 500 ms |
| Scroll | Tối thiểu 45 FPS |
| Visual direction | IntelliJ-native, dense, neutral, content-first |
| Compatibility | Giữ protocol contract và reducer semantics |

## Scope

### In scope

- State-to-UI delivery, scheduling và coalescing.
- Thread-specific view projection và block revisions.
- Transcript reconciliation, component lifecycle, HTML/height cache.
- Scroll anchoring, live-follow và manual-reading modes.
- Semantic tokens, reusable Swing primitives, icon policy và accessibility.
- Runtime performance instrumentation và stress verification.

### Out of scope

- Thay đổi app-server protocol hoặc generated schema.
- Drop hoặc reorder protocol events trong reducer pipeline.
- Thay framework UI hoặc thêm external UI dependency.
- Bundle font, hardcode palette riêng, hoặc tạo giao diện xa lạ với IntelliJ.
- Full transcript virtualization rewrite trong vòng đầu.
- Thiết kế lại task browser, conversation model hoặc command behavior.

## Evidence From Current Code

- `ServerStateStore.dispatch()` reduce và notify listeners đồng bộ cho từng event.
- `CodexWorkspacePanel.stateListener` tạo `SwingUtilities.invokeLater` rồi refresh nhiều projection trên mỗi snapshot.
- `CodexChatPanel` đã coalesce streaming khoảng 40 ms và chỉ chạy một background projection tại một thời điểm.
- `applyTranscriptBlocks()` reuse prefix, update HTML block đầu tiên khác biệt, sau đó dispose/rebuild phần còn lại.
- `HtmlBlockHost` đo `JBHtmlPane` root view theo width và gọi revalidate khi width thay đổi.
- `MultiAgentRenderScaleTest` đo projection complexity, không đo EDT queue, layout/paint time, scroll FPS, component count hoặc allocation.
- `CodexUiTheme` có semantic colors nhưng chưa là source of truth cho spacing, radius, sizing, icon và component behavior.

## Evaluated Approaches

| Approach | Advantages | Disadvantages | Decision |
|---|---|---|---|
| A. Tactical coalescing patch | Nhanh, ít file, rollback dễ | Không giải quyết component growth và layout ceiling | Không chọn; chỉ dùng làm emergency hotfix |
| B. UI bridge + incremental transcript | Xử lý EDT backlog, render churn, scroll và consistency theo từng phase | Cần seam mới, cache lifecycle và scroll tests | Chọn |
| C. Full virtualized transcript rewrite | Trần performance cao nhất | Rủi ro lớn với variable-height interactive blocks, selection và accessibility | Hoãn; chỉ mở lại nếu B không đạt benchmark |

## Agreed Architecture

```text
App-server events
      |
      v
ServerStateStore + ConversationReducer
      | lossless immutable snapshots
      v
UiStateBridge
      |-- critical lane: approval, error, terminal status
      |-- transcript lane: latest state, max 20 Hz
      `-- chrome lane: task, agent, title, max 5-10 Hz
      |
      v
ThreadViewProjection
      | stable block ID + revision + kind + estimated height
      v
KeyedTranscriptReconciler
      | update only inserted, removed, moved, or revised blocks
      v
Bounded Swing component window
```

### UI delivery bridge

`UiStateBridge` giữ snapshot mới nhất, change categories, generation và một scheduled flag. Mỗi workspace có tối đa một pending EDT delivery. Snapshot mới cập nhật dữ liệu đang chờ thay vì enqueue runnable mới.

Coalescing chỉ áp dụng cho view notification. Reducer vẫn nhận đủ event. Critical state không bị trì hoãn có chủ đích; transcript và workspace chrome có budget riêng.

Suggested delivery budgets:

| Lane | Budget | Policy |
|---|---:|---|
| Approval, error, terminal status | Immediate | Latest authoritative state, prompt update |
| Active transcript | 20 Hz | Latest snapshot wins |
| Agent strip/status | 5-10 Hz | Latest projection wins |
| Task list/title | On relevant fingerprint change | Skip unrelated deltas |
| Offscreen thread | 1-2 Hz or state only | No Swing render |

### Incremental transcript model

Mỗi rendered block cần:

```text
id              stable semantic identity
revision        changes only when visible content or interaction state changes
kind            html, code, agent, activity, files
estimatedHeight cached per width bucket
```

Reconciliation rules:

- Cùng ID và revision: giữ nguyên component.
- Cùng ID, revision mới: update component tại chỗ.
- Append thông thường: chỉ tạo block mới.
- Insert, remove hoặc move: sửa vùng thực sự bị ảnh hưởng.
- Không dùng full HTML fingerprint làm component identity.
- Không dispose toàn bộ tail khi một streaming block thay đổi.

### Bounded rendering

- Nhóm transcript theo turn/chunk trước khi cân nhắc cell-level virtualization.
- Chỉ materialize visible range cùng overscan, với target 150-250 live components.
- Dùng placeholder height cho chunk ngoài cửa sổ.
- Cache sanitized HTML, markdown result và measured height theo block revision cùng width bucket.
- Dispose editor, listener và HTML pane ngay khi block rời cache policy.
- Full virtualization chỉ là fallback nếu bounded chunk rendering không đạt metric.

### Scroll contract

| Mode | Behavior |
|---|---|
| Follow live | Apply theo render budget và giữ bottom anchor |
| Reading history | Giữ anchor block ID cùng pixel offset |
| User dragging | Hoãn non-critical component mutation, giữ latest model state |

Khi người dùng không ở đáy, hiển thị chỉ báo cập nhật mới thay vì tự kéo scroll. Một UI batch chỉ được revalidate/repaint một lần. Không xếp thêm một `invokeLater` chỉ để scroll bottom nếu có thể hoàn tất trong cùng layout cycle.

## Design System Direction

Không dùng palette hoặc font riêng từ design-system generator vì không phù hợp IntelliJ plugin. Dùng IntelliJ LAF, named colors, platform font, `JBUI.scale`, platform components và `AllIcons`.

### Tokens

| Category | Contract |
|---|---|
| Spacing | 4, 8, 12, 16, 24 |
| Control height | 24 compact, 28 standard, 32 prominent |
| Radius | 4 control, 8 card, full pill |
| Icon | 16 action, 20 tool-window |
| Typography | caption, body, body-medium, section-title, mono |
| Colors | surface, raised surface, border, focus, selected, disabled, status roles |
| Interaction | default, hover, pressed, selected, disabled, focus |

### Primitives

- `CodexIconButton`
- `CodexPrimaryButton`
- `CodexCard`
- `CodexChip`
- `CodexStatusBadge`
- `CodexSectionHeader`
- `CodexPopupRow`
- `CodexFocusBorder`

Mỗi primitive sở hữu padding, height, radius, icon gap, focus ring, keyboard activation, tooltip, accessible name và Light/Darcula behavior.

### Icon policy

- Ưu tiên `AllIcons` và IntelliJ Action System.
- Không dùng Unicode glyph thay icon platform tương đương.
- Custom SVG chỉ khi platform thiếu semantic icon phù hợp.
- Icon-only button luôn có tooltip và accessible name.
- Tool-window icon hỗ trợ New UI và Compact Mode.

## Observability Before Optimization

Thêm counters và timings cho:

- Store events per second.
- UI snapshots offered, merged và applied.
- Pending EDT delivery count.
- Projection duration và stale projection count.
- Transcript apply, layout và paint duration.
- Component count, HTML pane count và editor count.
- Height-cache hit rate.
- Scroll frame time.
- Heap retained sau khi task hoàn tất.

Dùng JFR hoặc async-profiler cho allocation/CPU, internal mode cho slow-operation evidence, thread dump khi freeze và IntelliJ UI Inspector cho component/accessibility audit.

## Delivery Sequence

| Phase | Outcome | Exit gate |
|---|---|---|
| 0. Baseline instrumentation | Biết bottleneck thực nằm ở queue, projection, apply, layout hay paint | Reproducible 8-agent report |
| 1. UI delivery bridge | Bounded EDT scheduling và view-selective updates | Tối đa một pending delivery/workspace |
| 2. Incremental block model | Stable identity và keyed reconciliation | Append/delta không rebuild tail |
| 3. Bounded transcript + scroll | Component cap, cache, anchoring và manual-scroll protection | 2.000 block đạt latency/FPS gates |
| 4. UI primitives | Button, card, chip, popup, radius và icon thống nhất | Light/Darcula/HiDPI/a11y audit pass |
| 5. Stress verification | Regression, memory và long-run evidence | Tất cả success metrics pass |

Không làm UI primitive migration lớn trước Phase 1-3 vì thay đổi component hàng loạt sẽ làm nhiễu performance baseline.

## Success Metrics

| Metric | Gate |
|---|---:|
| EDT task duration | Không task trên 500 ms |
| UI action latency p95 | Dưới 100 ms |
| Streaming result visibility | Dưới 250 ms |
| Scroll | Tối thiểu 45 FPS |
| Pending EDT render delivery | Tối đa 1/workspace |
| Concurrent transcript projection | Tối đa 1/thread view |
| Materialized transcript components | Có cap; target 150-250 |
| Tail rebuild on normal append/delta | 0 |
| Long-run memory | Ổn định sau 10 phút và sau task completion |

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| Latest-state coalescing che khuất transient UI action | Chỉ coalesce state projections; edge-triggered actions có lane riêng |
| Height estimate sai gây scroll jump | Cache theo width bucket, anchor correction sau real measurement |
| Cache giữ component/editor gây leak | Cache model/render artifacts, không cache live component vô hạn; disposal tests |
| Incremental identity sai reuse component cũ | Stable ID contract và characterization tests cho mọi block kind |
| Adaptive throttle làm result cảm giác chậm | Gate result visibility dưới 250 ms, critical lane immediate |
| UI migration tạo visual regression | Primitive-by-primitive rollout, UI Inspector, Light/Darcula/HiDPI matrix |

## Stakeholder Impact

| Stakeholder | Impact |
|---|---|
| End user | IDE vẫn tương tác được khi nhiều agent chạy; transcript không nhảy; controls nhất quán |
| Developer | Có state-to-view seam, performance budgets và primitives rõ ràng |
| QA | Có deterministic stress workload và runtime gates thay vì chỉ test projection complexity |
| Operations/support | Freeze report có counters, JFR/thread-dump evidence và reproducible load profile |

## References

- [JetBrains Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [JetBrains User Interface Components](https://plugins.jetbrains.com/docs/intellij/user-interface-components.html)
- [JetBrains Plugin User Experience](https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html)
- [JetBrains Working with Icons](https://plugins.jetbrains.com/docs/intellij/icons.html)
- [JetBrains UI Inspector](https://plugins.jetbrains.com/docs/intellij/internal-ui-inspector.html)

## Next Steps

1. Lập implementation plan theo TDD, bắt đầu bằng instrumentation và failing stress tests.
2. Không thay đổi code trước khi plan xác định ownership, files, test harness và rollback cho từng phase.

## Unresolved Questions

- Không có câu hỏi blocking. Các render frequency và component-cap values có thể điều chỉnh sau Phase 0 dựa trên profile, nhưng success metrics đã cố định.
