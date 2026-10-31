# Feedback service — M4a/M4b management reads

Backend đánh giá sau mua dùng PostgreSQL riêng `agora_feedback`, schema `feedback`, HTTP `127.0.0.1:8086`. [Thiết kế](../tasks/slice-4-feedback.md), [kế hoạch](../tasks/slice-4-feedback-plan.md), [HTTP contract](../contracts/openapi/feedback.v1.openapi.json), [Commerce RPC](../contracts/proto/agora/commerce/v1/commerce_payments.proto).

## Quyền và dữ liệu

Identity xác minh session và CSRF hiện hành qua gRPC9091. Commerce9094 xác nhận buyer sở hữu đúng SKU trong snapshot đơn PAID; server trả product/shop. Không dùng input client, Return VNPAY hoặc trạng thái hiển thị trên trình duyệt để chứng minh đã mua. Cặp `(orderId,skuId)` định danh dòng đơn hiện có; UNIQUE tại database ngăn review trùng. PAID là terminal nên việc xác minh trước transaction local không cần khóa xuyên service. Refund không xóa review đã hợp lệ.

POST cùng nội dung đã trim trả review hiện có (200; lần đầu201). Khác nội dung trả409; dùng GET cùng dòng để phục hồi, PATCH với expectedVersion để sửa. Phiên bản tăng khi buyer sửa, shop phản hồi hoặc admin kiểm duyệt; stale version trả409. Seller ACTIVE qua Merchant9092 chỉ phản hồi review đúng shop. Admin role lấy lại từ Identity cho mỗi request; audit lưu cùng transaction với visibility. Buyer/seller sửa không gỡ ẩn. Cả ẩn và hiện đều cần lý do.

Giới hạn text tính theo Unicode code points, gồm emoji. Merchant trả PERMISSION_DENIED khi account chưa có shop ACTIVE thì Feedback trả403. Public list tối đa20 review với cursor gắn product, timestamp chỉ trong năm1970–9999 để tránh vượt miền PostgreSQL/driver, keyset theo createdAt/id; summary chỉ đếm review chưa ẩn, averageRating=null nếu rỗng. Projection không có buyerId/orderId/skuId/hidden/reason/audit. Plain text; frontend sau này phải render text, không HTML. Private GET thuộc buyer; seller reply response dùng public projection. Bốn GET quản trị `/api/v1/seller/reviews`, `/seller/reviews/{id}`, `/admin/reviews`, `/admin/reviews/{id}` trả ManagedReview (public fields + hidden), không có buyerId/orderId/skuId/reason/audit. Seller chỉ đọc shop ACTIVE hiện hành của mình, kể cả review hidden; admin phải có role hiện hành trên mỗi request. GET byId trả version mới nhất để phục hồi sau mất response. List tối đa20 dòng, keyset createdAt/id; cursor gắn seller-shop hoặc admin, không dùng lại cursor public hoặc scope khác. Migration V2 chỉ thêm hai index quản trị, không sửa V1 hoặc migrate database native. Database chỉ có FK trong Feedback. Lỗi dependency/storage trả503; không chuyển sang dữ liệu giả.

## Chạy và kiểm thử

Dùng Maven Wrapper tại backend; `./mvnw -pl services/feedback-service -am test` chạy PostgreSQL Testcontainers độc lập, không cần VNPAY/Cloudinary hoặc ghi database native. Full regression dùng `./mvnw verify`. Chạy app cần database Feedback được provision riêng và INTERSERVICE_TOKEN giống Identity/Merchant/Commerce; đây chưa phải lệnh provision/deploy. Các gRPC endpoint hiện dùng loopback theo cấu hình local của các service đang có. Không dùng cấu hình này trực tiếp cho nhiều container/máy.

`FEEDBACK_DB_NAME`, `FEEDBACK_SERVER_ADDRESS`, `FEEDBACK_SERVER_PORT` có trong `.env.example`; secret chỉ ở file ignored. Không thêm key provider. Session cookie hiện có dùng chung domain/path qua frontend proxy khi làm lát cắt UI.

## Giới hạn

Phạm vi kiểm chứng ở đây chỉ backend; frontend được main nghiệm thu riêng. Ảnh review và Q&A ngoài phạm vi. Các test dựng đơn/capture và remote fixtures chỉ là bằng chứng local, không chứng minh tài chính thật. Quyết định cuối29/10 bỏ cả IPN và querydr khỏi portfolio: VNPAY_IPN_ENABLED=false, VNPAY_QUERY_ENABLED=false mặc định, không điều tra lại query99 hoặc hoãn IPN đến deploy. Gateway sandbox thật, TMN/HASH và authenticated Return giữ nguyên; Return không tạo capture/PAID. Chưa có luồng PAID tự động được nghiệm thu khi cả hai kênh xác minh tắt; review vẫn chỉ nhận dòng PAID do Commerce xác nhận. Không có endpoint giả PAID hoặc sửa đơn thật bằng SQL.

## Kiểm chứng backend M4b — 31/10/2026

`./mvnw verify` không clean kết thúc **19:48:19+07:00, BUILD SUCCESS: 487 tests**, 0 failure/error/skip. Số từ module summaries của đúng [log mới](/tmp/agora-backend-verify-20261031-feedback-payment-final-green.log): Identity13, Merchant59, Catalog206, Commerce84, Payment91, Feedback34. Feedback gồm25HTTP/PostgreSQL và9access tests. Không cộng XML tồn dư. [Focused Feedback verify](/tmp/agora-backend-feedback-20261031-focused.log) cũng34PASS lúc19:45:08+07:00.

Review không tìm thấy blocker production trong management reads hoặc Payment both-off; không đổi production code/contract/migration trong lượt này. Thêm3 regression Feedback: pagination cùng timestamp và nhiều shop/hidden với exact projection/no-store; quyền admin/shop hiện hành bị thu hồi; cursor sai scope/public, malformed, trùng query và timestamp ngoài miền. Strengthen Payment both-off regression: reject chữ ký tampered, signed amount mismatch và unknown attempt; valid Return303 đúng order/attempt/key, không provider query/wakeup/capture/ledger/capture job hoặc Commerce capture call. Gateway URL vẫn sandbox thật; IPN404 và ProviderReconciliation absent theo mặc định.

[Lượt full trung gian](/tmp/agora-backend-verify-20261031-feedback-payment-final.log) có1error trong setup test mới: `when(...).thenReturn(...)` gọi validator stub cũ với matcher null. Đã sửa đúng test bằng `doReturn(...)`; focused34 và full487 sau đóPASS. Đây không phải lỗi runtime production. Tài liệu Payment/Feedback/README được sửa theo quyết định bỏ cả IPN/querydr. Main đã đồng bộ mô tả IPN/Return của canonical Payment1.0.2 chưa phát hành với hai flag mặc định tắt; không đổi paths hoặc schemas.

Sau full verify, read-only ngoài sandbox xác nhận không còn Maven/test Java của project hoặc container Testcontainers; các cổng8081–8086/9091–9094 không có listener. Testcontainers tự dọn; không dừng native PostgreSQL. Giữ dirty baseline, backend/.env và native DB; không provider calls, frontend/root HANDOFF edits, stage/commit/deploy hoặc subagents. Chưa kiểm chứng frontend/live provider; chưa có luồng PAID/refund tự động thật trong cấu hình cả hai kênh tắt.

## Kết quả kiểm tra lịch sử (trước M4b)

Ngày29/10/2026, `./mvnw verify` kết thúc21:23:22+07:00: **477 tests PASS**, 0 failure/error/skip: Identity13, Merchant59, Catalog206, Commerce84, Payment88, Feedback27. Feedback gồm18HTTP/PostgreSQL và9gRPC access tests; Commerce thêm7 tests về quyền review. Trước đó focused60PASS. Root CI hiện dùng cùng Maven verify nên tự bao gồm module Feedback; chưa có GitHub run mới.

Actual RED trước implementation và regression summary NUMERIC→Double đã được ghi. Review độc lập Darwin tìm3P2 (Merchant permission mapping, Unicode limits, cursor timestamp range); cả3 có actual root RED rồi sửa và final root GREEN. Scoped re-review không còn P1/P2 trong phạm vi.

[Final log](../target/feedback-proof-20261029/agora-feedback-root-final.log), [acceptance mới sau thay đổi IPN](../target/vnpay-no-ipn-proof-20261029/acceptance.json), [execution source manifest mới](../target/vnpay-no-ipn-proof-20261029/source-manifest.json) ở target ignored, không push. Đếm theo module summaries trong log của đúng lần chạy; một XML IdentityApplicationTests cũ không thuộc lần chạy này được loại, không cộng thành478. Static checks:7operationId duy nhất,54localrefs hợp lệ, liên kết Feedback tồn tại, diff-check sạch; Git-visible pattern scan296files không thấy private-key/token mẫu phổ biến. Đây không phải audit toàn lịch sử.

Checkpoint trước quyết định bỏ querydr ngày29/10: full backend479PASS lúc21:41:36, gồm27Feedbacktests giữ nguyên. VNPAY sandbox dùng TMN/HASH và signedquery, không chuyển sang simulator; IPN không còn pending deploy. Artifacts cũ trong target có thể bị clean; log477 được giữ, acceptance/source manifest của checkpoint đó là root479 ở link trên, không nghiệm thu source M4b hiện tại.
