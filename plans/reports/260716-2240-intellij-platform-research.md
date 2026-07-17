---
title: IntelliJ Platform 2026.1 Implementation Research
date: 2026-07-16
status: complete
target: IntelliJ IDEA Community 2026.1.4 on Linux
sources: official-jetbrains-only
---

# IntelliJ Platform 2026.1 Implementation Research

## Summary

Use one Kotlin/JVM Gradle module targeting IDEA `2026.1.4`, branch `261`, Java 21,
Kotlin 2.3.20, and IntelliJ Platform Gradle Plugin `2.18.1`. Keep process, protocol,
and state independent of Swing behind a project service whose coroutine scope owns
app-server lifetime; use native Tool Window, actions, settings, context, and diffs.

## Findings

### Toolchain and compatibility

- IDEA 2026.1 is build branch `261` and requires Java 21. Platform versions 2024.2+
  require IntelliJ Platform Gradle Plugin 2.x; current documented version is `2.18.1`.
  Target the exact IDE with `intellijIdea("2026.1.4")`; set JVM toolchain/Java/Kotlin
  bytecode target 21 and `sinceBuild=261` (`untilBuild=261.*` for intentionally strict
  2026.1-only compatibility). [Gradle configuration](https://plugins.jetbrains.com/docs/intellij/configuring-gradle.html),
  [build ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- Plugins targeting 2025.1+ require Kotlin 2.x; IDEA 2026.1 bundles stdlib `2.3.20`.
  Pin Kotlin JVM plugin `2.3.20`, set `kotlin.stdlib.default.dependency=false`, and never
  bundle `kotlinx-coroutines`; use the IDE-bundled fork. Do not add Java/Kotlin IDE
  plugin dependencies unless PSI/Analysis API is actually used. Basic editor/VFS
  context is Platform API only. [Kotlin support](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html)

### Native UI, ownership, and threading

- Register `com.intellij.toolWindow` declaratively with a `ToolWindowFactory`; create
  native Swing/UI-DSL content lazily, use `SimpleToolWindowPanel`, and attach each tab's
  disposable with `Content.setDisposer()`. Tool-window scheduling uses
  `ToolWindowManager.invokeLater()`. [Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- Make one project service the composition root. Constructor-inject `(Project,
  CoroutineScope)`; the platform cancels that scope on project close or plugin unload.
  The service owns process/session resources; content disposers own only tab listeners
  and render jobs. Never use `Project` or `Application` as a disposer parent and never
  cache services in static/immutable fields. [Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html),
  [disposal](https://plugins.jetbrains.com/docs/intellij/disposers.html)
- CPU/state reduction: service scope/`Dispatchers.Default`; blocking process and file
  I/O: narrow `withContext(Dispatchers.IO)` blocks; Swing mutations:
  `withContext(Dispatchers.EDT)`. Use cancellable `readAction {}`/`smartReadAction {}`
  for model reads. IDEA 2026.1 deprecates non-cancellable read actions and logs an error
  if `OSProcessHandler.waitFor()` runs on EDT or under a read lock.
  [dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html),
  [2026.1 changes](https://plugins.jetbrains.com/docs/intellij/api-notable-list-2026.html)

### Platform integration APIs

- Launch with `GeneralCommandLine`, monitor with `OSProcessHandler` plus
  `ProcessListener`/`ProcessAdapter`, call `startNotify()`, and terminate through the
  handler during service disposal. Treat output callbacks as arbitrary chunks: an
  incremental UTF-8 JSONL/JSON-RPC framer must handle split and coalesced messages;
  keep stderr diagnostic-only and writes serialized. [Execution API](https://plugins.jetbrains.com/docs/intellij/execution.html)
- Action invocation context: `CommonDataKeys.EDITOR` and `CommonDataKeys.VIRTUAL_FILE`.
  Tool-window context: `FileEditorManager.getSelectedTextEditor()` and selected files;
  selection comes from `Editor.selectionModel`, file from
  `FileDocumentManager.getFile(editor.document)`. Listen with
  `FileEditorManagerListener` only when automatic context must track tab changes.
  Capture path, selection offsets/text, and document modification stamp atomically;
  avoid PSI/index access for phase one. [Editors](https://plugins.jetbrains.com/docs/intellij/editors.html),
  [working with text](https://plugins.jetbrains.com/docs/intellij/working-with-text.html)
- After app-server changes disk files, request an asynchronous VFS refresh (scoped
  `VirtualFile.refresh()`/`RefreshQueue`, or `refreshAndFindFileByNioPath()` for a new
  path) and continue in its completion callback. Never synchronously refresh on EDT or
  while holding a read lock. Show review with `DiffContentFactory`, `SimpleDiffRequest`,
  and `DiffManager.showDiff()`; preserve before-content before applying/refreshing.
  [VFS](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html),
  [Diff API source](https://github.com/JetBrains/intellij-community/tree/master/platform/diff-api/src/com/intellij/diff)
- Persist non-secret preferences in an application/project service implementing
  `PersistentStateComponent`, using `@State`/`@Storage`; expose them with a
  `Configurable`. Do not persist auth tokens or duplicate Codex authentication state.
  [state persistence](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html),
  [settings tutorial](https://plugins.jetbrains.com/docs/intellij/settings-tutorial.html)
- Keep every `AnAction` stateless, make `update()` fast, and implement
  `getActionUpdateThread()` (prefer BGT for project/VFS data; EDT only for Swing).
  In 2026.1, start asynchronous action work from `AnActionEvent.coroutineScope`.
  [Action System](https://plugins.jetbrains.com/docs/intellij/action-system.html)

## Recommendations

### Minimal boundaries and file inventory

Keep one Gradle module; packages are seams, not subprojects:

```text
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/*
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/CodexBundle.properties
src/main/kotlin/<base>/appserver/   # command line, lifecycle, framing, protocol adapter
src/main/kotlin/<base>/session/     # reducer, models, capabilities, commands/agents
src/main/kotlin/<base>/platform/    # project service, context, diff/VFS, settings, actions
src/main/kotlin/<base>/ui/          # tool window, tabs, native components/rendering
src/test/kotlin/<base>/{appserver,session,platform}/
src/test/resources/fixtures/appserver/  # versioned protocol streams and fake CLI data
```

Split Gradle modules only after a demonstrated classpath/test-runtime need. Keep protocol
DTOs generated or fixture-validated behind `appserver`; UI depends on session state, not
raw process events. Use a fake executable/process harness for lifecycle tests.

### Phase order and gates

1. Bootstrap toolchain/plugin descriptor; `runIde` opens an empty native Tool Window.
2. App-server framing/process lifecycle plus fixtures: start, stream, exit, cancel, restart.
3. Thread/turn reducer vertical slice: prompt, streaming, interrupt; UI observes snapshots.
4. Stateless actions, explicit/automatic editor context, approvals and command routing.
5. Patch event capture, async VFS refresh, native diff, file/line navigation.
6. Settings, history/multiple tabs, agents/subagents, slash-command completeness.
7. Unknown-event/version resilience, leak/EDT checks, UI smoke, parity audit.

Each phase must leave a runnable sandbox and tests. Commands:

```bash
./gradlew test
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
./gradlew buildPlugin verifyPlugin
./gradlew runIde
```

Use `BasePlatformTestCase`/light fixtures for platform behavior, pure JVM tests for
framing/reducers, full-product Driver UI tests only for critical smoke flows. Configure
Plugin Verifier with `ides { current() }` for the exact target. JetBrains recommends
real platform components over mocks. [testing](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html),
[light tests](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html),
[verification](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html)

## Risks

- UI freeze/deadlock: process wait, synchronous refresh, read action, or JSON parsing on
  EDT. Enforce dispatcher boundaries and test under internal mode.
- Zombie process/leak: service scope owns process; every listener/job gets the shortest valid parent disposable.
- Protocol corruption: callback/message assumptions, mixed stderr, or concurrent writes.
  Require incremental framing, separate channels, and one writer.
- Stale/wrong context: capture one immutable invocation snapshot and label source/modification stamp.
- Misleading diff: snapshot before-content, then refresh; surface missing/deleted/binary files explicitly.
- Compatibility failure: gate duplicate Kotlin/coroutines, internal APIs, and undeclared IDE-plugin dependencies.

## Unresolved Questions

- Is compatibility intentionally restricted to `261.*`, or should `untilBuild` remain open?
- Must context include unsaved full-document contents, or only selection plus on-disk file?
- Which exact app-server operation supplies authoritative before-content for native diffs?
- Root `README.md` required by repository instructions is absent; no additional context found.
