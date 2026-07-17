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
| Snapshot merge | `ConversationMergePolicy` / `ConversationController` |
| Panel draft/queue | `ChatPanelModel` / `FollowUpQueueActor` |
| Approvals | `ApprovalStateMachine` keyed by epoch+fingerprint |
| Patch facts | reducer only; `PatchProjection` is pure |
| Diff baselines | `DiffBaselineCache` (local pre-turn only) |

## Data flow

Trusted binary → lifecycle actor → stdout drain → framer → adapter → sequencer → conversation controller/reducer → UI projections.

Requests reverse through capability guard → transport writer → pending map → sequencer responses.
