# Agora — Mô hình dữ liệu

Trạng thái ngày 23/10/2026: Identity có migration V1–V3 cho schema/account/role/session. Merchant có V1 shop/audit/idempotency và V2 shop_reviews; create/edit/submit/review/resubmit đã triển khai và kiểm thử trên PostgreSQL riêng. Identity và Merchant tách service/database theo [ADR-0002](../decisions/0002-separate-identity-merchant.md).

## 1. Quy ước

- ID ứng dụng là UUID; timestamp dùng UTC/timestamptz và hiển thị theo timezone người dùng.
- Tiền số nguyên VND (bigint + currency); số lượng gói là integer; thông số khối lượng/kích thước có đơn vị rõ.
- Aggregate có version để kiểm soát cạnh tranh. Database bảo vệ unique/check; kiểm tra trước ở application không thay constraint.
- FK chỉ trong context sở hữu; ID tham chiếu context/service khác được xác minh qua interface, không join xuyên service.
- PII chỉ trả cho chủ thể có quyền. Audit không chứa password, cookie, token hoặc toàn bộ payload nhạy cảm.

## 2. Lát cắt 1 — agora cho Identity, agora_merchant cho Merchant

Bảng identity.* thuộc database local agora; bảng merchant.* thuộc database riêng agora_merchant. Hai database đã tạo local; Merchant áp dụng migration khi startup. Không đặt bảng Merchant trong database Identity. Username postgres chỉ dùng local theo yêu cầu, không phải thiết kế quyền cho production.

| Schema/table | Các trường chính | Ràng buộc/index |
|---|---|---|
| identity.accounts | id, email (đã chuẩn hóa), password_hash, display_name, status, version, created_at, updated_at | PK id; unique email; status ACTIVE/SUSPENDED |
| identity.account_roles | account_id, role | PK kép; FK trong identity; USER/ADMIN; đăng ký chỉ USER |
| identity.spring_session và bảng attributes tương ứng | Schema Spring Session JDBC theo dependency được ghim | Unique session_id; index expiry/principal; migration theo schema PostgreSQL chính thức |
| merchant.shops | id, owner_account_id, name, description, contact_phone, status, pickup_address JSONB, version, last_review_reason, created_at, updated_at | PK id; unique owner_account_id; index status/created_at/id và created_at/id |
| merchant.shop_reviews | id, shop_id, actor_account_id, decision, reason, reviewed_version, created_at | FK shop trong merchant; unique shop_id + reviewed_version để không duyệt hai lần một version |
| merchant.audit_entries | id, actor_account_id, shop_id, action, previous_status, next_status, reason, trace_id, created_at | Append-only; index shop_id + created_at; không log toàn bộ địa chỉ |
| merchant.idempotency_records | actor_id, operation, key, request_hash, state, http_status, response_snapshot, resource_id, expires_at | Unique actor_id + operation + key; index expires_at |

Account là aggregate của Identity; Shop là aggregate của Merchant. owner_account_id và actor_account_id tham chiếu qua hợp đồng Identity, không FK xuyên database/service. Với owner account đang khóa, mọi thao tác cần quyền bị từ chối ngay cả khi cookie còn tồn tại.

Giá trị cột email được chuẩn hóa bằng strip và lowercase theo chính sách ứng dụng; không có cột normalized_email hoặc quy tắc riêng của Gmail. Password không trim, giới hạn 12–128 ký tự, hash Argon2id. Tính hợp lệ email/password phải khớp schema OpenAPI và validator backend.

pickup_address là object có countryCode=VN, provinceCode/provinceName, wardCode/wardName, streetAddress. Code là chuỗi opaque, không giả định số. Lát cắt 1 kiểm tra hình dạng; việc đối chiếu mã địa giới/địa chỉ GHN được thực hiện trước tạo vận đơn khi tích hợp. Không tuyên bố đã được GHN xác minh chỉ vì nhập form thành công.

Tạo Shop là DRAFT. Chỉ owner sửa DRAFT/REJECTED trong lát cắt 1; sửa REJECTED đưa về DRAFT. Submit DRAFT thành PENDING_REVIEW; admin APPROVE thành ACTIVE hoặc REJECT thành REJECTED, lưu lý do. Mỗi thay đổi tăng version; đọc version cũ trả 409.

Thao tác review, shop version, audit và idempotency result commit cùng transaction. Không có hai người duyệt cùng version. Thêm trạng thái SUSPENDED và sửa hồ sơ shop ACTIVE ở lát cắt quản trị tiếp theo, không loại bỏ khỏi yêu cầu MVP.

Idempotency key được giữ tối thiểu 7 ngày cho thao tác mở/submit/review shop; unique owner và version guard vẫn giữ sau khi record hết hạn. Record dữ liệu nhạy cảm được dọn theo chính sách, replay luôn kiểm tra quyền hiện tại.

Session schema lấy từ phiên bản Spring Session JDBC thực tế, đặt schema identity, không tự sao chép SQL không khớp phiên bản. Flyway quản lý; không cho framework và Flyway cùng tự tạo một bảng.

## 3. Dữ liệu các service sau

| Service | Aggregate/data chính | Bất biến cần giữ |
|---|---|---|
| Catalog | Product, SKU, InventoryLot, Reservation | Product thuộc shop; giữ toàn giỏ hoặc không; available = on_hand - held - allocated không âm; lô đủ hạn |
| Commerce | Cart, Order, Cancellation, QualityClaim; Shipment trong Fulfillment | Một shop/đơn; snapshot; claim chặn hoàn tất; raw GHN và trạng thái nội bộ riêng |
| Payment | PaymentAttempt, ProviderTransaction, Refund, LedgerEntry, Settlement | Unique provider transaction; tiền đã thu không mất vì hủy; tổng refund thành công + pending không vượt captured |
| Feedback (MongoDB) | reviews, questions, answers/replies | Unique orderLineId cho review; quyền xác minh qua Commerce; moderation/audit; không embed cây phản hồi vô hạn |

Ảnh là tham chiếu Cloudinary public_id, loại, chủ sở hữu và trạng thái gắn đối tượng; file thực không nằm trong PostgreSQL/MongoDB. Một review gắn dòng đơn và tác giả; productId/shopId là tham chiếu, không yêu cầu transaction PostgreSQL–MongoDB.

Outbox/inbox thêm ở database context có sự kiện liên service khi cần. Sổ tiền append-only và có bút toán điều chỉnh; không sửa số dư bằng thao tác tay thay cho lịch sử giao dịch.

Schema SQL/JSON cụ thể của Catalog/Order/Payment sẽ được viết cùng lát cắt tương ứng. [Use case](../product/use-cases.md) và [state machines](../product/state-machines.md) vẫn là nguồn quy tắc nghiệp vụ.
