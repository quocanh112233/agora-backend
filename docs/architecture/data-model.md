# Agora — Mô hình dữ liệu

Trạng thái ngày 24/10/2026: Catalog đã có V1 product/SKU, V2 ảnh và V3 visibility trên fixture PostgreSQL; M2b2 đã có 144 fixture frontend PASS sau sửa revalidation, P2 fixed/review accepted, 4 normal live và 2 media/publication live mới PASS; M2b2 đã nghiệm thu local ngày 24/10/2026 khoảng 15:40 +07:00 sau main xác minh hai asset mới DB DELETED/0 relation, final DRAFT/version 2 và HIDDEN/version 7, independent exact provider lookup 2 asset all_missing=true/exit 0; cleanup hai asset trước pause đã được main xác minh. Identity có migration V1–V3 cho schema/account/role/session. Merchant có V1 shop/audit/idempotency và V2 shop_reviews; create/edit/submit/review/resubmit đã triển khai và kiểm thử trên PostgreSQL riêng. Identity và Merchant tách service/database theo [ADR-0002](../decisions/0002-separate-identity-merchant.md).

Toàn bộ service dùng PostgreSQL theo [ADR-0003](../decisions/0003-postgresql-only.md), gồm Feedback. Feedback ưu tiên bảng quan hệ, JSONB chỉ bổ sung metadata khi cần; địa chỉ và idempotency snapshot JSONB hiện có của Merchant giữ nguyên. Cloudinary tiếp tục giữ file ảnh.

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

### Catalog M2a — sản phẩm nháp

Catalog chạy database riêng `agora_catalog`, schema `catalog`; migration [V1__catalog_drafts.sql](../../services/catalog-service/src/main/resources/db/migration/V1__catalog_drafts.sql), [V2__product_images.sql](../../services/catalog-service/src/main/resources/db/migration/V2__product_images.sql) và [V3__product_visibility.sql](../../services/catalog-service/src/main/resources/db/migration/V3__product_visibility.sql). Database local đã tạo; Flyway chỉ áp dụng khi khởi động app vào database đó. Nghiệm thu dùng PostgreSQL Docker riêng, chưa áp dụng Catalog vào native database.

| Bảng | Các trường/ràng buộc chính |
|---|---|
| catalog.products | id, shop_id, name, description, category, condition, origin, producer_name, storage_instructions, status DRAFT/PUBLISHED/HIDDEN qua V3, version, timestamps; unique id/shop_id; index shop_id/created_at/id |
| catalog.skus | id, product_id, shop_id, code, label, price_vnd BIGINT, net_weight_grams, shipping_weight_grams, length_cm/width_cm/height_cm, sale_unit PACKAGE, position; unique shop_id/code và product_id/position; FK kép product_id/shop_id trong Catalog |
| catalog.audit_entries | actor_account_id, shop_id, product_id, action, product_version, trace_id, created_at; FK product trong Catalog, append-only |
| catalog.idempotency_records | actor_id, shop_id, operation, key, request_hash, state, http_status, response_snapshot JSONB, resource_id, created_at/expires_at; key theo actor/operation/key, hash bao gồm shop và resource; retention 7 ngày |

Input SKU không nhận ID. PUT toàn bộ DRAFT/HIDDEN giữ ID của code không đổi, tạo ID cho code mới và xóa SKU bị bỏ. PUBLISHED phải ẩn trước sửa; sửa HIDDEN giữ HIDDEN. V4 đã thêm FK lịch sử lô: SKU từng có lô không được xóa/đổi code hoặc net/shipping weight/dimensions/saleUnit. Label/price/reorder vẫn thay đổi được và giữ ID; upsert tại chỗ, không cascade lịch sử.

Product/SKU/audit/idempotency result commit cùng transaction; expectedVersion và unique constraint bảo vệ cạnh tranh. Shop ACTIVE được xác minh qua Merchant gRPC, account/CSRF qua Identity gRPC; không join/FK xuyên database. [Contract Catalog](../../contracts/openapi/catalog.v1.openapi.json) ghi giới hạn số nguyên VND, gram và cm cùng visibility; lô có contract inventory riêng.

### Catalog M2b1 — ảnh

| Bảng | Các trường/ràng buộc chính |
|---|---|
| catalog.image_uploads | UUID, actor/shop/product/key/hash; public_id đúng namespace server cấp; PENDING/UPLOADED/DELETE_PENDING/DELETED; MIME/bytes/pixels; metadata provider đã xác minh; expires_at; unique actor/key và FK product/shop cục bộ |
| catalog.product_images | upload_id/product_id/shop_id/position0–4; unique product/position, FK kép tới upload cùng product/shop |
| catalog.media_requests | actor/key, shop/product, ATTACH_IMAGE/DETACH_IMAGE, hash/snapshotJSONB, retention7ngày; kết quả/audit/version/relation cùng transaction |

Upload intent commit trước external I/O; retry trạng thái chưa rõ chỉ tra đúng reserved ID, không upload mù lần hai. Attach/detach chỉ chạm version product, không ghi lại SKU. Hết24giờ không gắn hoặc đã detach thì worker claim DELETE_PENDING trước network; chỉ DELETED sau provider xác nhận. Claim và attach khóa/kiểm tra trạng thái để không xóa ảnh còn gắn. File binary và filename không lưu trong PostgreSQL. Xem [contract media](../../contracts/openapi/catalog-media.v1.openapi.json).

### Catalog M2b2 — visibility

V3 chỉ mở rộng check status, giữ nguyên dữ liệu/khóa/FK/index của V1/V2. Công bố/ẩn dùng bảng idempotency/audit hiện có, operation `changeProductVisibility`; audit action `PUBLISH_PRODUCT`/`HIDE_PRODUCT`. Khóa product trước đọc ảnh relation UPLOADED, cập nhật status/version mà không ghi lại SKU. Public state không được nhận trong create/replace input; chỉ visibility endpoint nhận PUBLISHED/HIDDEN. DRAFT và HIDDEN sửa được, HIDDEN giữ trạng thái; PUBLISHED phải ẩn trước sửa hoặc quản lý ảnh. Migration test giữ SKU ID, image relation và completed snapshot DRAFT từ V2 sang V3.

### Catalog M2c1 — lô hàng qua V4

[V4](../../services/catalog-service/src/main/resources/db/migration/V4__inventory_lots.sql) bổ sung:

| Bảng | Dữ liệu và invariant |
| --- | --- |
| catalog.inventory_lots | id/product_id/shop_id/sku_id, code unique theoSKU, produced_on tùy chọn/expires_on bắt buộc, on_hand/held/allocated, stored state ACTIVE/BLOCKED, version/timestamps; FK triple tới SKU, không cascade |
| catalog.inventory_requests | actor/operation/key unique, product/shop/hash, IN_PROGRESS/COMPLETED, status/snapshot/lot_id, expiry7ngày; completed FK tới lot |
| catalog.inventory_audit | actor/lot/product/shop/action/quantity_delta/lot_version/trace_id/timestamp; FK triple tới lot; nhập có delta dương, khóa/mở delta0 |

Các bộ đếm nguyên0–1.000.000gói, held+allocated<=onHand. M2c1 chưa có giữ/phân bổ nên hai bộ đếm này bằng0. Ngày hiện tại dùng Clock Asia/Ho_Chi_Minh; trạng thái response tính BLOCKED trước EXPIRED (expires_on<=hôm nay), DEPLETED nếu available0, còn lại ACTIVE. available là phép trừ vật lý, không bảo đảm checkout.

FK SKU bổ sung UNIQUE(id,product_id,shop_id); unique(product_id,position) DEFERRABLE INITIALLY DEFERRED cho reorder trong transaction. Tạo lô và sửa Product dùng cùng parent row lock; guard giữ SKU đã có lô bất kể state/hạn dùng. Requests cố ý không lấy FK parent lúc claim để tránh key-share upgrade deadlock giữa hai create; service xác minh owned product trước claim, lock trước kiểm SKU, completion tham chiếu lot. Lot/audit/result rollback cùng nhau. [Contract](../../contracts/openapi/catalog-inventory.v1.openapi.json), [demo](../demo-inventory.md).

## 3. Dữ liệu các service sau

| Service | Aggregate/data chính | Bất biến cần giữ |
|---|---|---|
| Catalog | Product, SKU, InventoryLot, Reservation | Product thuộc shop; giữ toàn giỏ hoặc không; available = on_hand - held - allocated không âm; lô đủ hạn |
| Commerce | Cart, Order, Cancellation, QualityClaim; Shipment trong Fulfillment | Một shop/đơn; snapshot; claim chặn hoàn tất; raw GHN và trạng thái nội bộ riêng |
| Payment | PaymentAttempt, ProviderTransaction, Refund, LedgerEntry, Settlement | Unique provider transaction; tiền đã thu không mất vì hủy; tổng refund thành công + pending không vượt captured |
| Feedback (PostgreSQL) | Review, Question, Answer, SellerReply, moderation/audit | Unique order_line_id cho review; rating 1–5; quyền xác minh qua Commerce; FK cục bộ; phân trang nội dung riêng |

Ảnh là tham chiếu Cloudinary public_id, loại, chủ sở hữu và trạng thái gắn đối tượng; file thực nằm ở Cloudinary. Một review gắn dòng đơn và tác giả; productId/shopId/orderLineId là tham chiếu, không FK hoặc transaction xuyên service dù cùng dùng PostgreSQL.

### Thiết kế Feedback trên PostgreSQL

Database dự kiến `agora_feedback`, schema `feedback`. Các bảng dưới là thiết kế cho M5, chưa được tạo hoặc áp dụng migration:

| Bảng | Nội dung và ràng buộc chính |
|---|---|
| feedback.reviews | UUID id, order_line_id UNIQUE, product_id, shop_id, author_account_id, rating SMALLINT CHECK 1–5, body TEXT, trạng thái hiển thị, version, created_at/updated_at; index truy vấn theo product/trạng thái/thời gian/id |
| feedback.review_replies | FK review_id trong Feedback, actor_account_id, shop_id, body TEXT, trạng thái, version và timestamp; quyền trả lời đại diện shop xác minh qua Merchant |
| feedback.questions | product_id, shop_id, author_account_id, body TEXT, trạng thái, version và timestamp; phân trang theo product/thời gian/id |
| feedback.answers | FK question_id trong Feedback, actor_account_id, shop_id, body TEXT, trạng thái, version và timestamp; không tạo cây trả lời vô hạn |
| feedback.audit_entries | Actor, đối tượng, hành động kiểm duyệt, lý do, trace ID và timestamp; append-only, không chứa credential |

Các ID ngoài Feedback không có FK tới database service khác. Ảnh của từng loại nội dung có quan hệ/tham chiếu Cloudinary riêng khi triển khai UC-15; không nhúng binary. Không dùng JSONB thay cột rating, khóa duy nhất hoặc trạng thái nghiệp vụ.

MVP ưu tiên tính count/sum/average từ review đang hiển thị để tránh projection trễ trong cùng Feedback; review ẩn không tính sao. Nếu thêm bảng thống kê sau này, cập nhật cùng transaction thay đổi review và có cách tính lại. Projection công khai ở Catalog vẫn có thể trễ khi đi qua hợp đồng/sự kiện liên service.

Outbox/inbox thêm ở database context có sự kiện liên service khi cần. Sổ tiền append-only và có bút toán điều chỉnh; không sửa số dư bằng thao tác tay thay cho lịch sử giao dịch.

Catalog có schema product/SKU, ảnh, visibility và lô qua V4; schema reservation, Order/Payment/Feedback sẽ được viết cùng lát cắt tương ứng. [Use case](../product/use-cases.md) và [state machines](../product/state-machines.md) vẫn là nguồn quy tắc nghiệp vụ.
