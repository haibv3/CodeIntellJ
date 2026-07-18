---
phase: 1
title: "Baseline và regression harness"
status: completed
priority: P1
dependencies: []
---

# Phase 1: Baseline và regression harness

## Context Links

- [Brainstorm đã duyệt](../reports/260718-1116-multi-agent-performance-ui-brainstorm.md)
- [System architecture](../../docs/system-architecture.md)
- `MultiAgentRenderScaleTest.kt`: projection complexity coverage hiện có.
- `CodexDriverSmokeTest.kt`: chỉ là file/fixture smoke; không phải FPS hoặc Driver interaction proof.

## Overview

Tạo workload tái lập, deterministic counters và runtime capture procedure trước khi đổi production behavior. Phase này xác nhận baseline tests đang xanh, nhưng phải tạo evidence cho khoảng trống hiện tại: event amplification, component growth, apply/layout cost và scroll behavior chưa được đo.

## Requirements

- Workload cố định 8 agent, 5.000 sequenced event và transcript 2.000 block.
- Fixture phải deterministic theo epoch, arrival sequence, thread/turn/item IDs và terminal ordering.
- CI gates dùng operation counts/state invariants; wall-clock chỉ là report, không phải assertion cứng.
- Runtime baseline ghi IDE/JDK/build, Light hoặc Darcula, viewport, heap, CPU và JFR capture settings.
- Fake app-server có opt-in `multi-agent-performance` scenario đọc cùng workload spec; default fixture behavior giữ nguyên.
- Không sửa protocol/reducer hoặc tối ưu production trong phase này.

## Architecture

```text
MultiAgentUiWorkload (test fixture)
      |-- shared JSON workload spec
      |-- SequencedEvent stream
      |-- expected state counts
      `-- expected transcript shape
             |
             +--> deterministic unit counters
             `--> opt-in fake app-server replay + runIde/JFR baseline
```

Fixture là test-only source of truth dùng lại trong Phase 2-4. Baseline report phân biệt rõ measured, inferred và unmeasured values.

## Related Code Files

| Action | Absolute path | Purpose |
|---|---|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiAgentUiWorkload.kt` | Shared deterministic workload fixture |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiAgentUiWorkloadTest.kt` | Fixture ordering/count contract |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/ui/MultiAgentRenderScaleTest.kt` | Record store notifications, projection visits và block shape |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/multi-agent-performance-spec.json` | Small shared count/seed/scenario contract |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/resources/fixtures/appserver/fake-codex-app-server.py` | Emit deterministic performance stream only when scenario opt-in is set |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/resources/fake-app-server/fake-codex.sh` | Fix repository-root resolution (`src/src/...` hiện tại bị sai) |
| Modify | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/FakeAppServerFixture.kt` | Expose scenario/spec paths for runtime and process tests |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/uiTest/kotlin/dev/haibachvan/codexintellij/FakeAppServerPerformanceFixtureTest.kt` | Launch fixture and verify JSONL count/order/terminal stream |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/plans/260718-1121-codex-intellij-multi-agent-performance-and-ui-consistency/reports/baseline-performance.md` | Runtime and deterministic baseline evidence |

## Tests Before

1. Run current focused baseline:

   ```bash
   ./gradlew test --tests '*MultiAgentRenderScaleTest' --tests '*TranscriptRendererTest' --tests '*CodexUiPolishTest' --console=plain
   ```

2. Add fixture tests first:
   - `eight-agent workload has exactly 5000 ordered events`.
   - `workload reduces to expected threads turns items and agents`.
   - `workload projects exactly 2000 visual blocks at terminal state`.
   - `same seed produces identical IDs order and payloads`.
   - `fake server performance scenario emits spec count and terminal events`.
   - `fake server default mode never emits performance flood`.
   - `fake server wrapper resolves repository root and returns codex version`.
3. Fail phase if fixture cannot reproduce high-frequency state notifications or transcript size without random timing.

## Refactor

- Extract existing multi-agent fixture builders from `MultiAgentRenderScaleTest` into `MultiAgentUiWorkload` without changing expected renderer output.
- Add deterministic counters for reducer notifications, projection visits và block count.
- Make Kotlin workload and Python replay read the same small JSON spec; avoid committing a 5.000-line generated trace.
- Gate replay behind an explicit environment/scenario flag so normal tests and manual use remain unchanged.
- Do not add production logging or performance services.
- Capture one warm and one stressed `runIde` session with JFR/internal mode; include exact steps and raw summary in the baseline report.

## Tests After

- Re-run original projection scale assertions unchanged.
- Launch the fake process, perform initialize/thread-start exchange and verify exactly 5.000 ordered notifications plus terminal state.
- Verify no fixture payload contains machine-specific paths, credentials hoặc raw private prompts.
- Record which success metrics are not yet measurable automatically; do not mark them passed.

## Implementation Steps

1. Freeze current focused-test result and runtime environment in report.
2. Write workload fixture tests; confirm RED until fixture exists.
3. Implement only test fixture/extraction needed to turn tests GREEN.
4. Fix `fake-codex.sh` repository-root traversal and prove `--version` process execution.
5. Add shared JSON spec and opt-in fake app-server performance replay.
6. Add deterministic event, notification, projection và block counters.
7. Run `CODEX_FIXTURE_SCENARIO=multi-agent-performance ./gradlew runIde`, configure the canonical fake binary, then execute the scenario.
8. Capture JFR/thread-dump evidence for EDT queue, layout/paint and allocation hot spots.
9. Update baseline report with observed bottleneck ranking and unknowns.

## Todo

- [x] Baseline focused tests recorded.
- [x] Deterministic 8-agent/5.000-event/2.000-block fixture GREEN.
- [x] Notification, projection và block counters recorded.
- [x] Opt-in fake app-server replay count/order GREEN; default behavior unchanged.
- [x] Fake wrapper `--version` GREEN from repository root.
- [x] Runtime capture procedure reproducible.
- [x] Baseline report separates CI proof from manual evidence.
- [x] Full `./gradlew test` GREEN.

## Success Criteria

- [x] All later phases can import one stable workload fixture.
- [x] Baseline report identifies or explicitly leaves unknown reducer, EDT delivery, projection, apply, layout, paint and allocation costs.
- [x] No production behavior changed.
- [x] Focused and full unit suites pass.

## Regression Gate

```bash
./gradlew test --tests '*MultiAgentUiWorkloadTest' --tests '*MultiAgentRenderScaleTest' --console=plain
./gradlew uiTest --tests '*FakeAppServerPerformanceFixtureTest' --console=plain
./gradlew test --console=plain
```

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Noisy JIT/wall-clock result | Use operation counts for CI; wall-clock only in report after warm-up |
| Fixture không giống real app-server stream | Preserve real event kinds, arrival ordering và terminal events from current models |
| Kotlin/Python workload diverge | Shared JSON spec + process-level count/order test |
| Performance scenario ảnh hưởng test thường | Explicit opt-in flag; default fixture regression test |
| Wrapper không launch được fixture | Fix root traversal first; process-level `--version` test blocks runtime baseline |
| UI smoke bị hiểu nhầm là interactive proof | Label `CodexDriverSmokeTest` limitation explicitly |
| Baseline work trượt thành optimization | Phase gate cấm production behavior edit |

## Security Considerations

- Synthetic payload only; không đưa raw logs, tokens, environment hoặc user content vào report.
- JFR/thread dump phải được kiểm tra và redacted trước khi commit/share.

## Next Steps

Phase 2 chỉ bắt đầu khi fixture và baseline report hoàn tất, full unit suite xanh.
