# System Architecture

## Ownership

| Concern | Owner |
|---|---|
| Binary trust / env preview | `CodexBinaryTrustPolicy` |
| Process epoch / launch | `AppServerLifecycleActor` |
| Wire framing / RPC | `JsonlFramer`, `JsonRpcTransport` |
| Sequencing / backpressure | `ProtocolSequencer`, `BackpressurePolicy` |
| Capabilities | `CapabilityRegistry` |
| Normalized server facts | `ConversationReducer` via `ServerStateStore` |
| Bounded UI delivery | panel-owned `UiStateBridge`; latest immutable snapshot only |
| Transcript identity/diff | `TranscriptBlock` / `TranscriptBlockReconciler` |
| Transcript materialization | `TranscriptViewportWindow`; `CodexChatPanel` owns live components |
| Snapshot merge | `ConversationMergePolicy` / `ConversationController` |
| Panel draft/queue | `ChatPanelModel` / `FollowUpQueueActor` |
| Approvals | `ApprovalStateMachine` keyed by epoch+fingerprint |
| Patch facts | reducer only; `PatchProjection` is pure |
| Diff baselines | `DiffBaselineCache` (local pre-turn only) |

## Data flow

Trusted binary → lifecycle actor → stdout drain → framer → adapter → sequencer → conversation controller/reducer → lossless `ServerStateStore` → bounded `UiStateBridge` → UI projections.

Requests reverse through capability guard → transport writer → pending map → sequencer responses.

## State-to-view contract

- `ServerStateStore` remains authoritative and notifies once per accepted event; UI optimization never drops or reorders reducer input.
- Each workspace or standalone chat owns one `UiStateBridge`. Embedded chat is parent-fed and never self-subscribes.
- The bridge keeps only the newest replaceable snapshot, schedules at most one callback, and invalidates `tasks`, `agents`, `title`, `busy`, and `transcript` independently.
- `TranscriptRenderer` emits semantic block IDs and deterministic revisions. `TranscriptBlockReconciler` plans keyed keep/update/insert/move/remove operations in linear space and time.
- `TranscriptViewportWindow` retains all immutable block models but materializes a profile-tuned target of 40 Swing blocks, with a hard cap of 250, bounded height/HTML caches, and top/bottom spacers.
- One-line plain agent messages use a recyclable custom-painted native row. Markdown, links, code fences, and structured content keep their HTML/editor/card hosts.
- `CodexChatPanel` exclusively owns live transcript components. It recycles compatible plain rows and disposes HTML/editor/card hosts when their keyed block leaves the materialized window.
