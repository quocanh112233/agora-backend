# M4b — Feedback UI

Ngày29/10/2026 người dùng yêu cầu tiếp tục Feedback UI và bỏ cả IPN/querydr khỏi phạm vi portfolio. VNPAY sandbox/TMN/HASH/gateway/Return giữ nguyên. Không xác nhận PAID từ Return; queries và đối soát provider tự động tắt mặc định, khoản chưa rõ vẫn pending. Không có simulator hoặc endpoint giả PAID.

## Giao diện và API

- Product detail hiển thị review công khai, số lượng/điểm trung bình, reply, cursor20 và tải lại. Plain text, không render HTML; lỗi review không làm mất product.
- Order detail chỉ hiện form đánh giá cho dòng đơn PAID. Buyer GET theo(orderId,skuId), POST nội dung/rating hoặc PATCH expectedVersion; mất response đọc lại đúng dòng. Auth identity recheck cả trước/sau response, auth-store invalidation, no credential storage. Lỗi409 không tự overwrite hoặc đổi version dưới draft; yêu cầu reload rõ ràng.
- Seller `/seller/reviews` và Admin `/admin/reviews` có list20+cursor, xem review cả hidden và version. Seller chỉ shopACTIVE của mình; admin role hiện hành. Seller reply và admin hide/restore cần expectedVersion; GET byId hỗ trợ recovery sau mất response. Chỉ ManagedReview (public fields+hidden), không gửi buyer/order/SKU/reason/audit ra các list/read này.
- Backend bổ sung GET /api/v1/seller/reviews, /seller/reviews/{id}, /admin/reviews, /admin/reviews/{id}. Admin query mọi review; seller filter shop phía server. Shared cursor bound theo scope và time1970–9999, không dùng public cursor cho quản trị. Không thêm filter/search/ảnh/Q&A.
- FE allowlist/Next rewrites chỉ đúng các operation, feedback8086 envoriginserver-only. Canonical OpenAPI Feedback1.1 được pin và generate types. Dùng component/style/auth/error của dự án, keyboard/mobile/status states.

## Kế hoạch thực hiện

1. Main tắt mặc định query worker và cả việc tạo queue ở Payment/Return; retain optionallegacy tests optin. Backend quản trị Feedback4reads, scopes/cursor/privacy tests với PostgreSQL thật và HTTP. Contract trước frontend.
2. Frontend worker chỉ frontend: client strictprojection/IDs/content/cursor; public+buyer+seller/admin UI và routing/nav; unit/browserfixtures RED/GREEN/gates. Main sở hữu Maven/backend; không cùng viết một file.
3. Fullroot + FE unit/lint/type/build/browser (production), review độc lập. Không actualVNPAY/Cloudinary/tunnel/nativeDBwrites. Không tựcommit. HANDOFF ghi đúng query/IPN đã bỏ và giới hạn PAID thật.


## Hoàn tất local — 31/10/2026

Backend487PASS; frontend163unit/340productionbrowser (41Feedback), lint/type/buildPASS. Review độc lập tìm haiP2 về giữ draft và xóa private state khi đã biết denial;8actualRED rồiGREEN, re-reviewCLEAR. [Guide](../../frontend/docs/feedback.md), [proof](../../frontend/docs/feedback-proof.md), [acceptance](../target/feedback-ui-proof-20261031/acceptance.json). Không provider/nativeDB/Git/deploy. Giữ giới hạn không tựPAID khi bỏIPN/querydr; ảnhreview/Q&A ngoài phạm vi.
