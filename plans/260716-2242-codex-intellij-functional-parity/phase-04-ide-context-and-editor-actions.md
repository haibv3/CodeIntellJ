---
phase: 4
title: "IDE Context and Editor Actions"
status: completed
priority: P1
dependencies: [3]
---

# Phase 4: IDE Context and Editor Actions

## Overview

Add typed, previewable editor/file/project context and all six IDE actions with an exact `ContextSnapshot` → `turn/start.input` contract. Runnable result: selection, unsaved buffer, saved-file mention, and automatic context can be inspected/removed and sent to exactly one task.

## Context Links

- [Phase 3 state/controller](./phase-03-conversation-state-and-native-chat.md); [editor/action APIs](../reports/260716-2240-intellij-platform-research.md); [VS-02/03](../reports/260716-2240-parity-test-research.md)
- Gate: Phase 3 normalized-state, watermark merge, queue, and multi-panel tests green.

## Requirements

- Exact actions: `addToThread`, `addFileToThread`, `newChat`, `newCodexPanel`, `openCommandMenu`, `openSidebar`; selection/current/open/`@` file context; automatic toggle; removable previews; visible file errors.
- Snapshot: canonical absolute and content-root-relative path, source kind, UTF-16 editor offsets/line range, UTF-8 wire byte range, document modification stamp, language hint, bounded text, truncation, unsaved flag, content SHA-256, owning project/root identity—captured atomically.
- Root policy: file/context search and dispatch are limited to canonical IntelliJ content roots and the owning project. Cross-project and arbitrary external paths are rejected; no override is implemented in this scope.
- Encoding: validate final JSON against pinned `v2/TurnStartParams.json#/definitions/UserInput`. Saved whole-file references use `MentionUserInput {type:"mention",name,path}`. Selection/unsaved/truncated content uses deterministic `TextUserInput {type:"text",text,text_elements}`; no guessed context field.
- Honest preview: display exact encoded JSON bytes/hash that will be sent plus separately labeled UI-only metadata. For mention inputs, state that app-server may read current disk content after dispatch; do not claim previewed file bytes equal downstream reads.

## Context Field → Wire Contract

| `ContextSnapshot` field | Exact `turn/start` mapping | Status |
|---|---|---|
| `canonicalPath`, `displayName` for saved whole file | `input[]` `MentionUserInput.path/name`, `type="mention"` | Wire |
| `text` for selection/unsaved/truncated snapshot | `input[]` `TextUserInput.text`, `type="text"`; deterministic context header contains relative path/range/content hash followed by exact captured text | Wire |
| UTF-8 span of rendered context block | `TextUserInput.text_elements[].byteRange.start/end`; optional `placeholder` is the visible path/range label | Wire |
| `threadId`; chosen project cwd | enclosing `TurnStartParams.threadId/cwd` via controller/target policy, not duplicated in `UserInput` | Wire |
| editor UTF-16 offsets, line range, modification stamp, language, unsaved, owning root, truncation limit | preview/staleness/revalidation only; only the deterministic header fields above cross wire | UI-only unless named above |
| content SHA-256 | shown with encoded JSON hash; included in text header for content snapshots, not mention inputs | Conditional |

## Architecture

Stateless actions → `IdeActionRouter`. `EditorContextCollector` emits immutable `ContextSnapshot`; panel-local `ContextAttachmentStore` owns attachments. `ContextWireMapper` produces `EncodedContextInput(jsonBytes, sha256, previewModel)` using only schema-mapped variants. `TurnInputAssembler` sends those exact bytes through Phase 3 controller/Phase 2 adapter. Automatic tracking changes future attachments only.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/ContextSnapshot.kt` | 140 LOC | immutable source/UI metadata |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/EditorContextCollector.kt` | 200 LOC | selection/unsaved/root tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/ContextAttachmentStore.kt` | 100 LOC | panel isolation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/ContextWireMapper.kt` | 190 LOC | exact UserInput encoding |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/TurnInputAssembler.kt` | 120 LOC | encoded-byte reuse |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/AutomaticContextTracker.kt` | 120 LOC | listener/disposal tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/IdeActions.kt` | 220 LOC | exact six-action inventory |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/platform/IdeActionRouter.kt` | 120 LOC | typed routes |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/ContextChipsPanel.kt` | 190 LOC | byte/hash/UI-only preview |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/resources/META-INF/plugin.xml` | +70 LOC | action registration |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/resources/messages/CodexBundle.properties` | +35 LOC | action/warning labels |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/context-snapshot-turn-start.json` | reviewed | final encoded golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/context-field-wire-map.json` | reviewed | field mapping fixture |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/platform/ContextWireMapperTest.kt` | 260 LOC | schema+golden+byte equality |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/platform/EditorContextCollectorTest.kt` | 260 LOC | platform context matrix |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/platform/IdeActionRegistryTest.kt` | 140 LOC | exact six commands |

## Functions and Interfaces Checklist

- [x] `EditorContextCollector.capture(Editor?, VirtualFile?, ContextKind): Result<ContextSnapshot>` atomically captures document text/stamp and validates canonical owning content root.
- [x] `ContextWireMapper.encode(snapshot): EncodedContextInput`; outputs only schema-proven `MentionUserInput` or `TextUserInput`, canonical JSON bytes, SHA-256, and explicit UI-only metadata.
- [x] `EncodedContextInput.preview.wireBytes/hash` is the exact immutable buffer passed to `TurnInputAssembler`; dispatch rechecks content-root/path identity and snapshot staleness but never silently re-encodes.
- [x] `TurnInputAssembler.assemble(prompt, encodedInputs)` validates final `TurnStartParams` JSON against pinned schema and golden; a changed input requires a new preview/hash.
- [x] Six stateless `AnAction`s use correct update thread, cheap update, disposed/dumb/no-editor states, and async routing.

## Implementation Steps

1. Freeze field-map and final JSON goldens against checked-in `TurnStartParams`/`UserInput`; test schema validation and reject any unmapped or guessed field.
2. Test selection/whole unsaved/saved mention, UTF-16→UTF-8 ranges, CRLF/Unicode, truncation, rename/delete/symlink, content-root/multi-project ownership, and stamp/hash changes.
3. Implement collector and mapper. Saved mention previews path/name/hash of JSON and warns disk read is deferred; captured text previews exact deterministic header/body bytes and hash.
4. Implement chips with separate “sent fields” and “UI-only metadata”; removal/acceptance and dispatch use immutable encoded bytes. Stale snapshot requires refresh or explicit send-stale confirmation where safe.
5. Register/test six actions and auto-context; `/ide-context` changes future turns only; sandbox verifies byte/hash shown equals gateway request capture.

## Todo

- [x] Freeze exact field/wire and final `turn/start` JSON contracts.
- [x] Implement content-root collector, mapper, preview, assembler.
- [x] Register/test six IDE actions and automatic context.
- [x] Verify gateway-captured bytes/hash equal preview.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Unsaved/selection preview → gateway capture | identical encoded JSON bytes/hash; UI-only fields absent |
| Critical | Saved mention | exact `{type,name,path}` schema; UI warns server reads disk later |
| Critical | Cross-project/external path | rejected without override; no encoded/sent bytes |
| High | UTF-16 Unicode selection | correct UTF-8 byte range and schema-valid `text_elements` |
| High | File changes after preview | dispatch blocks for re-preview; no silent re-encoding |
| Medium | Deleted/unreadable/large file | visible failure/truncation; composer remains usable |

## Success Criteria

- [x] `./gradlew test --tests '*ContextWireMapperTest' --tests '*EditorContextCollectorTest' --tests '*IdeActionRegistryTest'`
- [x] `./gradlew verifyProtocolContract verifyPluginProjectConfiguration verifyPluginStructure`
- [x] `./gradlew runIde`: all six actions work; preview/captured JSON bytes match for selection, unsaved text, and saved mention.

## Risk Assessment / Security

“Preview equals transmitted context” is limited to immutable encoded request bytes; it cannot promise what app-server later reads from a mentioned path. Label that boundary. Content roots, canonical ownership, revalidation, size caps, explicit stale decisions, and no snapshot-body logging prevent cross-project/secret leakage. UTF-16/UTF-8 conversion errors can mislabel spans and must be golden-tested.

## Dependency Map / Next Steps

Requires Phase 3 controller/panels. Phase 5 consumes pre-turn stamped snapshots as one diff-baseline source; Phase 6/7 reuse content-root and encoded-preview contracts for `/project` and commands.
