# Baseline performance

## Environment

| Field | Value |
|---|---|
| Date | 2026-07-18 |
| Project | Codex IntelliJ 0.1.1 |
| Runtime contract | Java 21, IntelliJ Platform 2026.1.4 |
| Standard workload | 8 agents, 5,000 events, 2,000 transcript blocks |

## Deterministic Evidence

| Measurement | Before bridge | After bridge |
|---|---:|---:|
| Store notifications | 5,000 | 5,000 |
| UI state offers | 5,000 | 5,000 |
| Merged UI offers | 0 | 4,999 |
| UI deliveries after synchronous burst | 5,000 | 1 |
| Pending UI delivery high-water | 5,000 | 1 |
| Final delivered arrival sequence | 5,000 | 5,000 |
| Agent facts | 8 | 8 |
| Item facts | 2,000 | 2,000 |
| Rendered transcript blocks | 2,000 | 2,000 |
| Fake app-server notifications | 5,000 | 5,000 |

The authoritative store remains lossless and is not a batching boundary. Every event still reaches listeners once. `UiStateBridge` now bounds the replaceable UI lane to one pending scheduled delivery and preserves the newest arrival sequence.

## TDD Evidence

- RED: fake wrapper process exited 2 because it resolved `src/src/test/...`.
- GREEN: wrapper `--version` returns `codex-cli 0.144.5`.
- RED: workload fixture threw because event generation was absent.
- GREEN: deterministic count/order/state/transcript tests pass.
- RED: opt-in fake server returned `-32601` for `thread/start`.
- GREEN: initialize/thread-start/turn-start replay emits 5,000 notifications and terminal event.
- RED: bridge tests failed for an unimplemented mailbox; ownership test found direct panel listeners.
- GREEN: latest-state, one-pending, selective-surface, standalone/embedded ownership and late-dispose tests pass.

## Runtime Capture Procedure

1. Run `CODEX_FIXTURE_SCENARIO=multi-agent-performance ./gradlew runIde`.
2. Configure the canonical fake binary at `src/uiTest/resources/fake-app-server/fake-codex.sh`.
3. Enable internal mode and JFR or async-profiler.
4. Start one turn; the opt-in fake server replays the standard workload.
5. Record EDT task duration, action latency, result visibility, scroll FPS, component count and retained heap.

## Unmeasured Baseline

The current automated harness does not prove real IDE EDT duration, paint/layout time, scroll FPS, action latency or retained heap. These remain runtime gates and must not be inferred from unit/UI process tests.

## Bottleneck Ranking

1. Proven amplification: 5,000 store events produce 5,000 listener notifications.
2. Resolved by deterministic test: workspace/standalone delivery is bounded to one scheduled bridge callback.
3. Baseline code evidence: transcript component apply measured HTML and mutated a growing component tree.
4. At baseline time, EDT/layout/paint/heap magnitude was unmeasured; the final capture below closes this gate.

## Incremental Reconciliation Evidence

| Scenario | Keep | Update | Insert | Remove |
|---|---:|---:|---:|---:|
| Append after 3 blocks | 3 | 0 | 1 | 0 |
| One middle revision in 2,000 blocks plus append | 1,999 | 1 | 1 | 0 |

- Reconcile planning uses one ID map plus linear prefix/suffix scans; duplicate and blank IDs fail fast.
- Renderer workload remains 2,000 semantic blocks with unique IDs.
- An unchanged native code fence keeps both ID and revision when surrounding prose grows, so its editor component is reused.
- HTML updates mutate the matching measured host in place; removed/replaced components alone are disposed.

## Bounded Viewport Evidence

| Measurement | Automated result |
|---|---:|
| Total semantic blocks | 2,000 |
| Profile-tuned target materialized blocks | 40 |
| Maximum host children (blocks + spacers + glue) | 43 |
| Hard materialization gate | 250 |
| Height cache capacity | 512 |
| Sanitized HTML cache capacity | 512 |

- Prefix/spacer totals, tail-follow, prepend anchor correction and reading-mode behavior pass deterministic tests.
- Scrollbar dragging defers component mutation and retains only the latest pending block model.
- Automated tests prove windowing invariants only; runtime measurements are recorded separately below.

## Final Runtime Evidence

The 10-minute `runIde` capture on IU-261.26222.65 completed the standard workload with 180.7 ms result visibility, 101.4 ms maximum EDT gap, 1.60 ms scroll-action p95, 62.1 FPS, and 40 maximum materialized blocks. Full GC measurements moved from 285,809 KiB before the workload to 292,404 KiB after it, about 6.6 MB retained growth. The final class histogram contained 40 recyclable plain-agent hosts and no live transcript HTML host.

The original target of 200 was reduced only after runtime profiles showed multi-second layout/reconciliation gaps at that component count. The hard regression cap remains 250; 40 covers the measured viewport plus overscan without introducing a virtual-flow rewrite.
