---
phase: 2
title: "App Server Lifecycle and Capabilities"
status: completed
priority: P1
dependencies: [1]
---

# Phase 2: App Server Lifecycle and Capabilities

## Overview

Implement the trusted stdio lifecycle, epoch-keyed JSONL/JSON-RPC transport, single sequencing lane, drain-first backpressure, capability negotiation, and redaction at ingestion. Runnable result: Tool Window safely connects/restarts real or fake 0.144.5 and exposes epoch/capability/diagnostic status.

## Context Links

- [Phase 1 contract](./phase-01-start.md); [app-server lifecycle](../reports/260716-2240-app-server-capability-research.md); [platform process APIs](../reports/260716-2240-intellij-platform-research.md)
- Gate: Phase 1 trust, wrapper, full-tree manifest, and method-schema mapping tests green.

## Requirements

- Lifecycle: `AppServerLifecycleActor` alone launches/stops/restarts and increments opaque `ProcessEpoch`; it revalidates `CodexBinaryTrustPolicy` immediately before `GeneralCommandLine`, uses the previewed environment, and performs initialize→initialized exactly once per epoch.
- Identity: all requests/responses/events carry epoch. Server requests use `ServerRequestKey(epoch,id)` plus canonical payload fingerprint; old-epoch callbacks cannot respond in a new process.
- Sequencing: every RPC response, notification, server request, and requested snapshot enters one `ProtocolSequencer`; it assigns arrival sequence and request watermark. Control/terminal/response events are non-droppable; only keyed text/output/diff deltas coalesce.
- Framing/backpressure: stdout is always drained off EDT. `JsonlFramer` has per-epoch `Normal`/`DiscardUntilNewline`; an oversized record emits one diagnostic and discards through newline; partial bytes at EOF emit truncated-frame diagnostic and are never decoded.
- Wire: encode/decode only handwritten minimal DTOs proven by Phase 1 method map; unknown fields/methods/items remain sanitized `JsonObject` envelopes. Stable default; X false until opt-in/probe.
- Diagnostics: create `StructuredDiagnosticEvent`, `RedactionPolicy`, secret corpus, and `RedactedBundle` here. Redact before any ring buffer/persistence/UI/export; raw stdout/stderr/payload content is never retained.

## Architecture

Trusted binary → `AppServerLifecycleActor` (epoch owner) → `OSProcessHandler`. Stdout chunks → epoch framer → JSON parser/minimal DTO adapter → `ProtocolSequencer` → ordered gateway stream. Requests reverse through capability + exact schema-map guard, serialized writer, epoch pending map, then sequencer. `BackpressurePolicy` keeps non-droppable event kinds and per-key latest delta. `DiagnosticRingBuffer` accepts only already-redacted structured events.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/ProcessEpoch.kt` | 45 LOC | epoch type/key guards |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/AppServerGateway.kt` | 110 LOC | sole session boundary |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/AppServerLifecycleActor.kt` | 230 LOC | launch/restart epoch ownership |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/JsonlFramer.kt` | 130 LOC | Normal/discard/EOF tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/JsonRpcTransport.kt` | 220 LOC | epoch pending/writer tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/ProtocolAdapter.kt` | 220 LOC | method-map/JsonObject boundary |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/WireModels.kt` | 150 LOC | minimal handwritten DTOs |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/ProtocolSequencer.kt` | 190 LOC | arrival/watermark ordering |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/BackpressurePolicy.kt` | 120 LOC | non-drop/coalescing rules |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/StructuredDiagnosticEvent.kt` | 90 LOC | allow-listed diagnostics |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/RedactionPolicy.kt` | 170 LOC | ingress redaction/feedback bundle |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/DiagnosticRingBuffer.kt` | 100 LOC | sanitized-only bounded storage |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/session/CapabilityRegistry.kt` | 140 LOC | stable/X/G gating |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/CodexProjectService.kt` | +80 LOC | owns lifecycle/gateway |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CompatibilityPanel.kt` | +90 LOC | epoch/restart UI |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/handshake-epochs.jsonl` | 50 lines | handshake/restart golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/0.144.5/oversized-eof-unknown.jsonl` | generated | framer/backpressure golden |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/security/diagnostic-secret-corpus.json` | reviewed | redaction adversary corpus |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/JsonlFramerTest.kt` | 190 LOC | chunk/state/EOF matrix |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/AppServerLifecycleTest.kt` | 260 LOC | fake executable/epochs/trust |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/ProtocolSequencerTest.kt` | 240 LOC | watermark/non-drop/coalesce |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/RedactionPolicyTest.kt` | 200 LOC | pre-storage secret tests |

## Functions and Interfaces Checklist

- [x] `ProcessEpoch` is monotonic/opaque per project service; `ServerRequestKey(epoch, id)` and `SequencedEvent(epoch, arrivalSeq, requestWatermark, payload)` cross the gateway.
- [x] `AppServerLifecycleActor.start/stop/restart/dispose` serializes `Stopped → Starting → Ready → Stopping → Stopped` with terminal `Disposed`; no other class increments epochs/owns handler; restart awaits old-process termination, fails pending requests, and never reuses IDs.
- [x] `JsonlFramer.accept(epoch, bytes)` state is reset per epoch; oversize enters `DiscardUntilNewline`; `finish(epoch)` never emits partial JSON.
- [x] `JsonRpcTransport.request/notify/respond(ServerRequestKey, fingerprint, body)` verifies current epoch/fingerprint, exact mapped schema, one writer, and pending ownership.
- [x] `ProtocolSequencer.enqueue` never drops response/server-request/turn-terminal/item-terminal/control events; coalesces only `(epoch, thread, turn, item, deltaKind)` latest deltas while stdout drain continues.
- [x] `RedactionPolicy.redact(StructuredDiagnosticEvent): StructuredDiagnosticEvent`; `DiagnosticRingBuffer.append` accepts only redacted type; `bundle(range): RedactedBundle(bytes, sha256, eventCount)` operates only on sanitized events.
- [x] `CapabilityRegistry.require(method)` combines pinned map class, initialize result, probe/account/platform/X opt-in; absent method yields local unavailable without wire traffic.

## Implementation Steps

1. Write framer tests across every byte split, UTF-8 split, CRLF, multiple lines, oversize discard/resume, epoch reset, malformed JSON, and EOF partial record.
2. Implement lifecycle actor with exec-time trust revalidation, explicit environment, `OSProcessHandler.startNotify()`, independent stdout drain, stderr-to-redacted-diagnostics, and deterministic stop/restart/epoch transitions.
3. Implement transport pending map/writer and single sequencer. Assign request watermark at send; tag response/snapshot; prove non-droppable ordering and keyed-delta coalescing under a stalled consumer.
4. Implement minimal DTO adapter backed by exact Phase 1 method map and full-tree validation; retain unknowns as bounded sanitized `JsonObject`; never infer unsupported methods.
5. Implement structured ingress redaction, bounded sanitized ring buffer, `RedactedBundle`, and secret corpus before any UI/export/feedback consumer exists.
6. Connect capability/epoch/restart status UI; test real and injected fake 0.144.5 startup, version mismatch, binary change confirmation, and no EDT blocking.

## Todo

- [x] Complete epoch lifecycle, framer, transport, and single sequencer.
- [x] Enforce non-droppable/control and keyed-delta backpressure policy.
- [x] Complete handwritten wire adapter and capability guard.
- [x] Complete pre-storage diagnostics redaction and secret corpus.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Restart with same numeric request ID | new epoch key differs; old callback/response rejected |
| Critical | Slow consumer + high-rate deltas/control terminals | stdout drained; deltas coalesce by key; no control/terminal/response loss |
| Critical | Oversized record then valid line / partial EOF | discard to newline then recover; EOF partial only diagnostic |
| Critical | Binary changes after confirmation | launch stops before exec and asks confirmation |
| High | Unknown/malformed/wrong-schema payload | bounded sanitized unknown/error; sequencer continues safely |
| High | Secret in stdout/stderr/error/unknown JSON | absent before diagnostic buffer/UI/bundle |
| Medium | X method with opt-in false | local unavailable; zero wire bytes |

## Success Criteria

- [x] `./gradlew test --tests '*JsonlFramerTest' --tests '*AppServerLifecycleTest' --tests '*ProtocolSequencerTest' --tests '*RedactionPolicyTest'`
- [x] `./gradlew verifyProtocolContract verifyPluginProjectConfiguration verifyPluginStructure`
- [x] `./gradlew runIde` connects/restarts with visible epochs and survives overload/EOF without EDT warnings or secret-bearing diagnostics.

## Risk Assessment / Security

Epoch confusion can answer a new process with stale authority; every pending/server request and callback is epoch-keyed. Drain-first parsing prevents child deadlock; only keyed deltas may collapse. An unbounded control storm remains a process-fail condition with an actionable diagnostic, never silent drop. Redaction occurs before storage; raw payloads/stderr are ephemeral. Trust policy/env preview is rechecked immediately before launch.

## Dependency Map / Next Steps

Requires all Phase 1 gates. Phase 3 consumes only sequenced epoch/watermark events and gateway methods; Phase 5 uses `ServerRequestKey`/fingerprints; Phase 6 feedback consumes only `RedactedBundle`.
