---
phase: 3
title: "Incremental transcript reconciliation"
status: completed
priority: P1
dependencies: [2]
---

# Phase 3: Incremental transcript reconciliation

## Context Links

- [Phase 2 delivery bridge](./phase-02-ui-state-delivery-bridge.md)
- `TranscriptBlock.kt`: hiện chỉ có content-derived fingerprint, chưa có semantic identity.
- `TranscriptRenderer.kt`: tạo ordered blocks từ normalized state.
- `CodexChatPanel.applyTranscriptBlocks()`: hiện reuse prefix rồi dispose/rebuild tail.
- `HtmlSwingSafe.kt`: variable-height `JBHtmlPane` measurement.

## Overview

Đưa semantic ID và deterministic revision vào transcript blocks, sau đó reconcile theo key để chỉ create/update/remove/move block thực sự thay đổi. Tách HTML host và reconciliation khỏi `CodexChatPanel` 890 dòng; không rewrite markdown renderer hoặc thay block order.

## Requirements

- Mọi `TranscriptBlock` có unique semantic ID trong một render và revision phản ánh visible/interactive state.
- ID không phụ thuộc HTML hash; revision không dùng `String.hashCode()` làm correctness boundary.
- Normal append tạo đúng một component mới; streaming delta update đúng block cùng ID.
- Insert/remove/move không dispose unrelated editor/HTML host.
- Reconciliation O(n) theo block count, duplicate ID fail fast trong tests.
- `TranscriptRenderer.render()`, plain rendering, block order, file links, code cards, modified-files card và agent chip behavior giữ nguyên.
- Background projection vẫn single-flight; Swing mutation chỉ trên EDT.

## Architecture

```text
NormalizedServerState + render options
       |
       v
TranscriptRenderer
       | TranscriptBlock(id, revision, payload)
       v
TranscriptBlockReconciler (pure O(n) plan)
       | Keep / Update / Insert / Move / Remove
       v
Transcript component host on EDT
```

Identity rules:

| Block source | ID rule | Revision inputs |
|---|---|---|
| User/agent message prose | item ID + segment index | item arrival seq + render option state |
| Fenced code | item ID + code segment index | item arrival seq + language/content version |
| Reasoning/activity | section/item key | source arrival seq + running/expanded/elapsed bucket |
| Modified files | thread ID + turn ID | patch paths/counts + terminal state |
| Agent chip | agent item ID | agent status + summary source version |
| Local notice | existing notice ID | notice content/version |
| Empty/error block | reserved render-local ID | state/error generation |

Revision có thể là immutable value/data tuple; không cần public API hoặc cryptographic hash.

## Related Code Files

| Action | Absolute path | Purpose |
|---|---|---|
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptBlock.kt` | Add semantic ID/revision contract |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRenderer.kt` | Emit identity/revision at source boundaries |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptBlockReconciler.kt` | Pure keyed O(n) reconciliation plan |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/TranscriptHtmlBlockHost.kt` | Extract measured `JBHtmlPane` lifecycle from chat panel |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt` | Apply reconcile operations and own component slots |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/TranscriptRendererTest.kt` | Preserve output/order and assert ID/revision stability |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/TranscriptBlockReconcilerTest.kt` | Pure diff operation and disposal contract tests |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/ModifiedFilesCardTest.kt` | Identity/order regression for native card |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/InterimAgentMessageTest.kt` | Interim/final agent grouping identity regression |

## Tests Before

Write RED tests:

- `unchanged block keeps semantic id and revision`.
- `growing agent message keeps id and advances revision`.
- `code fence segments keep stable ids when prose grows`.
- `local notice and modified files ids never collide`.
- `duplicate block id is rejected`.
- `append emits keep N plus insert one`.
- `single middle update emits one update and keeps tail`.
- `remove and move dispose only removed components`.
- `reconcile operation count stays linear for 2000 blocks`.

Characterization tests must freeze current rendered block order before constructor changes.

## Refactor

- Extend sealed block variants with ID/revision while keeping visible payload fields.
- Generate IDs where renderer still has item, turn, section và segment context; do not reverse-engineer identity from final HTML.
- Implement pure reconciler over previous slots and next blocks.
- Extract `HtmlBlockHost` into `TranscriptHtmlBlockHost`; preserve sanitize, font, hyperlink, measurement and disposal behavior.
- Replace prefix/tail algorithm in `CodexChatPanel` with reconcile plan applied once on EDT.
- Reuse existing component for `Keep`; update compatible component in place for `Update`; replace only when kind changes under same semantic source.
- Batch one revalidate/repaint after all operations.

## Tests After

- Existing transcript, interim agent, modified files, HTML safety and code fence tests pass unchanged semantically.
- Phase 1 workload proves normal streaming delta has zero unrelated tail disposals.
- Active `CodeFenceCardPanel` editor survives an unrelated earlier HTML update.
- Stale background generation never applies reconcile operations.
- Disposal clears all remaining slots exactly once.

## Implementation Steps

1. Add block identity characterization and RED reconciler tests.
2. Define ID/revision rules for every existing sealed variant and renderer call site.
3. Implement pure O(n) reconciliation with duplicate-ID validation.
4. Extract HTML host without behavior change; run HTML safety tests.
5. Convert `CodexChatPanel` slot ownership from fingerprint-prefix to keyed slots.
6. Remove obsolete full-transcript fingerprint only after stale generation and no-op apply tests cover replacement behavior.
7. Profile 2.000-block append/update; record creates, updates, removals and disposals.

## Todo

- [x] Current block order characterization GREEN.
- [x] RED ID/revision tests added.
- [x] RED reconcile operation tests added.
- [x] Every block variant emits unique semantic ID.
- [x] O(n) reconciler GREEN for 2.000 blocks.
- [x] HTML host extraction preserves measurement/disposal.
- [x] Normal delta performs zero tail rebuild.
- [x] Full unit suite GREEN.

## Success Criteria

- [x] Append creates only new block components.
- [x] Single streaming update mutates only matching block component.
- [x] Unrelated code editor/component identity remains stable.
- [x] Duplicate IDs fail deterministically during tests/development.
- [x] Renderer output and all existing interactions remain behavior-compatible.

## Regression Gate

```bash
./gradlew test --tests '*TranscriptBlockReconcilerTest' --tests '*TranscriptRendererTest' --tests '*InterimAgentMessageTest' --tests '*ModifiedFilesCardTest' --tests '*HtmlSwingSafeTest' --console=plain
./gradlew test --console=plain
```

## Risk Assessment

| Risk | Mitigation |
|---|---|
| ID collision hoặc unstable segment index | Explicit per-source rules, duplicate-ID RED tests, fence/prose growth fixtures |
| Revision bỏ sót expanded/timing state | Include relevant render option version and elapsed bucket |
| Move operation làm hỏng Swing Z-order | Focused component-order test and one EDT batch |
| Extracted HTML host đổi sizing | Reuse implementation first, refactor only after characterization GREEN |
| Reconciler over-engineered | Map + single traversal; no LCS, no generic tree diff |

## Security Considerations

- Block IDs chỉ dùng internal semantic IDs, không log content.
- Không đưa raw HTML/prompt vào performance metrics hoặc failure report.

## Next Steps

Phase 4 chỉ bắt đầu khi keyed reconciliation đạt zero-tail-rebuild gate và full unit suite xanh.
