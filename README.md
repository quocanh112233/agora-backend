# Agora Backend

## Phạm vi hiện hành — 28/10/2026

Theo [ADR-0004](docs/decisions/0004-order-and-payment-only.md), luồng mua chỉ gồm đặt hàng một shop và thanh toán VNPAY sandbox. Tổng đơn bằng tổng tiền hàng; không vận chuyển hoặc nhận tại shop, không GHN/Fulfillment, phí/địa chỉ giao nhận, kiện, vận đơn hay tracking. Sáu service Identity/Merchant/Catalog/Commerce/Payment/Feedback giữ nguyên.

Order đích: `AWAITING_PAYMENT → PAID | EXPIRED | CANCELLED`; `PAID` kết thúc luồng mua khi tiền hợp lệ và trừ tồn được xác nhận. Feedback tương lai xác minh dòng đơn `PAID`; khiếu nại chất lượng sau giao/return shipment ngoài phạm vi, refund tài chính cho tiền trễ/trùng vẫn cần. M3d đã có implementation local cho `HELD → CONSUMED`, giảm `held` và `on_hand` nguyên tử; không tạo `SHIPPED` giả hoặc thêm luồng `ALLOCATED`.

M3b đã nghiệm thu; [M3c checkout](tasks/slice-3-checkout.md) đã có implementation trong workspace, đã nghiệm thu local ngày27/10/2026. [Demo checkout](docs/demo-checkout.md) ghi final gates, review, live và cleanup do main độc lập xác minh. M3c tạo đơn chờ thanh toán. [M3d Payment](docs/payment.md) đã có implementation và tests local; nghiệm thu toàn slice còn partial, chờ provider sandbox thực và proof cuối của main. Không dùng provider fixture để tuyên bố thanh toán thật thành công. Giữ nguyên hồ sơ Merchant/contact/`pickup_address`, metadata quy cách SKU và các cột `allocated` hiện có; không xóa dữ liệu hoặc rewrite migration cũ. Các số kiểm chứng lịch sử bên dưới giữ theo ngày và từng slice, không thay proof M3d.

## Payment M3d — đã triển khai local, nghiệm thu còn partial

[Hướng dẫn Payment](docs/payment.md), [plan M3d](tasks/slice-3-payment.md), [OpenAPI](contracts/openapi/payment.v1.openapi.json). Payment HTTP `127.0.0.1:8085` gọi Identity9091 và Commerce RPC9094; Commerce gọi Catalog9093 để consume reservation. Sáu service đích đều PostgreSQL-only; hiện có năm service trong reactor, Feedback còn dự kiến. Không GHN hoặc inventory refund.

Mặc định `VNPAY_ENABLED=false` chặn tạo attempt mới; callback và reconciliation cho attempt đã có vẫn hoạt động khi cấu hình provider hợp lệ. Chỉ Payment đọc `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`; không ghi giá trị vào tài liệu. Attempt giữ deadline gốc của hold15phút, tổng tiền server `5000..9999999999` VND, auth/CSRF và ownership trước replay đúng key. IPN commit capture/ledger/job nguyên tử; V2 lưu durable query wakeup, worker dùng lease/revision và gọi network ngoài transaction. Refund reserve khác refund `SUCCEEDED` đã có bằng chứng; lost reply giữ `NEEDS_RECONCILIATION`, không gửi lại refund chưa rõ.

Mốc tối28/10 do main cung cấp: Payment projection **83 PASS**, sau durable wakeup **86 PASS**, read-only review **CLEAR**. Đây là các lượt local trước root, không cộng dồn. Root verify local do main xác minh ngày **28/10/2026 lúc19:53:44+07:00: BUILD SUCCESS, 443 PASS =13 Identity+59 Merchant+206 Catalog+77 Commerce+88 Payment, 0failure/error/skip**. [root log](target/payment-proof-20261028-evening/root-verify.log), [manifest238sourcefiles](target/payment-proof-20261028-evening/source-manifest.json), [năm verified JAR](target/payment-proof-20261028-evening/verified-jars.json). Main đang boot năm JAR trên fixture mới; live/provider và proof nghiệm thu toàn M3d vẫn **pending**. Actual provider success/query/IPN/refund chưa được chứng minh ở đây. Demo M3d dùng fixture Docker **năm database biệt lập**, không tạo/migrate native database; fixture hoặc callback tự ký không thay proof provider thực.

## Catalog M3b — giữ tồn nội bộ

Catalog đã nghiệm thu local ngày27/10/2026 phần giữ tồn FEFO/TTL15phút qua ba RPC Hold/Get/Release. Một checkout/một shop, 1–20 SKU/1–99gói, atomic toàn bộ, identity checkout vĩnh viễn; replay không gia hạn hoặc hồi sinh. Tại nghiệm thu M3b 27/10 lúc 10:14:28+07:00 chưa có HTTP checkout hoặc kiểm giá đã chấp nhận; phần M3c hiện có được ghi riêng bên dưới. [Thiết kế](tasks/slice-3-reservations.md), [hướng dẫn và bằng chứng](docs/demo-reservations.md). Các số test M3a/M2 dưới đây giữ theo ngày; kết quả M3b cuối ghi riêng trong hướng dẫn.

Backend marketplace B2B2C nông sản và thủy hải sản cho portfolio cá nhân. Định hướng microservice/DDD, Java Spring Boot, PostgreSQL cho toàn bộ dữ liệu ứng dụng, gRPC và các tích hợp sandbox. Cloudinary lưu ảnh; Feedback cũng dùng PostgreSQL theo [ADR-0003](docs/decisions/0003-postgresql-only.md).

## Hiện trạng

Identity chạy Java 25, Spring Boot 4.1.1, Maven Wrapper 3.9.16, JDBC/Flyway. Đã có đăng ký USER, session login/logout, `/me`, CSRF, Argon2id và gRPC xác thực nội bộ. Merchant là ứng dụng/database riêng: tạo/sửa shop nháp, gửi duyệt, admin liệt kê/phê duyệt/từ chối và sửa hồ sơ bị từ chối để gửi lại. Cả 11 thao tác onboarding trong OpenAPI đã có implementation. Catalog M2a sản phẩm/SKU và M2b1 ảnh Cloudinary đã nghiệm thu; M2b2 công bố/ẩn đã có backend/UI và gates trước pause; sau resume frontend đã PASS build/lint/typecheck, 86 unit và 144 fixture gồm 19 regression revalidation. P2 đã sửa/review chấp nhận, normal live 4/4 và media/publication live 2/2 PASS. M2b2 đã nghiệm thu local ngày 24/10/2026 khoảng 15:40 +07:00 sau cleanup đúng hai asset mới. Commerce là service thứ tư, có giỏ M3a và implementation checkout/đơn M3c đã nghiệm thu local ngày27/10/2026; Payment là service thứ năm đã có implementation/tests local M3d; Feedback chưa triển khai.

Máy local dùng PostgreSQL 16 tại 127.0.0.1:5432: `agora` cho Identity, `agora_merchant` cho Merchant, `agora_catalog` cho Catalog. Không cần thêm container cho dữ liệu local. Integration tests dùng PostgreSQL Docker biệt lập, không reset/truncate database local. Maven đã cài trong thư mục người dùng; ưu tiên wrapper trong repository.

## Catalog M2c1 — lô hàng

M2c1 quản lý lô hàng đã nghiệm thu local ngày24/10/2026 sau resume20:23: backend150 tests (16:03:17, source không đổi), frontend98 unit,177 browser fixture production và5 live qua ba service đều đạt; build/lint/typecheck đạt, review cuối không còn blocker trong phạm vi sửa. M2c2 public browse/detail đã có implementation, đã nghiệm thu local ngày 25/10/2026; tại mốc M2c1/M2c2 này chưa có reservation/FEFO allocation hoặc checkout.

Chủ shop ACTIVE nhập lô theo SKU, xem số gói/ngày/trạng thái và khóa/mở lô qua ba operation trong [contract inventory](contracts/openapi/catalog-inventory.v1.openapi.json). Lô dùng ngày Asia/Ho_Chi_Minh, hết hạn từ00:00 ngày hạn dùng; không cần scheduler để chặn lô hết hạn. SKU có lịch sử lô không được xóa hoặc đổi quy cách, nhưng vẫn đổi giá/nhãn và reorder mà giữ ID. V4 thêm ba bảng inventory và constraint; V1–V3 không đổi, native database không bị migrate bởi đợt kiểm thử.

[Demo lô hàng](docs/demo-inventory.md), [kế hoạch](tasks/slice-2-inventory.md), [proof đầy đủ](../frontend/docs/catalog-inventory-proof.md). Các số M2a/M2b bên dưới là lịch sử theo ngày.

## Catalog M2c2 — khám phá công khai, đã nghiệm thu local

Đã triển khai đúng hai GET anonymous trong [contract storefront](contracts/openapi/catalog-storefront.v1.openapi.json): `/api/v1/products` và `/api/v1/products/{productId}`. Chỉ hiển thị PUBLISHED có ảnh attached tới upload UPLOADED và shop hiện ACTIVE. Bộ lọc tên/category/origin/giá/shop dùng cursor theo created_at DESC, id DESC. Mỗi request đọc tối đa21 candidate, kiểm tối đa20 qua Merchant với4batch×5ID; candidate thứ21 chỉ lookahead. Trang có thể rỗng mà vẫn có `nextCursor`, cần tiếp tục **Xem tiếp**.

Public shop chỉ `{id,name}`; tên tối đa120codepoints, tên legacy chỉ khoảng trắng bị omit. Product/SKU chỉ có `inStock` boolean từ lô ACTIVE, expires_on>hôm nay Asia/Ho_Chi_Minh và on_hand-held-allocated>0. Hết tồn vẫn xem được; boolean không đảm bảo giữ/mua/giao hàng. Không trả PII, version/audit/upload intent, mã/ngày lô hoặc số lượng tồn; allowlist và các trường private cụ thể ở [ranh giới public](docs/architecture/catalog-security.md#storefront-m2c2--public-projection).

Main báo root verify **25/10/2026 20:50:38 +07:00 BUILD SUCCESS,185tests (13Identity+59Merchant+113Catalog),0fail/error/skip**. Frontend/live/review đã hoàn tất, bằng chứng cuối ghi trong demo storefront; proof M2c1/M2b2 không thay proof storefront. [Demo storefront](docs/demo-storefront.md), [plan](tasks/slice-2-storefront.md). Tại nghiệm thu M2c2 ngày25/10 chưa có reservation/checkout/VNPAY/review/Q&A hoặc profile shop đầy đủ. M3b reservation đã nghiệm thu27/10; GHN bị loại khỏi target theo ADR-0004.

## Commerce M3a — giỏ hàng theo tài khoản

Commerce HTTP 127.0.0.1:8084, PostgreSQL agora_commerce/schema commerce; [V1](services/commerce-service/src/main/resources/db/migration/V1__commerce_cart.sql) lưu giỏ/dòng hàng/request. Đúng ba operation trong [OpenAPI giỏ 1.0.0](contracts/openapi/commerce-cart.v1.openapi.json): GET /api/v1/me/cart, PUT và DELETE /api/v1/me/cart/items/{skuId}. Người mua đăng nhập có giỏ nhiều shop, tối đa 20 SKU/1–99 gói mỗi dòng; PUT đặt số lượng tuyệt đối. Giỏ không giữ tồn hoặc checkout.

GET lấy current offers qua [CatalogOffers.ResolveOffers](contracts/proto/agora/catalog/v1/catalog_offers.proto) ở 127.0.0.1:9093; hàng mất public giữ refs với offer:null. cartId ổn định theo account đã xác minh; cartId từ tài khoản cũ bị 409 trước replay/write. Mutation kiểm Identity/CSRF, expectedVersion và UUID key giữ tối thiểu 7 ngày. PUT tra replay trước Catalog rồi kiểm lại dưới row lock; DELETE không gọi Catalog. Sau 200 phải GET mới; refresh 503 chỉ giữ cartId/version cùng refs tối thiểu để xóa, không dùng giá/tên cũ như hiện tại.

Main xác minh root verify ngày **26/10/2026 lúc 10:55:16 +07:00: BUILD SUCCESS, 226 tests = 13 Identity + 59 Merchant + 130 Catalog + 24 Commerce; 0 fail/error/skip**. Commerce 24 = 6 domain + 15 API + 3 service; Catalog thêm 17 = 12 gRPC + 4 SQL + 1 configuration. Đây là bằng chứng backend do main cung cấp; **M3a đã nghiệm thu local ngày 26/10: frontend 136 unit, 243 fixture production, 5 normal live và 1 cart/provider live đạt; hai lỗi review đã sửa và re-review CLEAR**.

[Demo giỏ](docs/demo-cart.md), [plan M3a](tasks/slice-3-cart.md), [mô hình dữ liệu](docs/architecture/data-model.md#commerce-m3a).

## Chạy local

Chạy các lệnh từ thư mục backend. .env local đã có và bị Git ignore; khi clone mới chỉ copy .env.example nếu chưa có .env, rồi đặt credential của mình. Không commit .env.

```bash
# Chỉ cần cho lần clone mới; không ghi đè .env hiện có
test -f .env || cp .env.example .env
chmod 600 .env

# Kiểm chứng toàn bộ reactor bằng PostgreSQL Testcontainers
# Image postgres:16 đã có sẵn trên máy này (PostgreSQL 16.15).
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify

# Cài contracts vào Maven local trước khi chạy service độc lập
./mvnw -B -ntp -pl libraries/identity-contract,libraries/merchant-contract,libraries/catalog-contract,libraries/commerce-contract -am install

# Chạy Identity với PostgreSQL local và .env
./mvnw -pl services/identity-service spring-boot:run

# Terminal khác, cũng từ backend; Identity phải bật gRPC
./mvnw -pl services/merchant-service spring-boot:run

# Terminal thứ ba; Merchant cũng phải bật gRPC
./mvnw -pl services/catalog-service spring-boot:run

# Terminal thứ tư; chỉ khi đã chọn DB Commerce biệt lập và bật Catalog gRPC
./mvnw -pl services/commerce-service spring-boot:run
```

Commerce mặc định trỏ agora_commerce, Payment trỏ agora_payment; không coi đó là database native đã được tạo/migrate. Demo M3d do main sở hữu dùng fixture Docker năm database Identity/Merchant/Catalog/Commerce/Payment biệt lập; xem [chuẩn bị Payment](docs/payment.md#demo-và-provider-gate). Fixture bốn database của [demo giỏ](docs/demo-cart.md#chuẩn-bị) là mốc lịch sử, không đủ cho M3d. Không khởi động app vào native để tạo proof.

Service đọc .env tự động từ cwd backend; không cần source password vào shell. Health: Identity http://127.0.0.1:8081/actuator/health, Merchant http://127.0.0.1:8082/actuator/health, Catalog http://127.0.0.1:8083/actuator/health, Commerce http://127.0.0.1:8084/actuator/health, Payment http://127.0.0.1:8085/actuator/health. Ctrl+C để dừng service. Identity dùng `SERVER_PORT`, Merchant dùng `MERCHANT_SERVER_PORT`, Catalog dùng `CATALOG_SERVER_PORT`; Commerce dùng `COMMERCE_SERVER_PORT` và `COMMERCE_DB_NAME`; Payment dùng `PAYMENT_SERVER_PORT` và `PAYMENT_DB_NAME`. Khi đổi thư mục chạy jar, bảo đảm .env được đọc từ đúng cwd.

V1–V3 đã tạo schema Identity, account/role và Spring Session JDBC. Merchant có V1 shop/audit/idempotency và V2 review; Catalog có V1 product/SKU/audit/idempotency, V2 image uploads/relations/media requests, V3 visibility, V4 inventory_lots/inventory_requests/inventory_audit cùng bảo vệ SKU có lịch sử và V5 partial index PUBLISHED(created_at DESC,id DESC). Flyway áp dụng vào database tương ứng khi app chạy. Nghiệm thu dùng database Docker biệt lập, không phải yêu cầu migrate native database. Migration đã áp dụng không được sửa; thay đổi tiếp bằng migration mới. Credential `postgres` hiện chỉ dành cho local theo yêu cầu, không dùng cho deployment.

Xem [hướng dẫn demo mở shop](docs/demo-onboarding.md) để chuẩn bị ADMIN có kiểm soát và [demo Catalog](docs/demo-catalog.md) để chạy sản phẩm/SKU, ảnh và công bố/ẩn sau khi shop ACTIVE.

## Session và gRPC local

Browser gọi HTTP cùng origin qua Next rewrites. Cookie `AGORA_SESSION` có HttpOnly/SameSite=Lax/Path=/, idle 30 phút. Lấy `/api/v1/auth/csrf` trước mutation và lấy lại sau login/logout. Đăng ký không tự đăng nhập và không nhận role do client khai báo. Không lưu phiên vào localStorage/Zustand persist.

`IDENTITY_GRPC_ENABLED=true` bật listener `127.0.0.1:9091`; Merchant/Catalog kiểm tra phiên qua [SessionIdentity v1](contracts/proto/agora/identity/v1/session_identity.proto). `MERCHANT_GRPC_ENABLED=true` bật Merchant `127.0.0.1:9092`; Catalog xác minh shop ACTIVE qua [MerchantAccess v1](contracts/proto/agora/merchant/v1/merchant_access.proto). `CATALOG_GRPC_ENABLED=true` bật Catalog 127.0.0.1:9093 cho CatalogOffers, CatalogReservations và CatalogCheckout; Commerce kiểm session qua Identity, đọc offers và gọi quote/accepted hold qua Catalog. `COMMERCE_GRPC_ENABLED=true` bật CommercePayments tại127.0.0.1:9094 (mặc định tắt); Payment dùng listener này cùng Identity9091. Năm app dùng cùng `INTERSERVICE_TOKEN` ngẫu nhiên 32–256 ký tự URL-safe từ .env. File local hiện đã có token ngẫu nhiên; `.env.example` để trống, không có credential mặc định. Không in hoặc commit token.

gRPC plaintext chỉ dành cho loopback local. Trước khi triển khai các app qua network/container riêng phải có TLS/mTLS và policy nội bộ; không chỉ mở rộng bind. HTTPS phải bật `SESSION_COOKIE_SECURE=true`. Rate limit bộ nhớ chỉ phù hợp một instance; Next proxy local có thể khiến nhiều người dùng dùng chung quota IP. Xem [ghi chú bảo mật](docs/architecture/identity-security.md).

## Catalog M2a

[OpenAPI Catalog](contracts/openapi/catalog.v1.openapi.json) M2a ban đầu có 4 operation dưới `/api/v1/seller/products`: list/create/read/PUT toàn bộ DRAFT. Contract hiện tại 1.1.0 có 5 operation gồm visibility; PUT nội dung chấp nhận DRAFT/HIDDEN, PUBLISHED phải ẩn trước sửa. Shop ACTIVE là điều kiện cho mọi request, gồm replay idempotency. Catalog dùng database riêng, không join database Merchant/Identity. Tiền số nguyên VND; SKU là gói cố định, có khối lượng gram và kích thước cm. Các trường khối lượng/kích thước đã nghiệm thu được giữ như metadata legacy; checkout mới không dùng để tính kiện hoặc phí.

Phạm vi, quyền và race giữa các service ở [catalog-security](docs/architecture/catalog-security.md). Lô hàng được bổ sung trong M2c1; browse/detail công khai đã nghiệm thu M2c2 ngày 25/10/2026. Reservation HELD/RELEASED đã nghiệm thu M3b ngày 27/10; checkout M3c đã có implementation, đã nghiệm thu local ngày27/10/2026; xem [demo checkout](docs/demo-checkout.md).

## Catalog M2b1 — ảnh sản phẩm nháp

[Contract ảnh riêng](contracts/openapi/catalog-media.v1.openapi.json) có bốn thao tác list/upload/attach/detach. Backend proxy JPEG/PNG tới Cloudinary, tối đa 5 MiB/20 triệu pixel và năm ảnh gắn. Upload lưu intent trước network, không tự gắn; attach/detach dùng expectedVersion và idempotency, tăng version/audit mà không sửa SKU. Worker dọn upload không còn tham chiếu, có retry; không xóa ảnh đang gắn. Không nhận URL/public ID từ client.

Ba biến secret server-only trong [cấu hình Cloudinary local](docs/cloudinary-local.md); chưa cấu hình trả503 khi dùng ảnh. [Kế hoạch M2b1](tasks/slice-2-media.md) ghi scope và giới hạn. Root verify 22:57:11 ngày23/10 đạt **114 tests: 13 Identity + 44 Merchant + 57 Catalog**, zero fail/error/skip; Catalog gồm25 test cũ,19media API PostgreSQL,2cleanup unit và11provider. Probe Cloudinary thật xác minh upload/metadata/delete đúng ảnh PNG mới; kết quả UI/live ảnh ghi riêng sau nghiệm thu.

Ngày24/10 lúc10:05:50, root verify sau sửa PNG đạt **116tests(13Identity44Merchant59Catalog)**. Validator chỉ đọc header/kích thước và bỏ metadata không dùng; regression đo cấp phát đã tái hiện25,278,800bytes trước sửa, dưới2MiB sau sửa với cùng PNG chứa8MiB compressed text. Hai test mới gồm kiểm tra JPEG vẫn đọc đúng kích thước. Giao diện/live của revision sau sửa được kiểm chứng riêng, không suy ra từ test provider giả.

M2b1 nghiệm thu ngày 24/10: frontend build/lint/typecheck, 77 unit, 111 fixture, 4 normal live và 1 media live đều đạt sau sửa revalidation khi quay lại tab. Live dùng ba service thật và ảnh Cloudinary mới; worker ghi DELETED, zero relation, main tra đúng reserved ID và xác nhận provider không còn resource. Proof UI ở repository frontend; không cộng số frontend vào tổng backend.

## Catalog M2b2 — công bố và ẩn

`PUT /api/v1/seller/products/{productId}/visibility` nhận `{status:"PUBLISHED"|"HIDDEN",expectedVersion}` và UUID key, trả Product. Chỉ shop ACTIVE/current owner; công bố cần dữ liệu đã lưu hợp lệ và ít nhất một ảnh đã gắn, đã xác minh. Khóa product trước kiểm tra ảnh giúp công bố và gỡ ảnh cuối không vượt điều kiện khi cạnh tranh. Trạng thái, version, audit và idempotency snapshot commit cùng transaction; không sửa SKU hoặc ảnh.

DRAFT/HIDDEN có thể công bố, PUBLISHED có thể ẩn. PUBLISHED chỉ đọc; ẩn trước khi chỉnh sửa thông tin/SKU/ảnh. Sửa HIDDEN giữ HIDDEN, không tự công bố lại. Nút Công bố phải chờ form được lưu; ảnh đã upload nhưng chưa gắn không đủ điều kiện. Storefront M2c2 đã nghiệm thu local ngày25/10; tại mốc đó chưa có reservation hoặc checkout. PUBLISHED không bảo đảm đủ tồn khi xác nhận checkout.

[Kế hoạch M2b2](tasks/slice-2-publication.md) ghi contract, policy và review focus. Root verify lúc **10:57:21 ngày 24/10 đạt 133 tests: 13 Identity + 44 Merchant + 76 Catalog**, zero fail/error/skip. Có 6 domain và 11 PostgreSQL API tests mới, gồm migration V2 có dữ liệu lên V3, replay/quyền hiện tại, version, rollback và race với gỡ ảnh cuối. Gates frontend lịch sử trước pause 11:05 ngày 24/10: build/lint/typecheck, **86 unit, 125 fixture production, 4 normal live, 2 media/publication live** đạt. Sau resume 24/10, main báo production build/lint/typecheck PASS, **86 unit, 144 fixture production PASS trong 23.8s**, gồm **19 regression revalidation**; 14 expected RED trên build cũ, focused 18 GREEN rồi thêm case thứ 19 về Create sau unmount vào full suite. Native audit mới exit 0/zero vulnerabilities; secret scan 216 Git-visible files/0 matches, env mode 600 và contract digest/57–51 refs hợp lệ. Hai asset trước pause đã được main exact lookup Cloudinary khoảng 15h: **all_missing=true, exit 0**. Final static review Heisenberg clear, không còn code blocker; P2 fixed/review accepted. **Normal live 4/4 PASS (6.7s)** trong `publication-resume-normal-live-green-20261024/`, gồm shared-session logout/re-login/editor-list clearing/recovery và giữ giá dirty khi focus. Lượt đầu bị test race chờ nav link luôn hiện; test-only fix chờ DELETE logout 204, production code không đổi. **Media/publication live 2/2 PASS (8.2s)** trong `publication-resume-media-live-20261024/`. M2b2 đã nghiệm thu local khoảng 15:40 +07:00 ngày 24/10: main xác minh hai asset mới DB DELETED/0 relation, cuối DRAFT/version 2 và HIDDEN/version 7; independent exact provider lookup 2 asset all_missing=true, exit 0. Hai asset trước pause cũng có proof riêng. Runtime cleanup đã xác nhận: Next và ba backend đã dừng, backend graceful exit 130; container `--rm` đã bị xóa sau kiểm đúng ID/label, docker stop exit 0; các cổng 3000/8081/8082/8083/9091/9092 đã đóng. ADMIN file ignored mode 600 hiện STALE, không giữ demo/named volume. Main xác nhận final secret scan sau cập nhật docs: 216 Git-visible files, 0 actual-secret matches; cả hai env mode 600. [Proof frontend](../frontend/docs/catalog-publication-proof.md) giữ riêng ngày và giới hạn; chưa có GitHub Actions run hoặc deployment thực tế.

Container `agora-catalog-live-20261024` trước pause và fixture nghiệm thu sau resume đều đã bị xóa; `.env.live-test` không còn dùng được. Chạy lại cần fixture/ADMIN mới. Native database giữ nguyên.

## Kiểm chứng backend M2a ngày 23/10/2026

Root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify` kết thúc 22:16:36, **82 tests: 13 Identity + 44 Merchant + 25 Catalog**, không fail/error/skip. Hai contract module và ba service đều build thành công; số này đã bỏ report lịch sử không còn source, không cộng nhầm IdentityApplicationTests từ 21/10.

Ba JAR vừa verify được copy sang thư mục test bị ignore để chạy với ba database Docker riêng. Probe HTTP/gRPC thật xác nhận: shop chưa ACTIVE bị chặn; ownership read/update trả 404 cho shop khác; sai CSRF bị từ chối; create/update replay giữ kết quả; SKU giữ ID theo code; stale version trả 409; key đổi payload trả 422; SKU trùng rollback toàn mutation. Thu hồi ACTIVE của shop hoặc khóa account chặn cả read/replay ngay ở request kế tiếp; version/SKU/audit/key counts không đổi sau các thao tác bị từ chối. Probe chỉ đổi trạng thái đối tượng tổng hợp trong container đã xác minh, không sửa native database.

Kết quả UI production/full browser/live được ghi riêng trong README và proof frontend sau khi chạy. Source review backend trước pause và frontend sau resume không có blocker tin cậy cao; review tĩnh không thay thế những gate runtime này. Chưa có GitHub Actions run hoặc deployment thực tế.

## Kiểm chứng M1 ngày 23/10/2026

Root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify` đạt 46 tests (13 Identity, 33 Merchant), không fail/error/skip. Có HTTP auth, email unique khi cạnh tranh, CSRF, logout, account bị khóa; gRPC kiểm tra caller, quyền mới nhất, anonymous/expired/suspended và idle timeout. Regression test chứng minh request gRPC cũ không đảo ngược lần login đổi session ID.

Merchant kiểm tra ownership, version, idempotency/replay/rollback, hai admin cạnh tranh và nâng schema V1 có dữ liệu lên V2. Probe HTTP qua hai service thật với PostgreSQL Docker riêng xác nhận cấp/thu hồi ADMIN có hiệu lực ngay; ADMIN đã bị thu hồi không thể đọc danh sách hoặc replay review đã thành công. Probe không cấp quyền cho tài khoản trong PostgreSQL native.

Kết quả giao diện và browser fixture/live nằm ở README repository frontend. Workflow [Backend CI](.github/workflows/ci.yml) dùng cùng lệnh verify đã chạy local, action ghim SHA và quyền chỉ đọc. Chưa có GitHub Actions run thực tế; chưa deploy hoặc tích hợp nhà cung cấp. Hướng dẫn demo ghi rõ cách ghép revision hai repo và phần live CI còn thiếu thông tin.

## Bắt đầu đọc

1. [Bản đồ nghiệp vụ](docs/product/capability-map.md)
2. [Use case và tiêu chí nghiệm thu](docs/product/use-cases.md)
3. [Trạng thái nghiệp vụ](docs/product/state-machines.md)
4. [Kiến trúc hệ thống](docs/architecture/architecture-overview.md)
5. [Cấu trúc thư mục](docs/architecture/project-structure.md)
6. [Mô hình dữ liệu](docs/architecture/data-model.md)
7. [Quy ước API](docs/architecture/api-conventions.md) và [OpenAPI](contracts/openapi/identity-merchant.v1.openapi.json)
8. [ADR nền tảng](docs/decisions/0001-foundation.md),
   [ADR tách Identity/Merchant](docs/decisions/0002-separate-identity-merchant.md)
   và [ADR chỉ dùng PostgreSQL](docs/decisions/0003-postgresql-only.md)
9. [Kế hoạch triển khai](tasks/plan.md)

Luồng đầu tiên: đăng ký, session login/logout, tạo/gửi duyệt shop và admin review. Tracker luồng đầu là `slice-1-onboarding`; tracker hiện hành là `slice-3-payment` theo [plan M3d](tasks/slice-3-payment.md); file plan là chỉ mục, không phải checklist thứ hai.

Repo frontend là repository riêng; contract backend là nguồn canonical. Chưa có deployment hoặc credential nhà cung cấp trong repo.

## Commerce/Catalog M3c — đã nghiệm thu local ngày27/10/2026

[OpenAPI checkout](contracts/openapi/commerce-checkout.v1.openapi.json), [CatalogCheckout proto](contracts/proto/agora/catalog/v1/catalog_checkout.proto), [plan](tasks/slice-3-checkout.md), [demo và evidence](docs/demo-checkout.md). Commerce có năm operation preview/confirm/recovery/list/detail đơn trên HTTP8084. Preview lấy quantity từ giỏ và snapshot giá server, giữ exact expiry5phút do Catalog DB cấp; preview chưa giữ tồn. Confirm commit durable attempt trước RPC; Catalog kiểm giá/product version nguyên tử cùng FEFO hold15phút và trả typed reservation hoặc permanent closed decision. Timeout chưa rõ giữ PROCESSING, recover đúng key/preview/checkout identity.

Catalog [V7](services/catalog-service/src/main/resources/db/migration/V7__checkout_decisions.sql) thêm checkout_decisions và accepted_not_after, giữ raw M3b hash/time semantics. Commerce [V2](services/commerce-service/src/main/resources/db/migration/V2__commerce_checkout.sql) thêm previews/attempts/orders snapshotJSONB. Order/attempt/dọn dòng selected commit cùng transaction; giỏ đổi version thì giữ nguyên, cartCleared=false. List/detail riêng buyer, snapshot không cần offer còn public. Tại nghiệm thu M3c, AWAITING_PAYMENT/EXPIRED tính khi đọc và chưa có PAID/CONSUMED/Payment. M3d hiện lưu order state theo settlement authority; xem [Payment](docs/payment.md). M3c đã nghiệm thu local ngày27/10/2026; bằng chứng final và giới hạn ghi ở demo checkout.


Root verify M3c **303 PASS =13 Identity+59 Merchant+191 Catalog+40 Commerce**,39current classes,0failure/error/skip ngày27/10/2026 lúc17:12:38+07:00; [log](target/checkout-proof-20261027/root-verify.log), [manifest180sourcefiles](target/checkout-proof-20261027/source-manifest.json). Catalog75focused/PauliCLEAR; Commerce40module/BanachCLEAR. Frontend final build VDJE0OiaLvYN78ua-ytLE, lint/typecheck/build PASS,143unit/272productionfixture/audit0. Real4app+Cloudinary1/1PASS10.046s và baseline5/5PASS12.560s, database/media/runtime cleanup đã xác minh; [exact evidence và giới hạn](docs/demo-checkout.md). Các kết quả này chỉ chứng minh revision M3c ngày27/10, không phải proof cho implementation M3d hiện tại; trạng thái M3d partial ghi ở đầu tài liệu.
