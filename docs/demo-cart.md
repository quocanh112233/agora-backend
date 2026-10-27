# Demo giỏ hàng — Commerce M3a

Ngày26/10/2026: Commerce là service thứ tư đã có implementation giỏ bền vững theo account. [Plan M3a](../tasks/slice-3-cart.md), [OpenAPI 1.0.0](../contracts/openapi/commerce-cart.v1.openapi.json), [CatalogOffers proto](../contracts/proto/agora/catalog/v1/catalog_offers.proto). M3a đã nghiệm thu local ngày 26/10 sau review, kiểm thử qua bốn service thật và cleanup Cloudinary. Bằng chứng theo từng lớp ghi ở cuối tài liệu.

## Chuẩn bị

Dùng luồng [onboarding](demo-onboarding.md), [Catalog/ảnh/công bố](demo-catalog.md), [lô hàng](demo-inventory.md), [storefront](demo-storefront.md). Main sở hữu runtime fixture bốn database PostgreSQL biệt lập/bốn app và frontend. Không dùng ADMIN/credential fixture cũ hoặc tạo/reset/migrate native PostgreSQL để làm proof. Chưa xác nhận đã tạo agora_commerce trên native. Cloudinary đã có config ignored; không xin lại credential hoặc in secret.

| Thành phần | HTTP loopback | gRPC loopback | Database/schema mặc định |
| --- | --- | --- | --- |
| Identity | 8081 | 9091, IDENTITY_GRPC_ENABLED=true | agora / identity |
| Merchant | 8082 | 9092, MERCHANT_GRPC_ENABLED=true | agora_merchant / merchant |
| Catalog | 8083 | 9093, CATALOG_GRPC_ENABLED=true | agora_catalog / catalog |
| Commerce | 8084 | Client Identity/Catalog, không listener mới | agora_commerce / commerce |

Main chọn DB fixture qua cấu hình runtime; tên trong bảng chỉ là mặc định. Commerce dùng COMMERCE_DB_NAME/COMMERCE_SERVER_PORT, không dùng nhầm Identity DB_NAME/SERVER_PORT. [Cấu hình Commerce](../services/commerce-service/src/main/resources/application.yaml), [Catalog](../services/catalog-service/src/main/resources/application.yaml), [V1 Commerce](../services/commerce-service/src/main/resources/db/migration/V1__commerce_cart.sql). Bốn app dùng cùng INTERSERVICE_TOKEN ngẫu nhiên32–256 URL-safe, plaintext chỉ loopback. Chạy service độc lập cần generated contracts Identity/Merchant/Catalog theo [README](../README.md#chạy-local). 

Chuẩn bị buyer đăng nhập, shop ACTIVE, product PUBLISHED có ảnh attached UPLOADED và SKU có lô ACTIVE còn hạn Việt Nam/available>0. Buyer có thể đồng thời là chủ shop; không có vai trò SHOPPER mới. Giỏ không yêu cầu địa chỉ hoặc GHN quote.

## Ba thao tác HTTP

Browser gọi cùng origin dưới /api/v1; Commerce HTTP 8084. Cả ba operation kiểm account/session hiện tại qua Identity. Mutation cần CSRF theo luồng có sẵn và Idempotency-Key UUID; không đưa cookie/token thật vào ví dụ.

| Operation | Input | Response200 |
| --- | --- | --- |
| GET /api/v1/me/cart | Không query | {cartId,version,items} |
| PUT /api/v1/me/cart/items/{skuId} | {cartId,productId,quantity,expectedVersion} | {version} |
| DELETE /api/v1/me/cart/items/{skuId} | {cartId,expectedVersion} | {version} |

GET khi chưa có row trả version 0/items rỗng/cartId ổn định, không ghi DB; giỏ đã xóa hết dòng giữ version đã tăng. Item chỉ productId/skuId/quantity/offer, sort SKU UUID ổn định. Giỏ nhiều shop, tối đa 20 SKU tổng cộng và1–99 gói/dòng. PUT là số lượng tuyệt đối: đổi 2 sang 3 gửi 3, không gửi delta 1.

cartId opaque ràng buộc account đã xác minh, không là credential; account đổi mà gửi cartId cũ thì 409 CART_CHANGED trước replay/write dù version trùng. Session 401/CSRF 403; cookie trùng bị từ chối. Field/query/UUID/key/quantity/version sai 422; duplicate/trailing/malformed JSON 400, body tối đa 4096 bytes. Version/cap/availability 409; dependency503, SQL defect500 an toàn. Response no-store/trace/security headers.

## Current offers và retry

GET đọc cart snapshot REPEATABLE_READ ngắn, đóng transaction trước RPC. ResolveOffers nhận1–20 SKU khác nhau, một SQL snapshot product/SKU/ảnh/stock rồi Merchant tối đa 4 batch × 5shop kiểm ACTIVE hiện tại. Missing/ineligible bị omit, Commerce giữ dòng với offer:null. Hết tồn vẫn có offer inStock=false. Dependency/malformed response503 toàn cart, không partial success hoặc fallback giá cũ.

Offer chỉ productId/skuId/shopId/shopName/productName/skuLabel/priceVnd/netWeightGrams/inStock/thumbnail; thumbnail chỉ url/width/height Cloudinary verified namespace đúng product. Không owner/contact/pickup, lot/count/version/audit, SKU code/shipping hoặc upload intent. Commerce kiểm subset/unique/product mapping/shape/bounds. [Allowlist và giới hạn](architecture/catalog-security.md#m3a--catalogoffers-cho-giỏ-hàng).

Catalog listener optional 9093 giới hạn4 KiB / 32 calls mỗi connection, token so constant-time; Commerce inbound64 KiB/deadline 10 giây. Identity 2 giây/call, Merchant 2 giây/call, không retry. Không có snapshot phân tán hoặc đảm bảo đủ exact quantity.

Auth/CSRF/cartId kiểm trước replay. Key scope actor+key; hash bind operation/cartId/pathSKU/toàn body. Cùng key/body trả version gốc, đổi payload 422. PUT preflight replay trước Catalog; mới thì kiểm public/inStock ngoài transaction, sau đó insert cart ON CONFLICT và khóa cart row để recheck replay/version/cap trước ghi. Lookup lỗi/không khả dụng cũng tra replay lần nữa để bắt original commit đồng thời. Item/version/request result commit cùng transaction; lỗi rollback toàn bộ.

Key giữ tối thiểu 7 ngày, scheduler mỗi giờ xóa record cũ hơn 7 ngày, không bảo đảm replay sau dọn. PUT quantity không đổi/DELETE dòng đã thiếu là no-op với version đúng. Replay không tăng version lần hai, có thể trả version cũ hơn giỏ hiện tại; cần GET mới sau 200. DELETE không gọi Catalog, vẫn cần Identity/CSRF/cartId/version đúng.

## Luồng demo

1. Đọc giỏ buyerA, thêm SKU từ detail, reload rồi đổi quantity tuyệt đối. GET mới phản ánh offer hiện tại; SKU shop khác được phép, tổng cap 20/quantity 99. Giỏ buyerB độc lập.
2. Khi mutation chưa rõ kết quả, retry đúng key/body: cùng version, không cộng gói lần hai. Hai ý định cùng version chỉ một thay đổi thành công; 409 cần refresh, không tự ghi đè hoặc đổi key của intent chưa rõ.
3. Đổi account ở tab khác rồi gửi cartId/intent cũ: 409 CART_CHANGED. UI reset intent/dữ liệu khi cartId đổi, bỏ response cũ; 401/403 xóa dữ liệu riêng.
4. Seller đổi giá/ẩn product hoặc đình chỉ shop fixture: GET mới lấy giá mới hoặc offer:null. Hết tồn inStock=false, PUT mới 409. Không dùng giá/tồn cũ như cam kết mua hàng.
5. Mutation 200 phải GET mới trước PUT tiếp. Refresh 503 bỏ offer/tên/giá/ảnh cũ, chỉ giữ cartId/version vừa biết cùng productId/skuId/quantity của chính giỏ để DELETE. Catalog down vẫn xóa được; version replay cũ có thể 409 và cần refresh. Không chuyển refs giữa account.
6. DELETE dòng mất public rồi GET lại; dòng đã thiếu/version đúng là no-op. Quan sát giỏ không tạo reservation/Order, không đổi on_hand/held/allocated và không có checkout/thanh toán giả.

Không guest cart/merge/promotion/shipping quote/xóa toàn giỏ trong M3a. Tại nghiệm thu M3a ngày26/10, M3b reservation, M3c checkout và M3d Payment là bước sau. Theo ADR-0004 ngày27/10 đã bỏ GHN; M3b nay đã nghiệm thu và M3c có implementation đã nghiệm thu local ngày27/10/2026; [demo checkout](demo-checkout.md), [plan](../tasks/slice-3-checkout.md). Giỏ nhiều shop không đổi chính sách checkout một shop; inStock không hứa giữ/mua/giao hàng.

## Nghiệm thu local ngày 26/10/2026

- Backend root lúc 10:55:16 +07:00: **226 tests = 13 Identity + 59 Merchant + 130 Catalog + 24 Commerce**, không fail/error/skip; review backend CLEAR. Sau resume, hash 150 file source/config/contracts khớp manifest đã build, không chạy lại Maven vì backend không đổi.
- Frontend cuối: lint/typecheck/build đạt, build ID `DLU8w9nCv4HmJUggCkzWP`; **136 unit / 243 fixture production / 5 normal live / 1 cart-provider live PASS**. Full fixture 47.1s, không skip/flaky; normal live 10.6s, provider 6.5s. Dependency audit 0 vulnerabilities.
- Review frontend phát hiện P1: GET đến muộn hiện lại dữ liệu sau mutation 401/403; P2: replay version cũ ghi đè refs mới đã quan sát. Hook đã abort/fence read theo thế hệ quyền và giữ snapshot có version bằng hoặc cao hơn kết quả replay. Năm regression RED trên bundle cũ, GREEN trên bundle mới; independent re-review CLEAR.
- Provider attempt đầu lỗi bài test đọc response body sau navigation. Đã lưu body trước chuyển trang; lượt cuối qua đầy đủ add/reload, block/unblock, giá 35.000→45.000, quantity 2→3, replay, cartId khác account bị 409, ẩn sản phẩm rồi xóa, logout xóa private view. Không dùng API interception.
- Main đã xem screenshot cart mobile 320px, detail 1440px và cart live; fixture kiểm bốn chiều rộng, keyboard/skip link, axe và touch Chromium mô phỏng iPhone 13. Không suy ra kiểm thiết bị thật, screen reader, Firefox/WebKit hoặc HTTPS production.

[Proof frontend](../../frontend/docs/cart-proof.md) giữ output paths, contract pin và giới hạn. Backend dùng PostgreSQL Docker biệt lập cho cả bốn database, bốn JAR đã verify và ADMIN thuộc đúng fixture. Không tạo/migrate/reset native database để làm proof. Chưa xác nhận GitHub Actions chạy xuyên repo, chưa deploy.

### Cleanup đã xác minh

| Lượt | Product / upload | Bằng chứng |
| --- | --- | --- |
| Provider attempt đầu | `5e75072e-c2ff-491a-9001-181df148c361` / `fc17ac2f-387a-4661-b08d-f1fa593aa72e` | DB DELETED, 0 attached, product HIDDEN/version 7; independent exact Cloudinary lookup missing. |
| Provider cuối | `0e3c0c4e-0bc3-462e-a362-789ed3dcd3f0` / `8837e8ff-2a3e-4769-b765-ca6122211771` | DB DELETED, 0 attached, product HIDDEN/version 7; independent exact Cloudinary lookup missing. |

Lô cuối `ACTIVE/on_hand=12/held=0/allocated=0/version=2`: thêm/sửa/xóa giỏ không giữ hay trừ tồn. Commerce của buyer cuối version 3, 0 dòng, đúng 3 request records; replay không ghi thêm. Buyer của lượt lỗi còn version 2/1 dòng/2 records trước khi xóa toàn fixture, không phải trạng thái của lượt thành công.

Next và bốn backend đã dừng graceful; container `agora-cart-live-20261026` có ID bắt đầu `3977f747` đã xóa sau kiểm đúng owner label. Cổng 3000/8081–8084/9091–9093/32768 đóng; native PostgreSQL5432 giữ nguyên. `.env.live-test` vẫn ignored/mode600 nhưng **STALE** vì fixture đã xóa; lần live sau cần fixture/ADMIN mới. Runtime artifacts local nằm ở ignored `target/live-cart-20261026/`, không là bootstrap có thể tái dùng nguyên trạng. Không có named demo volume.

[Kiến trúc](architecture/architecture-overview.md#m3a--giỏ-hàng-trong-commerce), [dữ liệu](architecture/data-model.md#commerce-m3a), [API](architecture/api-conventions.md#7-commerce-m3a), [CartService](../services/commerce-service/src/main/java/com/agora/commerce/application/CartService.java), [JdbcCartStore](../services/commerce-service/src/main/java/com/agora/commerce/adapter/outbound/JdbcCartStore.java), [CatalogOffersService](../services/catalog-service/src/main/java/com/agora/catalog/application/CatalogOffersService.java).
