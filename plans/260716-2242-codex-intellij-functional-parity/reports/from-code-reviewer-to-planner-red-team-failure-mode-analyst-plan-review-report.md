# Red-Team Failure-Mode Plan Review

## 1. The empty repository cannot execute the first Gradle gate

- **Severity:** High
- **Location:** Phase 1 bootstrap inventory and success criteria.
- **Flaw:** The repository is explicitly empty, but the phase creates only `gradle-wrapper.properties`; it does not create `gradlew`, `gradlew.bat`, or `gradle-wrapper.jar`. Every phase nevertheless assumes `./gradlew` is runnable. The plan's “exact file” inventory therefore cannot produce its own first acceptance command.
- **Concrete failure scenario:** A clean checkout reaches Phase 1 verification and `./gradlew test` fails with `No such file or directory`. A developer works around it with a machine-installed Gradle, silently defeating the declared deterministic wrapper/toolchain contract.
- **Evidence:** The source report says the repo is empty (`plans/reports/260716-2232-codex-intellij-functional-parity.md:127`). Phase 1 lists only `gradle/wrapper/gradle-wrapper.properties` for the wrapper (`plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:36`) but gates completion on `./gradlew` (`plans/260716-2242-codex-intellij-functional-parity/phase-01-start.md:81`).
- **Suggested fix:** Add the complete wrapper artifact set to Phase 1 ownership, pin a compatible Gradle distribution and SHA-256, and add a clean-checkout `./gradlew --version` gate before schema or plugin tasks.

## 2. Full thread reads can roll state backward after newer notifications

- **Severity:** Critical
- **Location:** Phase 3 controller reconciliation; Phase 8 restart reconciliation.
- **Flaw:** Notifications are serialized through the reducer, while RPC-returned thread snapshots are “reconciled” separately. No response watermark, epoch, revision, or monotonic merge rule is defined. “Completed item wins” protects only one item from late deltas; it does not protect a completed turn/thread from a stale full snapshot.
- **Concrete failure scenario:** `thread/read` starts while a turn is running. `turn/completed` is reduced before the read response arrives. The older read response is then reconciled and restores the turn to running, re-enables Stop/Steer, and can trigger a queued follow-up against the wrong expected turn. The same race is especially likely during multi-thread restart recovery.
- **Evidence:** The controller both consumes serial gateway events and reconciles returned snapshots (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:27`), but its permutation tests cover event order rather than RPC-snapshot/event races (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:57`). Recovery explicitly performs partial/full reads across multiple panels and children (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:62`).
- **Suggested fix:** Route RPC results through the same sequencer as notifications and attach `(processEpoch, requestStartSequence)` metadata. Define field-level monotonic merge rules: terminal turn/item states cannot regress, IDs deduplicate, and a snapshot may fill missing history but cannot overwrite state advanced after its request watermark. Add delayed-read-after-completion and delayed-old-epoch-read tests.

## 3. Approval “fail closed” conflates local closure with a response received by the server

- **Severity:** Critical
- **Location:** Phase 5 approval coordinator; Phase 8 disconnect/disposal.
- **Flaw:** The planned state machine has one terminal transition but does not distinguish `DecisionChosen`, `ResponseSending`, `ResponseSent`, `ServerResolved`, `TransportLost`, and `OutcomeUnknown`. It also keys approvals by server request plus origin but not process epoch. On disconnect or disposal, the plugin cannot truthfully claim a deny/cancel reached the server, and a late old-epoch callback can collide with an ID reused by a restarted server.
- **Concrete failure scenario:** The user clicks Deny as the process connection drops. The UI disables the banner and `failAll` marks it terminal, but the write never reaches app-server. After restart, a new server request reuses the same JSON-RPC ID; a queued `serverRequest/resolved` callback from the old epoch closes the new prompt, or an old response continuation answers the wrong request.
- **Evidence:** The coordinator key omits process epoch (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:27`), and its interface collapses decision, resolution, and mass failure into one terminal-response rule (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:50`). Recovery promises to freeze approvals and never reuse old request IDs, but cannot control server-originated ID reuse (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:54`).
- **Suggested fix:** Make `(processEpoch, serverRequestId)` the primary key and model send/ack/resolution separately. Introduce the epoch in Phase 2 through one serialized `Stopped/Starting/Ready/Stopping/Disposed` lifecycle actor; restart must await old-process termination and disposal must be terminal. Shutdown ordering should stop accepting decisions, attempt bounded denial only while the transport is writable, then mark unresolved requests `OutcomeUnknown/ConnectionClosed` without claiming server denial. Reject all old-epoch callbacks and test ID reuse across restart.

## 4. Backpressure can either deadlock stdout or drop authoritative control events

- **Severity:** High
- **Location:** Phase 2 transport, Phase 3 reducer, Phase 8 centralized policy.
- **Flaw:** The plan bounds individual records and coalesces deltas, but never specifies the capacity and overflow policy between process output, parsing, event dispatch, reducers, and EDT snapshots. Central backpressure is postponed to Phase 8 even though every earlier runnable slice streams untrusted output. A generic bounded `Flow`/channel cannot safely treat deltas, request/response correlation, approvals, and completions alike.
- **Concrete failure scenario:** A command floods stdout while the EDT is slow. A suspending event channel stops draining the child process, its stdout pipe fills, and the app-server cannot emit the completion or approval that would unblock the turn. If the channel instead drops oldest/latest globally, it may discard `item/completed`, `serverRequest/resolved`, or a JSON-RPC response and leave the UI permanently running/pending.
- **Evidence:** Phase 2 promises bounded stderr and records but no event-queue contract (`plans/260716-2242-codex-intellij-functional-parity/phase-02-app-server-lifecycle-and-capabilities.md:22`); Phase 3 merely says events enter the reducer serially and deltas are bounded/coalesced (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:27`, `plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:58`). The centralized policy is deferred until Phase 8 (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:35`).
- **Suggested fix:** Move the policy into Phase 2. Always drain stdout on a dedicated reader; make responses, server requests, lifecycle, errors, and terminal events non-droppable; coalesce only replaceable keyed deltas; bound retained payload bytes independently from event count; and expose truncation. Stress a blocked EDT plus output flood while an approval and completion arrive.

## 5. Oversized or partial JSONL has no defined resynchronization rule

- **Severity:** High
- **Location:** Phase 2 `JsonlFramer`; Phase 8 crash/oversize fixtures.
- **Flaw:** The plan requires a size cap and continued streaming after malformed input “when safe,” but does not define how the framer discards the remainder of an oversized record, handles an EOF-truncated record, or resets decoder state between process epochs. Merely clearing the buffer at the cap makes the suffix of the same line look like a new message; retaining it defeats the cap.
- **Concrete failure scenario:** A record exceeds the limit before its newline. The implementation clears the buffer, then receives a suffix that happens to be valid JSON and dispatches it as an authentic server event. Alternatively, a process dies mid-multibyte character and restart reuses decoder state, corrupting the first message of the new process.
- **Evidence:** `accept`/`finish` are required to handle EOF and a size cap (`plans/260716-2242-codex-intellij-functional-parity/phase-02-app-server-lifecycle-and-capabilities.md:51`), while malformed lines should continue only “when safe” (`plans/260716-2242-codex-intellij-functional-parity/phase-02-app-server-lifecycle-and-capabilities.md:78`). Partial JSONL is an explicit failure-test requirement in the research (`plans/reports/260716-2240-parity-test-research.md:63`), but Phase 8's stress step mentions malformed/oversized streams without a resync invariant (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:63`).
- **Suggested fix:** Specify incremental UTF-8 decoder state per epoch plus `Normal` and `DiscardUntilNewline` modes. An oversized line emits exactly one diagnostic and discards every byte through its delimiter; EOF with buffered data emits a truncated-record diagnostic and never decodes it; restart creates a fresh framer. Test delimiter splits, CRLF, multibyte cap boundaries, and valid-looking suffixes.

## 6. Queue sending has an unhandled “accepted but response lost” state

- **Severity:** High
- **Location:** Phase 3 follow-up queue; Phase 8 recovery.
- **Flaw:** The queue has `sendNext`, but no durable per-entry transition or atomic relationship between dequeue and `turn/start`. Recovery retains queued drafts while prohibiting replay of mutating calls. Those rules are insufficient when the server accepted `turn/start` but the response was lost: the client cannot know whether the head entry is unsent or already running.
- **Concrete failure scenario:** A completed turn triggers `sendNext`; app-server accepts the new turn and crashes before the response reaches the client. On restart, keeping the item queued risks sending it twice; removing it before the response risks losing it if the request never arrived. A duplicate/late completion can also invoke `sendNext` twice unless queue advancement is separately idempotent.
- **Evidence:** Queue operations include `sendNext` and are only described as panel-local client state (`plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:52`, `plans/260716-2242-codex-intellij-functional-parity/phase-03-conversation-state-and-native-chat.md:59`). Recovery retains queued drafts but forbids mutating replay (`plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:28`, `plans/260716-2242-codex-intellij-functional-parity/phase-08-resilience-parity-audit-and-documentation.md:99`).
- **Suggested fix:** Define queue states `Draft/Dispatching/Acknowledged/OutcomeUnknown/Consumed` with a local dispatch token and one serialized queue actor. After connection loss, reconcile the thread before advancing; if no protocol-supported idempotency key proves the request outcome, surface `OutcomeUnknown` and require explicit resend/discard rather than auto-replay. Test response loss after server acceptance and duplicate completion triggers.

## 7. The plan asserts trustworthy “before” diff content without identifying a source

- **Severity:** High
- **Location:** Phase 5 patch/VFS/diff architecture.
- **Flaw:** Capturing content “before async VFS refresh” does not mean capturing content before app-server changed the disk. Refresh is only when IntelliJ learns about a mutation that has already happened. The research explicitly leaves the authoritative before-content operation unresolved, but the phase assumes `PatchSnapshotStore` can retain that attribution.
- **Concrete failure scenario:** The app-server modifies a file on disk while IntelliJ has an unsaved document. The plugin receives a patch event, reads the document as “before,” refreshes VFS, and presents a diff that mixes the user's unsaved changes with Codex's disk changes or attributes unrelated edits to the turn. For a rename/delete, there may be no remaining file from which to capture the old side.
- **Evidence:** Phase 5 requires before-content capture prior to refresh (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:23`) and claims the patch store retains authoritative diffs and before attribution (`plans/260716-2242-codex-intellij-functional-parity/phase-05-execution-approvals-review-and-diff.md:27`). The platform research says VFS refresh follows app-server disk changes (`plans/reports/260716-2240-intellij-platform-research.md:69`) and explicitly leaves the authoritative before-content source unresolved (`plans/reports/260716-2240-intellij-platform-research.md:149`).
- **Suggested fix:** Resolve this before Phase 5: either reconstruct both sides from the authoritative patch/diff payload, or capture a per-path baseline before the turn with document/disk modification stamps. Never silently substitute a post-mutation VFS read. If neither source is trustworthy, show the server's unified diff with an “unavailable before snapshot” warning and block authorship claims. Add unsaved-buffer, external-edit, rename, delete, and conflict race tests.

## Status

**DONE_WITH_CONCERNS** — 7 Critical/High failure modes found; no plan files were modified.

## Summary

The plan has strong happy-path decomposition, but its safety claims are ahead of its state-machine contracts. The release-blocking gaps are stale snapshot reconciliation and approval behavior across transport epochs. Process lifecycle serialization, non-droppable event backpressure, JSONL resynchronization, queue ambiguity, and trustworthy diff baselines should move into the phases that first depend on them rather than being deferred to Phase 8 hardening.

## Concerns

- Phase gates claim runnable vertical slices before foundational lifecycle/backpressure invariants exist.
- Several “exactly once” behaviors are only local UI transitions; transport loss creates outcome ambiguity that the models do not represent.
- The diff flow can misattribute filesystem changes unless its evidence contract is made explicit.
- Root `README.md` was unavailable as expected for this greenfield repository; the review used the complete plan and all four named research reports.
