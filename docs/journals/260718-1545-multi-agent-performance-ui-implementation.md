---
date: 2026-07-18
session: multi-agent-performance-ui-implementation
---

# Journal: 2026-07-18 - Hiệu năng multi-agent và tính nhất quán UI

## Context

Phiên triển khai xử lý hai nhóm vấn đề: IDE lag khi nhiều agent cùng stream kết quả và UI Swing thiếu nhất quán về control, icon, bo góc, theme và accessibility. Phạm vi giữ nguyên app-server protocol, state store và reducer lossless; tối ưu nằm ở delivery lane dành cho UI, transcript projection/materialization và IntelliJ-native primitives.

## What Happened

- Dựng workload chuẩn 8 agent, 5.000 event và 2.000 semantic transcript block. TDD đầu tiên bắt được fake app-server wrapper resolve sai `src/src/...`; process fixture được sửa trước khi dùng làm baseline.
- Thêm `UiStateBridge`: 5.000 store notification vẫn được giữ, nhưng synchronous burst được hợp nhất thành một UI delivery với pending high-water bằng 1. Transcript chuyển sang stable block ID/revision, keyed reconciliation và selective surface invalidation.
- Áp dụng bounded viewport materialization, height/HTML cache có giới hạn, scroll anchor và drag deferral. Runtime profile buộc giảm target từ 200 xuống 40 block; hard cap vẫn là 250. Plain one-line agent message dùng recyclable native row để tránh HTML parser/document allocation ở hot path.
- Runtime `runIde` 10 phút đạt 180,7 ms result visibility, 101,4 ms maximum EDT gap, 1,60 ms scroll-action p95, 62,1 FPS và tối đa 40 block materialized. Retained heap sau full GC tăng khoảng 6,6 MB; histogram cuối không còn live transcript HTML host.
- Chuẩn hóa spacing/control/radius/icon token, `CodexIconButton`, platform icons, tooltip, accessible name và focus contract. Không tạo design framework song song với Swing/IntelliJ components.
- Visual TDD phát hiện composer 341 px làm control bị cắt và model selector thiếu accessible name. Fix bằng responsive toolbar một hàng ở wide, hai hàng ở narrow, đồng thời thêm semantics cho model.
- Vòng visual tiếp theo phát hiện composer input vẫn dùng màu Darcula khi app đổi sang Light và thiếu accessible name. Fix bằng `JBColor.namedColor` theo `TextField` LAF và semantics cho input.
- Ma trận thực tế pass ở Light/Darcula, home/chat, 320/900 px: không composer clipping, không icon thiếu tooltip, không focusable control thiếu accessible name. HiDPI 2x pass icon và focus ring. Keyboard traversal đi qua input, model, reasoning, IDE context, attachment, approval, send và task navigation; UI Inspector thật xác nhận composer cùng task list.
- Full automated test, UI smoke, protocol/schema, structure, build và plugin verifier pass sau migration chính. Review phát hiện 2 High về wrap O(n²)/resize rewrap và 3 Medium về focus khi recycle, surrogate pair, fixture environment allowlist; cả năm được sửa theo TDD và re-review không còn finding.

## Reflection

Measure-first thay đổi thiết kế thực tế: target 200 nhìn hợp lý trên deterministic test nhưng runtime cho thấy layout/reconciliation gap nhiều giây, nên target 40 là quyết định dựa trên profile chứ không phải con số tùy ý. Unit test cũng không thay thế manual runtime/visual gate; hai lỗi composer chỉ lộ ra khi đổi LAF và ép viewport hẹp. Ngược lại, visual pass chưa đủ để kết luận hoàn tất khi independent review còn finding trên hot path, resize, Unicode, focus và ranh giới test fixture.

## Decisions Made

| Decision | Rationale | Impact |
|---|---|---|
| Giữ store/reducer lossless, chỉ coalesce UI lane | Không đổi protocol semantics hoặc làm mất event | UI queue được chặn mà final state vẫn đầy đủ |
| Stable semantic block + keyed reconciliation | Append/delta không nên rebuild transcript tail | Giảm churn component và cho phép reuse host |
| Target 40, hard cap 250 | Runtime profile bác bỏ target 200 | Scroll/layout đạt ngưỡng, vẫn có regression ceiling rõ |
| Native row cho plain agent message | HTML parser/document là allocation hot path | Giảm heap/layout cost, rich block vẫn giữ fidelity |
| IntelliJ-native token và một icon primitive | Cần nhất quán nhưng tránh wrapper proliferation | Light/Darcula/HiDPI và accessibility dùng chung contract |
| Chỉ đóng plan sau re-review | Visual pass vẫn có thể bỏ sót hot-path, Unicode, focus và fixture-boundary bugs | Năm finding được sửa và re-review sạch trước khi completion |

## Next Steps

- Giữ runtime workload và visual/accessibility matrix làm regression gate cho thay đổi transcript/UI tiếp theo.
- Release/commit là workflow riêng, chỉ thực hiện khi người dùng yêu cầu.

## Câu hỏi còn mở

Không còn câu hỏi chặn trong phạm vi plan này.
