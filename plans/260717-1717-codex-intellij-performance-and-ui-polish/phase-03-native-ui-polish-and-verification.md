---
title: "Phase 3: Native UI polish and verification"
status: done
---

# Phase 3: Native UI polish and verification

## Overview

Apply an IntelliJ-native visual polish pass to the main chat/composer workflow and verify the complete change set.

## Requirements

- [ ] Transcript uses actual viewport width down to 320 px without clipping while horizontal scrolling remains disabled.
- [ ] Attachment chips, plus-menu hover, placeholders, borders, and action states use semantic Light/Dark theme tokens.
- [ ] Plus, send/cancel, attachment remove, menu selection, and modified-file navigation are keyboard reachable with visible focus and accessible descriptions.
- [ ] Preserve dense native IntelliJ layout, HiDPI scaling, existing Vietnamese UX, and all current actions.

## Implementation Steps

1. Extend `CodexUiTheme` with named hover/selection/focus tokens and remove raw duplicate Light/Dark fills from touched components.
2. Make transcript sizing derive from viewport extent with a safe minimum only for uninitialized layout.
3. Replace mouse-only plus-menu rows with focusable button/list semantics and Enter/Space activation; restore Escape popup behavior.
4. Restore focusability/focus indication and accessible names on icon/custom action buttons; use platform icons where the touched control already has an equivalent.
5. Make attachment and modified-file actions keyboard accessible without changing callbacks.
6. Run focused tests, full tests, schema/protocol verification, plugin build, pending-diff review, and manual `runIde` checklist when available.

## Todo

- [ ] Narrow-width, Light/Dark token, focus, and keyboard tests pass.
- [ ] Existing transcript, composer, modified-files, multi-panel, and parity tests pass.
- [ ] `verifyCodexSchemaManifest` and `verifyProtocolContract` pass.
- [ ] `verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin` pass.
- [ ] Independent review finds no contract or business-logic regression.

## Success Criteria

The primary tool-window experience is responsive, theme-safe, and keyboard operable; automated gates are green and any manual-only UI risk is explicitly listed.

## Related Files

- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexUiTheme.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexComposerBar.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/ComposerAttachmentsStrip.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/ComposerPlusMenu.kt`
- `src/main/kotlin/dev/haibachvan/codexintellij/ui/ModifiedFilesCardPanel.kt`
- Corresponding unit/UI tests

## Risks And Rollback

- LAF behavior varies. Prefer IntelliJ named colors and standard buttons; validate Light and Darcula manually.
- Do not broaden this phase into full localization, task-browser redesign, or real Starter/Driver infrastructure.
