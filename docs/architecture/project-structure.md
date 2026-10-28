# Agora — Cấu trúc thư mục

## Phạm vi cấu trúc — 28/10/2026

[ADR-0004](../decisions/0004-order-and-payment-only.md) giữ sáu service Identity/Merchant/Catalog/Commerce/Payment/Feedback. Commerce sở hữu giỏ/checkout/đơn, capture application và notification dự kiến; bỏ Fulfillment, không adapter GHN, shipment, pickup hoặc module tính phí/kiện/địa chỉ trong target. Hồ sơ Merchant/contact/pickup_address, metadata SKU và migration đã có giữ nguyên như legacy.

[M3c](../../tasks/slice-3-checkout.md) đã có implementation đã nghiệm thu local ngày27/10/2026: dùng Catalog contract/listener9093 hiện có cho quote/hold giá-version và Commerce house layers cho preview/attempt/recovery/orders. Không thêm service/common library. M3d đã có Payment/VNPAY adapter, Commerce capture application và HELD → CONSUMED giảm held/on_hand nguyên tử trong implementation/tests local; nghiệm thu còn partial, chờ actual provider và proof cuối của main; không SHIPPED giả. Order đích AWAITING_PAYMENT → PAID terminal hoặc EXPIRED/CANCELLED; Feedback xác minh dòng đơn PAID. Refund tài chính vẫn trong Payment; claim chất lượng sau giao ngoài phạm vi. Cây dưới phân biệt thành phần hiện có với đường dẫn dự kiến; các proof M2/M3a/M3b giữ theo ngày.

## Catalog M3b — thành phần reservation

`domain/Reservation.java` chứa immutable Request/Line/Item/Allocation và canonical hash; `application/ReservationService.java` điều phối Merchant/replay và expiry scheduler; port `ReservationStore.java` và adapter `JdbcReservationStore.java` giữ transaction/khóa/counters. `adapter/inbound/ReservationRpc.java` map protobuf qua service, đăng ký cùng Offers trong CatalogGrpcServer. [Proto](../../contracts/proto/agora/catalog/v1/catalog_reservations.proto) được build bởi catalog-contract hiện có, không thêm module/dependency. [V6](../../services/catalog-service/src/main/resources/db/migration/V6__reservations.sql) thêm reservation schema.

Tests: ReservationDomainTests, ReservationServiceTests, ReservationStoreTests, ReservationGrpcTests và ReservationRpcIntegrationTests. PostgreSQL test biệt lập; integration dùng listener TCP thật, Merchant fixture gRPC, Clock kiểm soát. Tại nghiệm thu M3b 27/10 lúc10:14:28+07:00, frontend/Commerce HTTP không thay đổi; source M3c hiện có được ghi riêng. [Guide và evidence](../demo-reservations.md).

Trạng thái 26/10/2026: Identity/Merchant/Catalog đã nghiệm thu local đến M2c2; Commerce là service thứ tư đã triển khai giỏ M3a. Root backend 226 tests đạt theo main 10:55:16 +07:00; M3a đã nghiệm thu local ngày 26/10 sau review, live và cleanup. Payment/Feedback và notification chưa triển khai tại mốc lịch sử này. backend/frontend là hai repo riêng; không tạo repo thứ ba hoặc phụ thuộc filesystem repo kia khi build. Mọi service dùng PostgreSQL theo [ADR-0003](../decisions/0003-postgresql-only.md).

## 1. Repo backend

```text
backend/
├── pom.xml                         # Parent + reactor; Java/BOM/plugin thống nhất
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper được ghim
├── services/
│   ├── identity-service/           # Đã có
│   ├── merchant-service/           # Đã có, tách riêng
│   ├── catalog-service/            # Product/SKU, ảnh, visibility, lô, public read và reservation M3b
│   ├── commerce-service/           # Giỏ M3a; checkout/đơn M3c đã nghiệm thu local ngày27/10/2026, HTTP8084
│   ├── payment-service/            # M3d implementation/tests local; HTTP8085, provider proof pending
│   └── feedback-service/           # Dự kiến; JDBC/Flyway/PostgreSQL riêng
├── libraries/
│   ├── identity-contract/          # Sinh Java/protobuf từ contract Identity
│   ├── merchant-contract/          # Sinh Java/protobuf từ contract Merchant
│   ├── catalog-contract/           # Offers, Reservations (gồm Consume), Checkout v1
│   └── commerce-contract/          # CommercePayments v1, listener Commerce9094
├── contracts/
│   ├── openapi/                    # Nguồn HTTP contract; JSON OpenAPI
│   ├── proto/                      # Nguồn .proto khi có service cần giao tiếp
│   └── events/                     # Schema integration event có version
├── infra/
│   ├── compose.local.yaml
│   └── postgres/                   # Tạo database/role cho môi trường local
├── docs/
│   ├── product/
│   ├── architecture/
│   ├── payment.md                 # Hành vi, cấu hình và giới hạn proof M3d
│   └── decisions/
├── tasks/plan.md                   # Chỉ mục kế hoạch; tracker canonical: claude-mem
└── .github/workflows/
```

Reactor hiện có bốn contract module identity-contract/merchant-contract/catalog-contract/commerce-contract và năm service identity-service/merchant-service/catalog-service/commerce-service/payment-service; Feedback là service thứ sáu dự kiến. Maven Wrapper 3.9.16 đã ghim checksum. Backend/.env bị Git ignore và chỉ dùng local; .env.example được phép commit. Database agora/agora_merchant/agora_catalog chạy trên PostgreSQL native hiện có, chưa cần infra/compose.local.yaml. Service không phụ thuộc JAR của service khác; được dùng generated contract cần thiết. Commerce cấu hình agora_commerce/schema commerce, fixture dùng database test riêng; chưa xác nhận tạo/migrate native. Payment đã có module và migration V1/V2, mặc định database agora_payment/schema payment; demo M3d dùng năm database Docker biệt lập, không thay đổi native database. Các đường dẫn Feedback/infra trong cây là thiết kế, không mặc định đã tạo file hoặc database.

## 2. Một service theo DDD

```text
services/identity-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/agora/identity/
    │   │   ├── IdentityApplication.java
    │   │   ├── domain/             # Account và quy tắc nghiệp vụ
    │   │   ├── application/        # Use case/port
    │   │   ├── adapter/
    │   │   │   ├── inbound/        # HTTP/gRPC khi có chức năng
    │   │   │   └── outbound/       # Persistence/provider adapter
    │   │   └── configuration/      # Wiring, security, serialization
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/       # Flyway, service sở hữu migration
    └── test/java/com/agora/identity/
        └── IdentityApiTests.java  # Cùng các unit test domain/security
```

- Domain chỉ dùng Java và abstraction nghiệp vụ; không import Spring/JPA/HTTP/SDK provider. Domain repository không extends JpaRepository.
- Application phụ thuộc domain và port; không gọi thẳng controller hoặc persistence adapter.
- Adapter inbound xác thực/parse request rồi gọi use case; adapter outbound hiện thực port, map domain với row PostgreSQL bằng JDBC. Feedback theo cùng cách, không có document repository/driver MongoDB.
- JpaRepository, DTO HTTP, protobuf generated type và persistence entity không được dùng như domain model xuyên các lớp.
- Main class ở package gốc; feature/module ở package bên dưới, không gom mọi controller/service/repository toàn ứng dụng.
- Context cùng process gọi interface ứng dụng của nhau, không truy cập persistence của nhau. Annotation/framework được đặt ngoài domain.
- Domain event là khái niệm nội bộ; integration event là hợp đồng đã map và có version, không serialize nguyên aggregate.
- Chưa tạo thư viện common/domain dùng chung. Chỉ tách phần kỹ thuật đã có nhu cầu lặp rõ ràng.

Identity và Merchant là hai process/database độc lập. Merchant có cây DDD tương tự, gọi gRPC Identity để xác minh principal/CSRF, không đọc database agora. Chưa có Dockerfile hoặc service Feedback; khi triển khai Feedback, migration nằm trong resources/db/migration của chính service, database dự kiến agora_feedback.

## 3. Contracts và generated code

- OpenAPI canonical ở contracts/openapi; implementation và test phải khớp cùng revision. Không duy trì một bản sửa tay thứ hai ở frontend.
- .proto đặt theo provider/context/version trong contracts/proto khi cần. Generated Java nằm target/generated-sources, không sửa tay/commit build output.
- Frontend nhận type/client HTTP từ artifact contract được ghim revision; không import filesystem ngoài repo để build.
- Mỗi integration schema ghi producer/consumer, ngữ nghĩa idempotency, thời gian, retry và compatibility.

## 4. Repo frontend

```text
frontend/
├── package.json, package-lock.json
├── next.config.ts, tsconfig.json
├── src/
│   ├── app/
│   │   ├── (storefront)/
│   │   ├── (auth)/login/, (auth)/register/
│   │   ├── seller/onboarding/
│   │   └── admin/shops/
│   ├── features/
│   │   ├── auth/
│   │   ├── merchant/
│   │   ├── catalog/
│   │   ├── cart/
│   │   ├── checkout/
│   │   └── feedback/
│   ├── components/ui/
│   ├── lib/api/                    # HTTP, error mapping, CSRF
│   └── generated/                  # Type/client từ contract ghim revision
├── public/
├── tests/e2e/
├── docs/project-structure.md
└── .github/workflows/
```

Trong feature chỉ thêm components/, api.ts, schemas.ts, store.ts hoặc test khi thực sự có mã. app/ ghép route/layout; nghiệp vụ UI nằm feature. Component chung ở components/ui chỉ khi được dùng lại.

Zustand dành cho trạng thái tương tác phía client, không giữ session ID hoặc coi giá/tồn kho frontend là sự thật. Server Component không đọc/ghi global store dùng chung người dùng. Dữ liệu /me và admin không cache công khai; UI bảo vệ route không thay phân quyền backend.

Next.js 16 App Router, TypeScript, npm; ghim patch cùng package-lock khi scaffold. Version React theo Next đã chọn. ESLint chạy bằng script riêng, không giả định next lint tồn tại.

## 5. Kiểm tra và cấu hình

Hiện có unit/integration tests, PostgreSQL Testcontainers và Playwright fixture/live cho M1; kết quả ở README hai repo. Feedback cũng dùng PostgreSQL Testcontainers khi triển khai; chưa có ArchUnit. Test fixture không chứa thông tin đăng nhập thật.

.env.example chỉ có tên biến/giá trị minh họa; .env.local và secret không commit. Migration append-only sau khi đã áp dụng; schema không để ORM tự cập nhật trong môi trường demo deploy.

Từ backend chạy ./mvnw verify để build/test, hoặc ./mvnw -pl services/identity-service spring-boot:run. Test và plugin run dùng cwd repo backend để Spring đọc .env tự động; chạy từ repo root, không từ thư mục service. Xem [README](../../README.md) và [plan](../../tasks/plan.md).

Nguồn: [Spring Boot structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html), [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [Next.js structure](https://nextjs.org/docs/app/getting-started/project-structure).

## M2c1 — thành phần đã triển khai

Catalog thêm `domain/InventoryLot`, `application/InventoryStore`/`InventoryService`, `adapter/inbound/InventoryController`, `adapter/outbound/JdbcInventoryStore`, `configuration/InventoryConfiguration` và migration `V4__inventory_lots.sql`. ProductService/JdbcProductStore giữ khóa product và upsert SKU thay vì xóa toàn bộ. Test `InventoryLotTests`/`InventoryApiTests` có17ca mới trong root150tests; không tạo module hay dependency mới.

## M2c2 — public browse/detail đã triển khai, đã nghiệm thu local

Catalog thêm `application/StorefrontQuery`, `StorefrontService`, `StorefrontStore`, `PublicShops`; inbound `StorefrontController`, outbound `JdbcStorefrontStore`/`GrpcPublicShops` và migration `V5__public_product_browse.sql`. Merchant thêm public `{id,name}` projection qua `ShopService` và endpoint `MerchantGrpcServer` cho `MerchantAccess.FindPublicShops`. Không thêm module/dependency hoặc đọc database xuyên service.

[Contract public riêng](../../contracts/openapi/catalog-storefront.v1.openapi.json) chỉ có hai GET list/detail, tách seller/media/inventory; projection không dùng nguyên Product/Shop private. Các test storefront hiện có `StorefrontQueryTests`, `StorefrontApiTests`, `StorefrontServiceTests` và Merchant `MerchantPublicShopGrpcTests`; [demo](../demo-storefront.md) ghi backend proof25/10, frontend/live/review đã đạt, xem bằng chứng cuối trong demo storefront. M2c2 đã nghiệm thu local; review/Q&A của UC-05 chưa triển khai.

Contract riêng `contracts/openapi/catalog-inventory.v1.openapi.json`; frontend ghim snapshot/type, route `/seller/products/[productId]/lots`, feature `catalog-inventory`, fixtures và live spec. [Demo](../demo-inventory.md) và [proof](../../../frontend/docs/catalog-inventory-proof.md) ghi177fixture/98unit/5live cùng giới hạn nghiệm thu local.

## M3a — thành phần đã triển khai

[Commerce](../../services/commerce-service/src/main/java/com/agora/commerce/CommerceApplication.java) giữ house layers: domain Cart/CartFailure; application CartService/CartStore/IdentityAccess/CatalogOffers; inbound CartController/ApiErrors/metadata; outbound JdbcCartStore/GrpcIdentityAccess/GrpcCatalogOffers và gRPC wiring. [V1](../../services/commerce-service/src/main/resources/db/migration/V1__commerce_cart.sql) tạo ba bảng commerce. Không phụ thuộc JAR service khác hoặc thêm common library.

Catalog thêm [CatalogOffersService](../../services/catalog-service/src/main/java/com/agora/catalog/application/CatalogOffersService.java), batch projection StorefrontStore/JdbcStorefrontStore và optional [CatalogGrpcServer](../../services/catalog-service/src/main/java/com/agora/catalog/configuration/CatalogGrpcServer.java) 9093. [Proto](../../contracts/proto/agora/catalog/v1/catalog_offers.proto) sinh Java qua [catalog-contract](../../libraries/catalog-contract/pom.xml). HTTP Catalog giữ ranh giới seller/public. [Demo giỏ](../demo-cart.md) ghi proof M3a đã nghiệm thu local ngày26/10; không suy ra checkout M3c đã triển khai.

## M3c — thành phần hiện có, đã nghiệm thu local ngày27/10/2026

Catalog thêm domain Checkout, application CheckoutService/CheckoutStore, inbound CheckoutRpc; JdbcReservationStore hiện thực quote/replay/accepted hold với savepoint và permanent decision. CatalogGrpcServer đăng ký CatalogCheckout trên listener hiện có. [Proto](../../contracts/proto/agora/catalog/v1/catalog_checkout.proto) dùng catalog-contract; [V7](../../services/catalog-service/src/main/resources/db/migration/V7__checkout_decisions.sql) thêm decision/accepted_not_after.

Commerce thêm domain Checkout (Preview/Snapshot/Attempt/Work/Order/Summary/Page), application CheckoutService/CheckoutStore/CheckoutCatalog, inbound CheckoutController, outbound JdbcCheckoutStore/GrpcCheckoutCatalog; tái sử dụng IdentityAccess/cart/catalogChannel. [V2](../../services/commerce-service/src/main/resources/db/migration/V2__commerce_checkout.sql) tạo previews/attempts/orders JSONB, không thêm service/dependency hoặc bảng order_lines. Tại revision M3c ngày27/10, CheckoutService recover attempt mỗi10giây và order expiry tính khi đọc; M3d thay bằng stored order state theo settlement authority như ghi bên dưới.

[OpenAPI](../../contracts/openapi/commerce-checkout.v1.openapi.json), [plan](../../tasks/slice-3-checkout.md), [demo/evidence](../demo-checkout.md). CheckoutStoreTests và CheckoutAdmissionTests có final evidence: Catalog75focused gồm13CheckoutStore/PauliCLEAR, Commerce40module/BanachCLEAR; root303PASS trên39current classes, live/cleanup đã được main xác minh. Exact gates ở demo checkout.

## M3d — Payment, capture application và consumption đã có local

[Payment](../payment.md) ghi trạng thái partial ngày28/10/2026 và các provider gate còn pending; [plan](../../tasks/slice-3-payment.md) là chỉ mục thiết kế, không thay proof nghiệm thu. Theo main, lượt tối projection83PASS, wake86PASS và read-only reviewCLEAR là các mốc trước root. Root verify local do main xác minh ngày **28/10/2026 lúc19:53:44+07:00: BUILD SUCCESS, 443 PASS =13 Identity+59 Merchant+206 Catalog+77 Commerce+88 Payment, 0failure/error/skip**. [root log](../../target/payment-proof-20261028-evening/root-verify.log), [manifest238sourcefiles](../../target/payment-proof-20261028-evening/source-manifest.json), [năm verified JAR](../../target/payment-proof-20261028-evening/verified-jars.json). Main đang boot năm JAR trên fixture mới; actual provider/live và nghiệm thu toàn M3d vẫn pending.

Payment HTTP8085 theo house layers: domain Payment/PaymentFailure; application PaymentService/PaymentNotifications/PaymentReconciliation/ProviderReconciliation và ports IdentityAccess/PaymentOrders/PaymentStore/PaymentProvider/ProviderJobs. Inbound PaymentController/VnpayController; outbound GrpcIdentityAccess/GrpcPaymentOrders, JdbcPaymentStore/JdbcProviderJobs và VnpayCodec/VnpayApiCodec/VnpayApiClient/VnpayProvider. [V1](../../services/payment-service/src/main/resources/db/migration/V1__payments.sql) lưu attempts/captures/ledger/capture jobs/observations/refunds/provider queries; [V2](../../services/payment-service/src/main/resources/db/migration/V2__provider_query_wakeups.sql) thêm query_wakeups bền vững. Wakeup không bị mất khi query đang leased; claim/complete dùng lease/revision, HTTP/gRPC ngoài DB transaction.

Commerce thêm CaptureApplication, PaymentApplicationService/PaymentStore/ReservationSettlement, PaymentRpc, JdbcPaymentStore/GrpcReservationSettlement và CommerceGrpcServer9094; [V3](../../services/commerce-service/src/main/resources/db/migration/V3__order_payments.sql) lưu state/winner/receipts/jobs. [CommercePayments proto](../../contracts/proto/agora/commerce/v1/commerce_payments.proto) sinh Java qua commerce-contract; listener bật bằng COMMERCE_GRPC_ENABLED, mặc định false. Public order reads dùng stored state; PAID chỉ sau xác nhận Catalog CONSUMED đúng capture. Catalog [V8](../../services/catalog-service/src/main/resources/db/migration/V8__reservation_consumption.sql) thêm consumed capture/time, terminal constraints và auditCONSUME trong reservation hiện có; không thêm service/common library hoặc transaction xuyên database.

PaymentApiTests và VnpayCodecTests/VnpayApiCodecTests/VnpayApiClientTests kiểm tra API, codec, persistence và provider fixtures; CommercePaymentTests/PaymentRecoveryTests/PaymentRpcTests/PaymentMigrationTests và ReservationConsumptionTests kiểm tra settlement/recovery. Fixture không chứng minh provider sandbox thật chấp nhận thanh toán. Refund chỉ tài chính: reserve/ledger không tự suy thành SUCCEEDED, lost reply cần reconciliation và không gửi lại refund chưa rõ; không tăng lại inventory hoặc đảo terminalPAID.
