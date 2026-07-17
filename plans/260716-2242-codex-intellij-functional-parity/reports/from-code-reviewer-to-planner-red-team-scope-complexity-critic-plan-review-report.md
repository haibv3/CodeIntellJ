# Red-Team Plan Review: Scope & Complexity Critic / Contract Verifier

Review scope: `plan.md`, phases 1–8, and the four supporting reports. The accepted functional-parity scope is held. This review targets avoidable implementation complexity, duplicate ownership, phase coupling, inventory contradictions, and unverifiable contracts. No plan file, code, build, lint, or test was changed or run.

## Findings

### 1. Phase 1's "exact" file inventory contradicts its schema-bundle contract

- **Severity:** High
- **Exact location:** Phase 1 — Related Code Files and Implementation Step 3.
- **Flaw:** The inventory names exactly one aggregate schema file under each stable/experimental root, while the implementation step requires copying the entire generated trees. The supporting capability report relies on component artifacts such as `ClientRequest.json`, `ServerRequest.json`, and `ServerNotification.json`, but the phase neither inventories those files/directories nor defines whether the aggregate files replace them. The manifest's completeness boundary is therefore undefined.
- **Failure scenario:** Phase 1 passes by hashing only the two aggregate files. Phase 2 later needs a per-method schema cited by the research, discovers it was never committed, and either regenerates an unreviewed local tree or hand-codes against incomplete evidence. Another implementation could commit hundreds of files and still claim compliance because both interpretations fit the plan.
- **Evidence:** The only schema paths in the file table are the two aggregate JSON files (`plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:43`, `plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:44`), but the execution step says to copy the entire generated trees (`plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:59`); the research explicitly cites component schemas from those trees (`plans/reports/260716-2240-app-server-capability-research.md:15`).
- **Suggested fix:** Choose one artifact contract before Phase 1 starts. If the full tree is authoritative, inventory the two directory roots, define include/exclude rules, and require the manifest to enumerate every relative file and hash. If the aggregate files are authoritative, remove the full-tree claim and prove they contain every used request/response/notification definition. Keep the parity scope; remove the ambiguous duplicate representation.

### 2. Patch state has two competing authoritative owners

- **Severity:** High
- **Exact location:** Phase 3 — Conversation reducer contract; Phase 5 — PatchSnapshotStore architecture/checklist.
- **Flaw:** Phase 3 requires the serial `ConversationReducer` to reduce patch events and includes file/review items in its domain. Phase 5 then creates a second mutable `PatchSnapshotStore` that independently applies per-item patch updates and aggregate diffs and calls its data authoritative. The plan does not say which store receives an event first, which store owns terminal/completed precedence, or how they are atomically reconciled.
- **Failure scenario:** A per-item patch update reaches both stores, but an item completion or duplicate/late update is normalized only by `ConversationReducer`. The transcript shows the completed patch while the native diff reads a stale/evolving `PatchSnapshotStore`, so two views of the same turn disagree and attribution changes with event timing.
- **Evidence:** Phase 3 assigns patch reduction to the conversation state machine (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:21`) and establishes a single serial reducer/store flow (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:27`); Phase 5 separately declares authoritative app-server diff ownership and mutation methods (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:27`, `plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:52`).
- **Suggested fix:** Keep server-originated patch/diff facts in one serial state owner. Extend `ConversationSnapshot` with the required per-item and aggregate diff state, and make native diff consume an immutable derived `PatchSnapshot`. If local before-content needs caching, create a narrowly named baseline cache keyed by turn/item/path; it must not re-reduce server patch state.

### 3. AgentTreeReducer duplicates event reduction already promised by ConversationReducer

- **Severity:** High
- **Exact location:** Phase 3 — domain item/reducer boundary; Phase 7 — AgentTreeReducer architecture and interface.
- **Flaw:** Phase 3's domain explicitly covers collaboration and subagent items through the central `AppServerEvent` reducer. Phase 7 introduces separate `AgentModels` and a 230-LOC `AgentTreeReducer` that again joins thread, collaboration, and subagent events by IDs. No projection boundary or event fan-out ordering is defined, so parentage, activity, status, and errors become duplicated normalized state.
- **Failure scenario:** A late child-thread completion is idempotently absorbed by `ConversationReducer` but processed differently by `AgentTreeReducer`. The child transcript says Done while the agent tree remains Active; controls are enabled from one snapshot and executed against the other.
- **Evidence:** The central reducer accepts all app-server events (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:49`) and its domain includes collaboration/subagent items (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:53`); Phase 7 creates another reducer over the same thread/collaboration/subagent event families (`plans/260716-2242-codex-intellij-functional-parity/phase-07-agents-and-advanced-execution-targets.md:28`, `plans/260716-2242-codex-intellij-functional-parity/phase-07-agents-and-advanced-execution-targets.md:53`).
- **Suggested fix:** Preserve one normalized server-state reducer. Either extend `ConversationSnapshot` with authoritative parent/role/activity fields or derive `AgentTreeSnapshot` as a pure projection from it. Keep `AgentConfigDiscovery` as a separate advisory catalog, but never let advisory config or a second event reducer own live node status.

### 4. Deferred slash routing creates a 460-LOC handler switchboard with replace-in-place coupling

- **Severity:** High
- **Exact location:** Phase 6 — slash architecture/file inventory/checklist; Phase 7 — command-handler modification.
- **Flaw:** The registry stores metadata plus a `handlerId`, the dispatcher resolves it, Phase 6 creates a 280-LOC `CoreCommandHandlers.kt`, and Phase 7 adds another 180 LOC while "replacing" deferred handlers. This makes registry availability, handler binding, and implementation phase three separately mutable contracts. It also turns one file into the integration point for conversation, context, review, settings, MCP, targets, agents, and experimental APIs.
- **Failure scenario:** Phase 7 swaps the `/plan` handler but leaves Phase 6 registry availability or argument metadata unchanged. The command appears enabled with the old gate, reaches the new X handler with incompatible arguments, or retains a placeholder because a string-like handler ID was not rebound. Exact-22 tests still pass because inventory is correct.
- **Evidence:** Phase 6 explicitly plans handler replacement while retaining the registry (`plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:28`), separates `handlerId` metadata from dispatch (`plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:52`, `plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:53`), and budgets 280 LOC for core handlers (`plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:37`); Phase 7 adds 180 LOC to that same file (`plans/260716-2242-codex-intellij-functional-parity/phase-07-agents-and-advanced-execution-targets.md:42`).
- **Suggested fix:** Make each registry entry carry a typed sealed route specification, with availability and argument schema defined beside that route. Bind routes exhaustively to small domain handlers (`conversation`, `context`, `review`, `settings`, `mcp`, `targets`, `experimental`) rather than one core switchboard. Phase 6 may register gated route specs; Phase 7 supplies domain implementations without replacing metadata or editing an omnibus file.

### 5. RecoveryCoordinator crosses boundaries already owned by gateway, process manager, and conversation controller

- **Severity:** High
- **Exact location:** Phase 2 — gateway/lifecycle architecture; Phase 3 — reconciliation architecture; Phase 8 — recovery architecture/interface.
- **Flaw:** Phase 2 makes `AppServerGateway` the sole session-facing boundary and gives process exit/restart to the transport layer. Phase 3 gives snapshot reconciliation to `ConversationController`. Phase 8 then places a 210-LOC `RecoveryCoordinator` in `appserver` that fails calls, freezes approvals, retains UI drafts/thread IDs, restarts transport, invokes list/read/resume, and reconciles session state. It therefore needs inward knowledge of UI, approval, session, and transport concerns while duplicating their operations.
- **Failure scenario:** A reconnect action is initiated through the gateway while `RecoveryCoordinator` is already reconnecting. One path publishes Ready while the other is still freezing approvals or applying reads; drafts and thread IDs are retained in an app-server component that cannot own panel disposal. Fixing the race creates circular dependencies or service-locator calls through `CodexProjectService`.
- **Evidence:** Phase 2 declares the gateway the sole session-facing interface and assigns exit/restart behavior there (`plans/260716-2242-codex-intellij-functional-parity/phase-02-app-server-lifecycle-and-capabilities.md:27`); Phase 3 assigns returned-snapshot reconciliation to `ConversationController` (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:27`); Phase 8 gives all of those responsibilities plus approvals/drafts to `RecoveryCoordinator` (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:28`, `plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:54`).
- **Suggested fix:** Put process epochs and reconnect state transitions in the Phase 2 gateway/process owner, and keep authoritative read/merge logic in the Phase 3 conversation owner. If coordination is still useful, make it a thin project-level use case that calls those existing interfaces and emits no independent state; panel drafts remain owned by panel models and approvals react to connection epochs.

## Status

**DONE_WITH_CONCERNS** — five High-severity scope-and-complexity contract defects found; accepted parity scope remains unchanged and no plan files were edited.

## Summary

The plan can hold its full parity target without carrying multiple normalized stores and replacement seams. The most expensive rework risks are duplicate patch/agent state, a recovery component that crosses four ownership layers, and the phase-spanning command switchboard. The schema inventory also cannot currently prove that the promised protocol artifacts exist.

## Concerns

- Establish one owner for each mutable fact: conversation/patch/agent server state, panel-local state, process epoch, and recovery merge.
- Replace phase-later handler substitution with typed, exhaustive domain route bindings.
- Make file inventories describe the artifacts actually required.
- Root `README.md` is absent, consistent with the greenfield-repository note in `plans/reports/260716-2240-intellij-platform-research.md:150`.
