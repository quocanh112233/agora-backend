# Feedback Implementation Plan

> Execution: native trong workspace hiện tại theo ủy quyền của người dùng; canonical tracker slice-4-feedback. Không tự commit. Review độc lập cuối lát cắt.

**Goal:** Backend Feedback dùng quyền mua thật từ Commerce.
**Architecture:** HTTP/Identity/Merchant/Commerce adapters, application orchestration, domain validation, JDBC PostgreSQL. Giữ cách tổ chức service hiện có.
**Tech Stack:** Java25/SpringBoot4.1.1/PostgreSQL/gRPC/MavenWrapper.
**Spec:** [Thiết kế](slice-4-feedback.md).

## Global Constraints

PostgreSQL-only; không logistics, IPN/tunnel/provider calls hoặc native DB writes. Review chỉ từ dòng đơn PAID; client không quyết product/shop/buyer. Không bypass xác minh tiền. Không auto commit/push/deploy. HTTP8086, database agora_feedback/schema feedback. Q&A/ảnh/UI ngoài M4a.

## Review Focus

Wrong buyer/SKU/state; malformed remote identity; concurrent replay/edit/moderation; hidden content/PII leaks; cursor/JSON bounds và DB/dependency outage.

## Tasks

1. Commerce GetReviewEligibility(ReviewLineReference) → ReviewEligibility. Dùng PaymentStore.order, chỉ PAID, tìm đúng skuId. Unit và authenticated RPC tests: PAID pass, nonPAID/foreign/missingSKU denied, malformed IDs và token denied. Chạy focused Commerce tests RED rồi GREEN.
2. services/feedback-service: pom, application/configuration, domain Feedback/FeedbackFailure, application FeedbackAccess/FeedbackService, outbound GrpcFeedbackAccess/JdbcFeedbackStore, inbound FeedbackController/errors/filter. Migration V1 reviews UNIQUE(order_id,sku_id), rating CHECK và moderation audit. HTTP strict tạo/sửa/reply/moderate và list/summary, own recovery. Viết PostgreSQL/HTTP và in-process gRPC fixture tests cho từng tiêu chí; chạy RED trước hoàn thiện code, GREEN sau.
3. contracts/openapi/feedback.v1.openapi.json, env example, README/project structure/payment-deferred note và docs/feedback.md. Chạy focused tests/root verify; review độc lập toàn diff, sửa lỗi có regression. Cập nhật HANDOFF với evidence và bước frontend kế tiếp. Không chạy payment provider.
