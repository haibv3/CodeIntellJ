# Red-Team Plan Review: Assumption Destroyer / Scope Auditor

Review scope: `plan.md`, phases 1–8, and the four supporting reports. This is a plan-contract review only; no implementation, lint, build, or test work was performed.

## Findings

### 1. The schema pin has no executable DTO/code-generation contract

- **Severity:** High
- **Exact location:** Phase 1 requirements and file inventory; Phase 2 protocol adapter/model inventory.
- **Flaw:** Phase 1 says generated wire DTOs must not enter UI packages, but its deliverables contain only aggregate JSON schema bundles and a manifest. It defines no generator, generator version, Kotlin output tree, source-set wiring, schema-validation library, or check task for every used request/response. Phase 2 then introduces handwritten `ProtocolModels.kt`. The plan therefore leaves the most important choice—generated wire types versus systematically schema-validated handwritten types—unstated.
- **Concrete failure scenario:** The 0.144.5 schema marks a field required or uses an enum/tag shape omitted by the handwritten model. Curated JSONL fixtures still pass, the manifest hash is correct, and the plugin ships a request the pinned server rejects or decodes a valid event as malformed.
- **Evidence:** Phase 1 promises schema classification and generated-wire separation but lists only schema JSON/manifest artifacts (`plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:21`, `plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:42`); Phase 2 schedules handwritten protocol models and an adapter (`plans/260716-2242-codex-intellij-functional-parity/phase-02-app-server-lifecycle-and-capabilities.md:37`); the research recommendation explicitly calls for generated wire DTOs (`plans/reports/260716-2240-app-server-capability-research.md:71`).
- **Suggested fix:** Before implementation, choose one enforceable contract. Either add a pinned generator, generated source tree, Gradle source-set/check wiring, and regeneration-diff policy, or declare handwritten wire DTOs and validate every encoded/decoded used method against the checked-in per-type schemas in CI. List the resulting files/tasks in Phase 1 and make Phase 2 depend on that gate.

### 2. Worktree implementation reverses the research scope gate

- **Severity:** High
- **Exact location:** Phase 7 target requirements/implementation versus parity-test VS-08 and v1 boundary.
- **Flaw:** The research says desktop-style worktree management is out of v1 unless the app-server exposes it, and its acceptance gate requires schema-proven host support. The app-server report states there is no worktree method. Phase 7 nevertheless commits to a client-owned create/select workflow after only Git/path/confirmation checks. That is a new workspace-management product, not proven IDE functional parity.
- **Concrete failure scenario:** The plugin creates a branch/worktree with semantics different from Codex’s documented `/worktree`, encounters a dirty repository or branch collision, and leaves an orphaned worktree. Registry and smoke tests still label `/worktree` supported because a new thread starts with that cwd.
- **Evidence:** Phase 7 commits to a public “client-owned” worktree workflow (`plans/260716-2242-codex-intellij-functional-parity/phase-07-agents-and-advanced-execution-targets.md:23`) and explicit creation/collision handling (`plans/260716-2242-codex-intellij-functional-parity/phase-07-agents-and-advanced-execution-targets.md:63`); the validation research requires schema-proven host support and otherwise excludes desktop worktree management (`plans/reports/260716-2240-parity-test-research.md:35`, `plans/reports/260716-2240-parity-test-research.md:50`); app-server research says no worktree method exists (`plans/reports/260716-2240-app-server-capability-research.md:64`).
- **Suggested fix:** Resolve this product decision before Phase 7. Either keep `/worktree` visible-disabled under the original gate, or explicitly approve a separately named client-owned Git workflow and specify exact create/select/branch/cleanup/ownership/recovery semantics. Do not count that substitute as exact `/worktree` parity.

### 3. Native diff assumes authoritative before-content that research leaves unresolved

- **Severity:** High
- **Exact location:** Phase 5 patch snapshot/native diff architecture and IntelliJ research unresolved questions.
- **Flaw:** Phase 5 claims `PatchSnapshotStore` retains authoritative app-server diffs and before-content attribution, and that `NativeDiffService` captures “before” before VFS refresh. The research never establishes which app-server message supplies complete before-content. A patch/hunk or aggregate diff is not necessarily a reconstructable original file, especially with unsaved editor buffers or concurrent external changes.
- **Concrete failure scenario:** Codex edits a file while the user has an unsaved buffer. The plugin snapshots disk after the process write or reconstructs from a partial patch, then presents a native two-sided diff whose left side never existed and attributes unrelated user edits to Codex.
- **Evidence:** The platform research explicitly leaves the authoritative before-content operation unresolved (`plans/reports/260716-2240-intellij-platform-research.md:149`); Phase 5 nevertheless assigns before-content attribution to the patch store (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:27`) and requires native diff to capture before-content (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:54`).
- **Suggested fix:** Add a Phase 5 entry gate requiring a captured 0.144.5 trace and mapping for before/after sources. Define precedence among server patch, pre-turn document snapshot, disk, and unsaved document; define rename/delete/conflict behavior. If no authoritative before side exists, show the server patch/diff as such and do not claim native two-sided attribution.

### 4. A fresh unauthenticated user has no planned account login lifecycle

- **Severity:** High
- **Exact location:** Plan runtime/smoke assumptions and Phase 6 account surface.
- **Flaw:** The plan correctly avoids plugin-owned credentials, but it silently assumes the user-installed CLI is already authenticated. The app-server research says account login/cancel/logout/read are stable. Phase 6 lists `AccountController` but specifies only account/status rendering; the only concrete login flow is MCP OAuth. No signed-out state, account login URL/device flow, cancellation, logout, expiry, or auth-required retry is in the interfaces, tests, or sandbox criteria.
- **Concrete failure scenario:** On a fresh machine, the app-server initializes but `turn/start` fails for missing authentication. The Tool Window can display compatibility and status, yet there is no native path to authenticate or a documented precondition, so the headline fresh prompt-to-diff smoke cannot start.
- **Evidence:** The root plan requires a fresh sandbox prompt flow while declaring no plugin-owned auth (`plans/260716-2242-codex-intellij-functional-parity/plan.md:44`, `plans/260716-2242-codex-intellij-functional-parity/plan.md:51`); Phase 6 specifies MCP OAuth but no account-auth lifecycle (`plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:24`, `plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:65`); the capability report identifies account login/cancel/logout/read as stable (`plans/reports/260716-2240-app-server-capability-research.md:36`).
- **Suggested fix:** Either make pre-authenticated Codex CLI an explicit setup prerequisite and test the signed-out error, or add a server-backed account controller contract for read/login/cancel/logout/expiry and auth-required retry. Add sanitized fixtures and a sandbox journey from signed out → login/cancel → chat, without storing credentials.

### 5. “Exactly 22 routes” is being used as a proxy for slash-command semantics

- **Severity:** High
- **Exact location:** Phase 6 registry/handlers and parity research unresolved methods.
- **Flaw:** Registry tests establish names and typed destinations, not behavior. Phase 6 promotes `/goal`, `/feedback`, and `/init` to stable/core handlers even though research still asks which methods implement goal pause/resume/edit/clear and whether feedback upload is the correct public flow. `/init` is described as a normal typed turn with a “documented scaffold intent,” but no versioned intent text, file overwrite contract, or response oracle is pinned.
- **Concrete failure scenario:** `/goal` supports get/set/clear and passes its route test, but the documented IDE flow’s pause/resume/edit behavior is absent. `/init` sends an approximate prompt and overwrites/creates a different file shape. The 22-command tour passes while functional parity is false.
- **Evidence:** Phase 6 declares these commands stable/core and plans dialogs without per-command semantic traces (`plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:54`, `plans/260716-2242-codex-intellij-functional-parity/phase-06-settings-mcp-and-slash-commands.md:63`); parity research explicitly leaves goal and feedback contracts unresolved (`plans/reports/260716-2240-parity-test-research.md:93`, `plans/reports/260716-2240-parity-test-research.md:94`); the acceptance matrix requires a broader goal lifecycle (`plans/reports/260716-2240-parity-test-research.md:37`).
- **Suggested fix:** Add a versioned semantic contract per command: preconditions, arguments, exact request sequence, user-visible state transition, side effects, cancellation/error behavior, and captured fixture/manual citation. Mark unresolved commands visible-disabled until that contract exists. Keep the inventory test, but do not use it as the parity gate.

### 6. Context “preview equals payload” is not mapped to the actual wire schema

- **Severity:** High
- **Exact location:** Phase 4 snapshot and assembler contract.
- **Flaw:** `ContextSnapshot` includes path, source kind, offsets/ranges, modification stamp, language hint, text, truncation, and unsaved state, and the plan says the transmitted typed input is the same immutable value. No field-by-field mapping to a 0.144.5 turn-input variant is supplied. Some fields may be UI-only; serializing them into text would be prompt emulation, while dropping them makes the equality claim false.
- **Concrete failure scenario:** The preview shows an unsaved file path/range/stamp, but the wire encoder sends only a text input. A unit test comparing `ContextSnapshot` to a pre-encoding assembler object passes even though the app-server receives unlabeled text or a synthetic prompt wrapper.
- **Evidence:** Phase 4 defines the rich snapshot and equality requirement (`plans/260716-2242-codex-intellij-functional-parity/phase-04-ide-context-and-editor-actions.md:22`) and repeats it at the assembler boundary (`plans/260716-2242-codex-intellij-functional-parity/phase-04-ide-context-and-editor-actions.md:49`); the research only establishes local atomic capture, not wire support for those fields (`plans/reports/260716-2240-intellij-platform-research.md:66`).
- **Suggested fix:** Add a checked-in mapping table from every snapshot field to an exact pinned `turn/start` input schema field, explicitly marking UI-only metadata. Test the final encoded JSON at the gateway boundary against schema and a golden. Define equality as exact user-visible content plus clearly disclosed transport metadata, not Kotlin object identity before encoding.

### 7. The full-product Driver smoke has no runnable harness or automated oracle

- **Severity:** Medium
- **Exact location:** Phase 8 driver test inventory and release gates.
- **Flaw:** Phase 8 lists `CodexDriverSmokeTest.kt`, but no phase defines Driver dependencies, a separate UI-test source set/task, IDE launch/control configuration, test server/plugin setup, fixture process injection, or log collection. `runIde` is an interactive launch task, not proof that a test class ran. “Zero EDT/internal-mode errors” likewise has no log/assertion oracle.
- **Concrete failure scenario:** `./gradlew test` excludes or cannot connect the Driver test, while `runIde` opens successfully. G5 is marked complete after a manual glance even though the multi-panel, approval, restart, and delegated-child smoke never executed and EDT errors remain in `idea.log`.
- **Evidence:** Phase 8 creates a Driver test and declares its long workflow (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:44`, `plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:58`) but its gates are only standard `test` plus interactive `runIde` (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:91`, `plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:94`); platform research says full-product Driver tests should cover critical smoke flows but does not supply the missing harness (`plans/reports/260716-2240-intellij-platform-research.md:127`).
- **Suggested fix:** Add the exact pinned Driver test dependency/plugin, dedicated source set and Gradle task, sandbox launch task, fake/real app-server injection, timeout/artifact handling, and `idea.log` assertions. Separate automated gates from a named manual checklist, and require both explicitly if both are intended.

Status: DONE_WITH_CONCERNS

Summary: Seven evidence-backed scope/assumption defects found. The plan is structurally detailed, but it overstates parity at unresolved schema-to-domain, command-semantic, auth, worktree, diff-attribution, context-wire, and test-harness boundaries.

Concerns: Worktree support contradicts the approved validation boundary; native diff attribution lacks an authoritative before-content source; fresh-login and full-product test paths are not executable as written. These should be resolved before Phase 1 freezes contracts.
