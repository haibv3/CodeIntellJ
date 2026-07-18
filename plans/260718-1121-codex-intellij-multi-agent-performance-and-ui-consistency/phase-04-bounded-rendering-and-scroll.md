---
phase: 4
title: "Bounded rendering và scroll"
status: completed
priority: P1
dependencies: [3]
---

# Phase 4: Bounded rendering và scroll

## Context Links

- [Phase 3 keyed reconciliation](./phase-03-incremental-transcript-reconciliation.md)
- `CodexChatPanel.kt`: current scroll-near-bottom và `scrollTranscriptToBottom()` behavior.
- `TranscriptHtmlBlockHost.kt`: Phase 3 measured HTML lifecycle seam.
- `CodeFenceCardPanel.kt`: native editor allocation/disposal boundary.

## Overview

Giới hạn live Swing component bằng viewport window theo transcript block/chunk, dùng top/bottom spacer heights và stable scroll anchor. Khi user kéo scrollbar, model vẫn nhận state mới nhưng non-critical component mutation bị hoãn; follow-live vẫn cập nhật trong performance budget.

## Requirements

- Target profile-tuned 40 materialized blocks, hard gate không vượt 250 trong workload chuẩn trừ component đang pinned vì focus/action.
- Windowing theo stable block IDs; không tạo custom virtual-flow framework hoặc thay `JScrollPane`.
- Cache sanitized HTML và measured height theo block ID, revision và width bucket; cache bounded, không giữ live editor/component vô hạn.
- Follow-live giữ đáy; reading-history giữ anchor block ID + pixel offset.
- `verticalScrollBar.valueIsAdjusting` hoãn non-critical apply và hiển thị chỉ báo cập nhật mới.
- Một batch chỉ revalidate/repaint một lần; không tạo nested `invokeLater` cho bottom scroll thông thường.
- Resize/Light-Darcula font metrics invalidates đúng height bucket và không gây overlap.
- Editor/HTML host rời materialized window phải được dispose theo cache policy.

## Architecture

```text
All keyed TranscriptBlocks
       |
       v
TranscriptViewportWindow
       | prefix height index
       | visible anchor + overscan
       | top/bottom spacer heights
       v
Keyed reconciler on materialized slice
       |
       v
JScrollPane viewport
```

Window model là pure Kotlin data logic; Swing host chỉ áp dụng slice và spacer result. Unknown height dùng conservative estimate theo block kind, sau real measurement cập nhật cache và correction quanh anchor.

## Related Code Files

| Action | Absolute path | Purpose |
|---|---|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptViewportWindow.kt` | Pure window, prefix heights, anchor and spacer model |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptHtmlBlockHost.kt` | Bounded measurement cache and width-bucket invalidation |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt` | Window application, scroll modes, new-update indicator and batch layout |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodeFenceCardPanel.kt` | Verify editor allocation/disposal under window transitions |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/TranscriptViewportWindowTest.kt` | Cap, overscan, anchor, spacer and cache tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/TranscriptScrollContractTest.kt` | Follow-live, reading, dragging and correction tests |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiAgentRenderScaleTest.kt` | Component/materialization operation-count gate |

## Tests Before

Write RED pure-model tests:

- `2000 blocks materialize no more than 250 around viewport`.
- `scrolling down advances window without changing anchor pixel offset`.
- `prepend and update above anchor preserve visible content`.
- `follow live remains bottom-pinned after append`.
- `manual reading never auto-jumps to bottom`.
- `dragging defers apply and retains latest pending window`.
- `width bucket or revision change invalidates only affected height entry`.
- `evicted code editor is disposed once`.
- `top plus materialized plus bottom heights equal total estimated height`.

Use pure height fixtures and fake scrollbar state; không assert real FPS trong unit tests.

## Refactor

- Implement `TranscriptViewportWindow` with prefix-height index and bounded overscan.
- Keep all block models in memory but materialize only window slice.
- Add top/bottom spacer components managed by keyed host.
- Move scroll state from boolean `stickToBottom` to explicit `FollowLive`, `Reading(anchor, offset)` and `Dragging` contract.
- Defer component apply while scrollbar is adjusting; update latest model and show a lightweight new-update indicator.
- Apply measured-height corrections around anchor after layout, without nested unbounded runnable chains.
- Add bounded LRU for sanitized HTML/height artifacts; do not cache live Swing editors outside window.

## Tests After

- 2.000-block fixture: live component count <=250.
- Repeated scroll top-bottom: create/dispose counts stabilize and no disposed editor reused.
- Streaming while reading: anchor stays within one pixel after correction and indicator appears.
- Streaming while following live: newest block visible within 250 ms runtime gate.
- Resize 320 to 900 px and back: no overlap, clipping or runaway remeasurement.
- Empty, short and one-very-tall-block transcripts bypass window errors.

## Implementation Steps

1. Add RED pure window and scroll contract tests.
2. Implement prefix heights, visible range, overscan and spacer calculations.
3. Integrate window slice with Phase 3 reconciler.
4. Add explicit scroll modes and drag deferral.
5. Add bounded height/render artifact cache and exact disposal ownership.
6. Verify narrow viewport, resize and theme/font invalidation.
7. Run 10-minute `runIde` stress; capture FPS, EDT task duration, result visibility, heap and component counts.
8. If 45 FPS or component cap fails, profile and tune overscan/cache; do not jump to full virtualization without new plan approval.

## Todo

- [x] RED window/cap tests added.
- [x] RED scroll anchor tests added.
- [x] Materialized component cap GREEN.
- [x] Follow-live and reading-history contracts GREEN.
- [x] Dragging deferral and update indicator GREEN.
- [x] Height cache invalidation and disposal GREEN.
- [x] Runtime 2.000-block/10-minute evidence recorded.
- [x] Full unit suite GREEN.

## Success Criteria

- [x] Materialized components stay within cap for standard workload.
- [x] Scroll position does not jump during unrelated streaming updates.
- [x] Result visibility, FPS and EDT freeze runtime gates pass.
- [x] Heap/editor/HTML host counts stabilize after scrolling and task completion.
- [x] No full virtualization framework introduced.

## Runtime Evidence

Workload `runIde` thực tế trên IU-261.26222.65: 8 agent, 5.000 event, đúng 2.000 semantic block, chạy 600 giây và scroll 37.248 tick.

| Measurement | Result | Gate |
|---|---:|---:|
| Result visibility | 180,7 ms | <250 ms |
| Maximum observed EDT gap | 101,4 ms | <500 ms |
| Scroll action p95 | 1,60 ms | <100 ms |
| Scroll FPS | 62,1 | >=45 |
| Maximum materialized blocks | 40 | <=250 |
| Maximum transcript host children | 43 | <=253 including spacers/glue |
| Retained heap delta after full GC | ~6,6 MB | Stable |
| Live HTML hosts after completion | 0 | No offscreen retention |

JFR cuối được lưu local ngoài repository; capture không chứa prompt hoặc transcript content trong báo cáo này.

### Profile-driven deviation

Target ban đầu là 200 block. Baseline runtime ghi nhận result visibility 920 ms, EDT gap 845 ms và khoảng 16 FPS; JFR cho thấy Swing HTML parsing/`StyledDocument` cùng layout của 200 component là hot path. Sau fast path native cho plain agent row, non-allocating height cache và O(n) host reconciliation, 200 component vẫn tạo gap 2-4 giây trong các vòng layout/reconcile. Theo Implementation Step 8, overscan được tune xuống 40 block (khoảng 17 row nhìn thấy + overscan) trong khi hard cap 250 giữ nguyên. Không có virtual-flow framework mới.

## Regression Gate

```bash
./gradlew test --tests '*TranscriptViewportWindowTest' --tests '*TranscriptScrollContractTest' --tests '*MultiAgentRenderScaleTest' --tests '*HtmlSwingSafeTest' --console=plain
./gradlew test --console=plain
```

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Height estimate gây jump | Prefix cache + post-measure anchor correction + width buckets |
| Focused control bị evict | Pin focused/action-active block until focus leaves |
| Cache leak | Bounded artifact LRU; never retain live editor outside window |
| Spacer math drift | Total-height invariant and prepend/update tests |
| Windowing phức tạp thành rewrite | Keep `JScrollPane`, current components and keyed reconciler; pure model only |

## Security Considerations

- Cache keys dùng block IDs/revisions, không serialize transcript content.
- Performance report không chứa prompt, code output hoặc local file paths.

## Next Steps

Phase 5 chỉ bắt đầu sau khi performance gates pass hoặc mọi deviation có profile evidence và user-approved plan update.
