# M4a — Feedback: đánh giá sau mua

Ngày 29/10/2026. Người dùng yêu cầu triển khai Feedback và đã giao quyền quyết định lựa chọn thường lệ. Cập nhật29/10: vẫn VNPAY sandbox với TMN/HASH, không làm IPN; signed querydr xác minh tiền nhưng lỗi99 chưa giải quyết. Không thay bằng thanh toán giả hoặc hạ cơ chế xác minh tiền.

## Thiết kế

Feedback là service thứ sáu, HTTP 8086, database agora_feedback, schema feedback. PostgreSQL riêng; không đọc database Commerce/Merchant/Identity. Dùng gRPC nội bộ có token, deadline 2 giây, loopback theo mô hình hiện có. Commerce cung cấp GetReviewEligibility: buyerId/orderId/skuId phải khớp snapshot dòng đơn PAID. Dòng đơn được định danh bởi cặp (orderId, skuId) vì snapshot checkout hiện không có UUID dòng riêng. Server trả productId/shopId; client không được tự gán.

Một review mỗi dòng đơn, rating nguyên 1–5, content trim dài 1–2000 Unicode code points. Buyer được sửa không giới hạn thời gian trong MVP; optimistic expectedVersion tránh ghi đè. Merchant PERMISSION_DENIED khi chưa có shop ACTIVE được ánh xạ403. Seller ACTIVE đúng shop được thêm/sửa một reply dài 1–2000 ký tự. Admin hiện hành được ẩn/hiện với lý do 1–500 ký tự; lưu audit nguyên tử. Sửa review/reply không tự hiện lại nội dung bị ẩn. Refund không tự xóa review.

Public projection chỉ gồm reviewId/productId/shopId/rating/content/reply/version/timestamps; không lộ buyerId/orderId/skuId/reason/audit. Danh sách 20 dòng, keyset cursor gắn product và chỉ chấp nhận timestamp từ năm1970 đến hết9999 để tránh vượt miền driver/PostgreSQL; summary chỉ tính review hiển thị, đọc trực tiếp có thể tính lại. Nội dung plain text; không HTML. Buyer đọc review của mình theo dòng đơn để phục hồi sau mất response. Create lặp cùng payload trả review hiện có; payload khác trả conflict và dùng edit. Unique DB chống create đồng thời. Các RPC trước local transaction; dependency lỗi trả 503, không tạo review hoặc cho phép sai quyền.

## Phương án

Chọn service PostgreSQL độc lập theo ADR-0002/0003. Ghép vào Catalog trái ranh giới đã chốt; sao chép order vào Feedback cần đồng bộ không cần thiết. Xác minh PAID đồng bộ qua Commerce đơn giản, fail closed; PAID là terminal nên không cần transaction phân tán.

## Phạm vi

Lát cắt backend gồm review/create/read/edit, seller reply, admin moderation, public list/summary, contract, migration và tests PostgreSQL/gRPC/HTTP. Không thêm Q&A, ảnh review, notification, hoàn hàng hoặc UI trong lát cắt service này. UI Feedback là lát cắt tiếp theo, dùng contract này. Không tạo database native, không commit/push/deploy/tunnel. Fixture PAID chỉ dùng trong test; không có endpoint giả thanh toán.

## Tiêu chí nghiệm thu

- Chặn đơn chưa PAID, buyer khác, SKU không thuộc đơn, session/CSRF sai và dependency outage.
- Unique dòng đơn dưới request đồng thời; stale version conflict, replay không nhân đôi.
- Shop khác không reply; admin mới được moderation; hidden không lọt public/summary và edit không gỡ ẩn.
- JSON strict/bounded; public không lộ dữ liệu riêng; cursor hợp lệ và phân trang ổn định.
- Focused tests và root Maven verify thực sự đạt; docs/evidence ghi đúng giới hạn provider.
