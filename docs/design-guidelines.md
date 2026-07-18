# Codex IntelliJ Design Guidelines

## Foundation

The plugin follows the active IntelliJ Look and Feel. Use platform fonts, `JBColor.namedColor`, `JBUI.scale`, standard Swing/IntelliJ controls, and `AllIcons` before custom painting. Custom painting is reserved for transcript cards, high-volume plain agent rows, status pills, and the send/stop control where the platform has no equivalent behavior or cannot meet the measured performance budget.

## Metrics

`CodexUiMetrics` is the source of scaled shared dimensions:

| Role | Values |
|---|---|
| Spacing | 4, 8, 12, 16, 24 px before scaling |
| Controls | 24, 28, 32 px before scaling |
| Radius | 4 px controls, 8 px cards |
| Icons | 16 px actions, 20 px tool-window |

Pills may use their live height as a full radius. One-off layout dimensions that represent content measurement rather than a design token may remain local.

## Colors

Use semantic properties from `CodexUiTheme`; do not copy fallback RGB values into components. Every token must resolve under Light and Darcula. Main roles are foreground, muted, border/divider, card, hover, selection, focus, accent, success, danger, and approval.

Composer input colors use the named LAF roles `TextField.background` and `TextField.foreground` through `CodexUiTheme.inputBackground` and `CodexUiTheme.inputForeground`. Keep the input background, foreground, and caret bound to these live named colors so a runtime LAF switch does not leave stale Light or Darcula colors behind.

## Buttons and icons

- Use `CodexIconButton` for icon-only actions. Tooltip and accessible name are mandatory; Enter and Space activation and the focus ring are built in.
- Use native `JButton` for actions with text such as Accept, Reject, Review, and Undo. Apply shared height/spacing metrics without introducing another wrapper.
- Prefer an `AllIcons` semantic match. Do not use replaceable `+`, back-arrow, copy, remove, or delete glyph text on Swing buttons.
- HTML transcript chevrons and inline symbols are document content, not Swing icon buttons, and follow the renderer contract.

## Transcript surfaces

- A visual block has one semantic ID and deterministic revision.
- Components are keyed and reused; content hashes are not an identity boundary.
- The live component window uses a profile-tuned target of 40 blocks and must not exceed 250 materialized blocks. The target covers the visible viewport plus overscan; the hard cap remains a regression boundary.
- Reading history preserves an anchor; follow-live stays at the bottom. Scrollbar dragging defers non-critical component mutation and retains only the latest pending model.
- Live editors and HTML panes are never stored in artifact caches; eviction disposes them through `CodexChatPanel` ownership.
- One-line plain agent messages use a recyclable painted row with platform font/colors, a copy action, and an accessible description. This fast path is not directly text-selectable; markdown, links, code fences, and structured content remain on selectable HTML/editor/card hosts.
- Plain-row wrapping must remain linear in text length, iterate Unicode code points rather than UTF-16 units, and remeasure from the live component width when layout changes without a state update.
- Never rebind a recyclable row while the row or one of its descendants owns keyboard focus; detach it instead so a focused copy action cannot silently change semantic target.

## Accessibility and responsive layout

- Icon-only actions require a tooltip, accessible name, focusability, and visible keyboard focus.
- Interactive lists require an accessible name and a nonblank accessible description. The recent-task list uses `Nhiệm vụ gần đây` and describes how to open a task; do not derive either value from task or prompt content.
- Keep transcript horizontal scrolling disabled and derive content width from the live viewport, including 320 px tool-window layouts.
- The composer toolbar is responsive rather than hiding controls: at narrow widths it uses two rows, with model/reasoning/IDE context above and attach/approval/send below; at wide widths all primary controls return to one row.
- Keep composer input, model, reasoning, IDE context, attach, approval, and send reachable by focus traversal at both responsive modes.
- Use platform scaling for metrics and verify focus rings and icons at HiDPI.
