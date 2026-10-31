# VNPAY sandbox bỏ cả IPN/querydr — quyết định cuối29/10/2026

Người dùng giữ gateway sandbox thật và TMN_CODE/HASH_SECRET đã cấu hình. Bỏ cả IPN và querydr khỏi phạm vi portfolio, kể cả khi deploy. Quyết định này thay kế hoạch trung gian chỉ bỏ IPN. Không có simulator, fake capture/ledger hoặc endpoint giả PAID.

## Hành vi backend

- VNPAY_IPN_ENABLED=false: không đăng ký controller IPN, HTTP404 mặc định; frontend không proxy IPN.
- VNPAY_QUERY_ENABLED=false: không đăng ký ProviderReconciliation (cả query/refund tự động của worker này tắt), attempt mới không tạo provider_queries, Return không ghi query_wakeups. Không xóa queue/data/migration cũ; worker áp capture vào Commerce vẫn giữ nguyên.
- Giữ gateway VNPAY sandbox, TMN/HASH, server amount, HMAC/binding, session/CSRF và idempotency. Authenticated Return chỉ correlation/303, không ghi capture hoặc PAID.
- Không có kênh xác minh thì khoản chưa rõ vẫn pending/đối soát hoặc hết hạn. Chưa có luồng PAID tự động được nghiệm thu; Feedback chỉ nhận dòng PAID do Commerce xác nhận. Fixture PAID riêng chỉ dành cho test.
- Regression legacy IPN/query/ledger opt-in cả hai flag trong test, không có nghĩa portfolio cần bật lại. Không sửa backend/.env hoặc native DB, không gọi provider.

## Contract và giới hạn

Backend Payment OpenAPI1.0.2 giữ schema/operation hiện có; mô tả tổng thể nêu phạm vi Return-only. Snapshot/types frontend do main đồng bộ riêng. Main đã sửa mô tả legacy IPN/Return theo mặc định query tắt, đồng bộ snapshot/types/hash frontend; chỉ metadata thay đổi, paths/schemas giữ nguyên.

Sandbox querydr99 là vấn đề lịch sử chưa xác định nguyên nhân, không tiếp tục điều tra hoặc dùng Return để suy thành công. Local codec/HTTP/PostgreSQL regression không chứng minh capture/PAID/refund thật. Không clean, stage/commit/deploy hoặc tạo runtime demo trong lượt kiểm chứng backend này. Bằng chứng mới ghi trong docs/payment.md và docs/feedback.md theo module summaries của log mới.
