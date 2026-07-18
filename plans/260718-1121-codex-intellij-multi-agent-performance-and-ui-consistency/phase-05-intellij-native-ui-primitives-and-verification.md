---
phase: 5
title: "IntelliJ-native UI primitives và verification"
status: completed
priority: P1
dependencies: [4]
---

# Phase 5: IntelliJ-native UI primitives và verification

## Context Links

- [Phase 4 bounded rendering](./phase-04-bounded-rendering-and-scroll.md)
- [JetBrains UI Components](https://plugins.jetbrains.com/docs/intellij/user-interface-components.html)
- [JetBrains Working with Icons](https://plugins.jetbrains.com/docs/intellij/icons.html)
- [JetBrains UI Inspector](https://plugins.jetbrains.com/docs/intellij/internal-ui-inspector.html)
- `CodexUiTheme.kt`: current semantic colors.
- `CodexUiPolishTest.kt`: current width, token and accessibility coverage.

## Overview

Khóa design contract IntelliJ-native sau khi performance ổn định. Thêm spacing/size/radius/icon metrics và primitive nhỏ chỉ khi có ít nhất hai call site. Migrate main chat/composer surfaces, chạy full automated gates và manual Light/Darcula/HiDPI/accessibility/performance matrix.

## Requirements

- Dùng IntelliJ LAF, named colors, platform font, `JBUI.scale`, standard controls và `AllIcons` khi có semantic match.
- Token contract: spacing 4/8/12/16/24; control height 24/28/32; radius 4 control/8 card/full pill; icon 16 action/20 tool-window.
- Icon-only actions có tooltip, accessible name, visible focus và Enter/Space activation.
- Không Unicode glyph thay icon platform tương đương cho Swing buttons (`+`, back, copy, remove/delete); HTML disclosure chevrons không bị rewrite trong phase này.
- Không tạo wrapper cho `JPanel`/`JButton` nếu token/factory đã đủ; primitive cần ít nhất hai real call sites.
- Giữ dense tool-window layout, Vietnamese UX, Light/Darcula và HiDPI.
- UI migration không làm regression các performance metrics Phase 2-4.

## Architecture

```text
CodexUiTheme (semantic colors)
       +
CodexUiMetrics (scaled spacing, size, radius, icon)
       +
CodexIconButton / shared focus-border helpers
       |
       v
Existing IntelliJ/Swing components
```

Không tạo parallel design framework. Standard `JButton`, `JBLabel`, `JBScrollPane`, Action System và platform icons vẫn là nền tảng.

## Related Code Files

| Action | Absolute path | Purpose |
|---|---|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexUiMetrics.kt` | Scaled spacing, control, radius and icon metrics |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexIconButton.kt` | Shared icon-button focus, tooltip and accessibility contract |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexUiTheme.kt` | Complete semantic state colors, remove touched raw duplicates |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexComposerBar.kt` | Plus/send/cancel/dropdown sizing and icons |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexWorkspacePanel.kt` | Back/copy/task actions, spacing and agent strip |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ComposerAttachmentsStrip.kt` | Remove action, chip radius and spacing |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ComposerPlusMenu.kt` | Popup row metrics, selection and focus |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodeFenceCardPanel.kt` | Copy action and card radius |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ModifiedFilesCardPanel.kt` | Card/action metrics and icons |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ContextChipsPanel.kt` | Remove action and chip tokens |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ApprovalBanner.kt` | Standard button sizing, focus and semantic status colors |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/AgentChipPanel.kt` | Pill radius/spacing/status tokens |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexToolWindowHeader.kt` | Platform icon and control metrics |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/CodexUiPrimitivesTest.kt` | Token, focus, keyboard and accessibility contract |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/CodexUiPolishTest.kt` | Light/Dark token and narrow-layout regression |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/docs/system-architecture.md` | Document state-to-view and transcript ownership |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/docs/design-guidelines.md` | Evergreen UI token, primitive and icon contract |

## Tests Before

Write RED tests before primitive migration:

- `ui metrics expose scaled spacing control radius and icon sizes`.
- `icon button requires nonblank tooltip and accessible name`.
- `icon button activates with enter and space`.
- `icon button exposes visible focus state`.
- `semantic colors resolve in light and dark variants`.
- `main chat Swing buttons do not use replaceable unicode glyphs`.
- `repeated card and chip surfaces use shared radius metrics`.
- `320 px viewport keeps composer and transcript actions reachable`.

Static source assertions chỉ dùng cho policy khó expose; interaction behavior phải được test qua components.

## Refactor

- Add `CodexUiMetrics` as the single source for scaled dimensions.
- Add one `CodexIconButton` primitive because icon-only behavior repeats across composer, code, attachments and workspace.
- Keep standard labeled `JButton` for Accept/Reject/Review; apply metrics rather than wrapper inheritance.
- Replace touched raw colors/radii/padding with semantic tokens.
- Replace Swing glyph buttons with `AllIcons` equivalents when semantic match exists; use custom SVG only after documented gap.
- Migrate one component group at a time; run focused tests and Phase 2-4 performance counters after each group.
- Use UI Inspector to compare with native IntelliJ controls and run accessibility issue checks.

## Tests After

- Keyboard traversal reaches plus, send/cancel, copy, remove, review, accept/reject and task navigation.
- Every icon-only action has tooltip and accessible name.
- Light/Darcula visual states: default, hover, pressed, selected, disabled, focus, running, success, warning, error.
- 320 px and wide viewport remain usable; no horizontal transcript scroll.
- HiDPI scale does not clip icons/focus rings.
- Phase 2 delivery count, Phase 3 tail rebuild and Phase 4 component cap remain unchanged.

## Implementation Steps

1. Inventory current buttons, radii, raw colors, icons and duplicated padding; map each to a semantic role.
2. Add RED metric/primitive/accessibility tests.
3. Implement `CodexUiMetrics`, `CodexIconButton` and only required focus/border helpers.
4. Migrate composer and workspace primary actions first.
5. Migrate attachments, context, code, modified-files, approval and agent surfaces.
6. Run UI Inspector checks in Light, Darcula and HiDPI; capture defects without embedding screenshots/log secrets in repo.
7. Re-run 8-agent/2.000-block/10-minute performance workload after visual migration.
8. Update architecture and design guidelines with actual final APIs only.
9. Run full verification matrix and independent code review before marking plan complete.

## Todo

- [x] RED token/primitive tests added.
- [x] Button/icon/radius inventory completed.
- [x] Shared metrics and icon button GREEN.
- [x] Main UI surfaces migrated without speculative wrappers.
- [x] Automated keyboard/accessibility contract pass.
- [x] Light/Darcula/HiDPI, keyboard-only và UI Inspector manual matrix pass.
- [x] Performance regression matrix pass.
- [x] Architecture/design docs match actual code.
- [x] Full verification commands pass.

## Success Criteria

- [x] Main app controls use one metrics/icon/accessibility contract.
- [x] No replaceable Unicode glyph remains on migrated Swing icon buttons.
- [x] UI feels native in Light, Darcula and compact/HiDPI contexts.
- [x] All automated performance and correctness metrics from prior phases still pass.
- [x] Docs contain only APIs and tokens that exist in final code.

## Regression Gate

```bash
./gradlew test --tests '*CodexUiPrimitivesTest' --tests '*CodexUiPolishTest' --tests '*MultiAgentRenderScaleTest' --tests '*TranscriptRendererTest' --console=plain
./gradlew test uiTest --console=plain
./gradlew verifyCodexSchemaManifest verifyProtocolContract --console=plain
./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin --console=plain
```

Manual gate:

```bash
./gradlew runIde
```

Checklist: internal mode, Light, Darcula, 320 px tool window, HiDPI, keyboard-only flow, UI Inspector accessibility, 8-agent stream, 2.000-block scroll, JFR capture, heap after completion.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Visual migration làm nhiễu performance | Chỉ chạy sau Phase 4; rerun counters per component group |
| Wrapper proliferation | Primitive requires at least two real call sites; otherwise use standard component + token |
| `AllIcons` semantic mismatch | UI Inspector/Icon list verification; custom SVG only with documented gap |
| LAF-specific sizing/contrast | Light/Darcula/HiDPI matrix and named colors |
| `uiTest` bị hiểu nhầm là full Driver/FPS proof | Keep runtime/manual gate explicit and report evidence separately |

## Security Considerations

- Không thêm telemetry/network reporting; performance data local only.
- Redact JFR, logs, screenshots và thread dumps before share.
- Accessible descriptions không chứa prompt/file content nhạy cảm.

## Next Steps

Sau implementation full verification, chạy independent `ck-code-review`; chỉ ship khi review không còn finding blocking và user duyệt release workflow.
