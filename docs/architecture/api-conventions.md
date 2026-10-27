# Agora — Hợp đồng HTTP và gRPC

## CatalogReservations M3b

[Proto](../../contracts/proto/agora/catalog/v1/catalog_reservations.proto) bổ sung Hold/Get/Release trên listener9093. Caller server tin cậy xác thực buyer trước RPC; token nội bộ kiểm trước mọi validation/persistence, buyer/checkout binding luôn kiểm trước replay. Hold nhận buyer/checkout/shop UUID, 1–20 dòng product/SKU UUID khác nhau, quantity1–99. Get/Release nhận buyer+checkout. Không endpoint HTTP public mới.

Response HELD/RELEASED, deadline cố định15phút, release reason CANCELLED/EXPIRED, dòng và lot references chỉ nội bộ. Timestamp epoch microseconds. Idempotency theo checkout vĩnh viễn; cùng payload trả trạng thái hiện tại, không snapshot HELD cũ hoặc gia hạn. Cùng checkout khác payload ALREADY_EXISTS; không thuộc buyer NOT_FOUND.

Status safe: INVALID_ARGUMENT input, FAILED_PRECONDITION eligibility/thiếu tồn, RESOURCE_EXHAUSTED vượt giới hạn lô, UNAVAILABLE dependency/contention/timeout, INTERNAL lỗi lập trình. Không trả SQL/secret/message exception. Request4KiB/maxcalls32 kế thừa listener; reservation caller dùng inbound128KiB/không retry transport. Offers client64KiB vẫn chỉ gọi Offers, chưa có caller reservation trong Commerce. Request chưa rõ kết quả phải đối soát/retry cùng checkout_id. [Guide/proof](../demo-reservations.md).

Nguồn machine-readable: [OpenAPI 3.1 JSON](../../contracts/openapi/identity-merchant.v1.openapi.json). Bundle của hai service tách riêng; x-service xác định provider. Ngày 23/10/2026, cả 11 thao tác dưới đây đã triển khai, gồm account/session/CSRF và shop draft/submit/admin review/resubmit. OpenAPI vẫn giữ nguyên revision dùng chung với frontend; mô tả lịch sử trong snapshot không phải báo cáo tiến độ.

Catalog có artifact riêng [catalog.v1.openapi.json](../../contracts/openapi/catalog.v1.openapi.json) cho năm thao tác seller product list/create/read/update/visibility; media và inventory có contract riêng. M2c2 public browse/detail đã triển khai theo [catalog-storefront.v1.openapi.json](../../contracts/openapi/catalog-storefront.v1.openapi.json)1.0.0, đã nghiệm thu local ngày 25/10/2026. Không sửa artifact onboarding để thêm Catalog; [ranh giới Catalog](catalog-security.md) phân biệt quyền seller/public.

Commerce M3a đã có [OpenAPI giỏ1.0.0](../../contracts/openapi/commerce-cart.v1.openapi.json) với ba operation cho người mua đăng nhập; xem [mục7](#7-commerce-m3a). [Demo giỏ](../demo-cart.md) ghi root backend26/10 đạt, frontend/live/review M3a đã hoàn tất ngày 26/10.

## 1. Phạm vi và tuyến

Base path /api/v1, JSON camelCase, enum UPPER_SNAKE_CASE, UUID và timestamp RFC3339. Web gọi cùng origin qua allowlisted rewrites; backend vẫn xác thực mọi request.

| Method/path dưới /api/v1 | Quyền | Kết quả chính |
|---|---|---|
| GET /auth/csrf | Công khai | Token CSRF; có thể tạo anonymous session |
| POST /auth/registrations | Công khai + CSRF | Tạo USER, 201; không tự đăng nhập |
| POST /auth/sessions | Công khai + CSRF | Đăng nhập, 200 + HttpOnly cookie; sai thông tin 401 |
| DELETE /auth/sessions/current | Session + CSRF | Vô hiệu hóa, 204, xóa cookie |
| GET /me | Session | Hồ sơ/roles hiện tại, 200; mất phiên 401 |
| GET /me/shop | Session | Shop của mình, 200; chưa có 404 |
| POST /shops | Session + CSRF + Idempotency-Key | Tạo DRAFT, 201; đã có shop 409 |
| PATCH /shops/{shopId} | Owner + CSRF + Idempotency-Key | Sửa DRAFT/REJECTED, 200; cần expectedVersion |
| POST /shops/{shopId}/submissions | Owner + CSRF + Idempotency-Key | DRAFT thành PENDING_REVIEW, 200 |
| GET /admin/shops | ADMIN | Danh sách có phân trang/lọc status |
| POST /admin/shops/{shopId}/reviews | ADMIN + CSRF + Idempotency-Key | Duyệt/từ chối đúng expectedVersion, 200 |

Không có role trong input đăng ký; JSON field lạ bị từ chối ở request schema. Địa chỉ/tài khoản không công khai trong response của API người dùng khác.

## 2. Session, CSRF và cache

Cookie tên AGORA_SESSION, Path=/, HttpOnly, SameSite=Lax; Secure trên HTTPS. Local HTTP có profile riêng. Token CSRF lấy qua /auth/csrf, gửi header X-CSRF-TOKEN trên request thay đổi dữ liệu. Header này không thay session cookie.

Sau login/logout lấy CSRF token mới. Login handler phải dùng lifecycle Spring Security để đổi session ID, gọi session strategy và lưu SecurityContext; không chỉ tự gắn một attribute rồi coi đã login.

Mọi response chứa session/CSRF/profile/owner/admin data có Cache-Control: no-store. Không public-cache dữ liệu xác thực ở Next hoặc proxy. Server Component gọi dữ liệu riêng phải chuyển cookie phù hợp và không dùng cache dùng chung.

Chỉ chấp nhận các định dạng và kích thước body đã cấu hình. Đăng ký/login cần rate limit; MVP một instance dùng limiter bộ nhớ có giới hạn, ghi rõ không phải rate limit phân tán. Không ghi log thông tin xác thực.

Bootstrap ADMIN bằng [script SQL chạy thủ công](../../scripts/grant-local-admin.sql) cho một tài khoản ACTIVE đã tồn tại trong môi trường local/test; không có API public cấp ADMIN hoặc mật khẩu mặc định commit trong repo. Xem [demo](../demo-onboarding.md) trước khi dùng script; không chạy vào database khác ngoài database Identity đã chọn.

## 3. Lỗi và phân trang

Error body thống nhất: code (machine-readable), message, traceId, details là danh sách field/message khi phù hợp. HTTP 400 JSON sai cú pháp, 401 thiếu/sai session, 403 không có quyền hoặc CSRF sai, 404 không tồn tại/che tài nguyên chéo owner, 409 xung đột trạng thái/version/đang xử lý, 422 input không hợp lệ hoặc key đổi payload, 429 rate limit, 500 lỗi đã che chi tiết.

CSRF filter có thể trả 403 trước bước xác thực; không hứa mọi request anonymous thay đổi dữ liệu đều trả 401. Khi kiểm thử phân quyền, dùng CSRF hợp lệ để đo đúng điều kiện.

List admin: page bắt đầu 0, pageSize mặc định 20 tối đa 100; sort cố định createdAt giảm dần rồi id giảm dần. Response gồm items, page, pageSize, totalItems. Không nhận tên cột sort tùy ý.

## 4. Idempotency và cạnh tranh onboarding

- Client sinh UUID một lần cho một ý định, giữ nguyên qua retry; thao tác khác có key mới.
- Key scope theo actor + operation + key; payload hash gồm body và resource ID, không gồm cookie/traceId.
- Claim bằng unique constraint trong transaction; không SELECT rồi INSERT không có bảo vệ.
- Key cũ + payload khác trả 422 IDEMPOTENCY_PAYLOAD_MISMATCH; request đang xử lý trả 409 IDEMPOTENCY_IN_PROGRESS; đã xong replay status/body gốc sau khi kiểm tra quyền hiện tại.
- Kết quả lỗi validation/auth không cần giữ; kết quả business và thay đổi dữ liệu cần chiến lược transaction nhất quán. Không đánh dấu thành công trước commit.
- expectedVersion bảo vệ hai ý định khác nhau cùng tác động Shop; key không thay version lock.
- Form không tự retry đăng nhập/đăng ký; mất response đăng ký có thể login hoặc dùng khôi phục, unique email ngăn account trùng.

## 5. gRPC và sự kiện

Identity/Merchant tách ứng dụng theo ADR-0002. Trước khi Merchant gọi xác thực, định nghĩa .proto theo provider/version và contract test; không dùng interface in-process hoặc database chung để vượt ranh giới.

Hợp đồng [SessionIdentity v1](../../contracts/proto/agora/identity/v1/session_identity.proto) nhận `session_id`, `csrf_token`, `require_csrf`, trả `account_id` và `roles`. Merchant suy ra `require_csrf` từ HTTP method, không nhận quyết định này từ body của trình duyệt. Nguồn role/trạng thái account là Identity, ownership là Merchant; không trả email, password hoặc cookie trong response RPC.

Caller gửi metadata `x-agora-internal-token`, cấu hình bằng `INTERSERVICE_TOKEN` ngẫu nhiên 32–256 ký tự URL-safe, không hardcode. Local listener bind `127.0.0.1:9091`; plaintext chỉ dành cho loopback. Trước khi chạy qua mạng/container riêng phải thiết kế TLS/mTLS, không chỉ mở rộng bind. Không dùng bảng session chung hoặc header X-User-Id tự khai từ browser. Client đặt deadline 2 giây cho từng call, không tự retry; UNAUTHENTICATED/PERMISSION_DENIED/UNAVAILABLE tương ứng HTTP 401/403/503.

Request seller Catalog gọi Identity rồi [MerchantAccess.ResolveActiveShop](../../contracts/proto/agora/merchant/v1/merchant_access.proto) ở loopback 9092. Merchant chỉ trả shop_id đang ACTIVE thuộc account_id đã được caller xác minh qua Identity. Cùng metadata nội bộ, deadline 2 giây/call, không cache quyền hoặc bỏ kiểm tra khi replay. INVALID_ARGUMENT cho account ID sai; chưa có shop/shop không ACTIVE trả PERMISSION_DENIED; lỗi DB trả UNAVAILABLE. Chi tiết ranh giới và race giữa các service ở [catalog-security](catalog-security.md).

## 6. Public storefront M2c2

Chỉ GET `/api/v1/products` và GET `/api/v1/products/{productId}`, anonymous không cần cookie/CSRF và không gọi Identity. Cookie sai không đổi quyền read public; response no-store. Chỉ PUBLISHED+attached UPLOADED photo+shop hiện ACTIVE. Detail không khả dụng404; unknown/duplicate/malformed query, UUID hoặc cursor sai422; lỗi phụ thuộc DB/RPC hoặc RPC malformed503 an toàn, SQL lập trình vẫn500. Detail không nhận query parameter.

Browse nhận `q` tối đa100codepoints, `origin`120, `category`PRODUCE/SEAFOOD, `shopId`UUID, minPriceVnd/maxPriceVnd số nguyên1..1e9 với min<=max. q/origin strip rồi literal case-insensitive contains, escape wildcard SQL; giá lọc là min mọi SKU kể cả hết tồn. Sort duy nhất created_at DESC,id DESC. Cursor base64url tối đa256ký tự bind normalized filters; đổi filter phải bỏ cursor. Response `{items,nextCursor}` tối đa20items, không count/page/offset. Đọc21candidate, chỉ kiểm20với Merchant tối đa4batch×5ID; cursor từ candidate cuối đã xét, có thể0items mà vẫn còn nextCursor. Client tiếp tục **Xem tiếp** tới khi cursor null; không có snapshot xuyên request.

`FindPublicShops` nhận1–5UUID khác nhau, token/loopback/4KiB, deadline2giây/call, không retry/cache trạng thái. Response chỉ `{id,name}` ACTIVE hiện tại, name1..120codepoints không blank; Merchant omit legacy blank. Catalog kiểm subset/unique/shape/name, lỗi batch sau cũng fail503 toàn page. Public JSON dùng allowlist riêng, không serialize aggregate private; [trường public/private và stock boolean](catalog-security.md#storefront-m2c2--public-projection). Chưa có reservation, checkout hoặc review/Q&A. [Demo/proof backend](../demo-storefront.md); frontend/live/review đã đạt, xem bằng chứng cuối trong demo storefront.

## 7. Commerce M3a

HTTP 127.0.0.1:8084/api/v1, [OpenAPI](../../contracts/openapi/commerce-cart.v1.openapi.json). Cả ba operation kiểm Identity account/session hiện tại; mutation kiểm CSRF trước replay. Cookie AGORA_SESSION trùng bị từ chối. Response no-store và trace/security headers.

| Method/path dưới /api/v1 | Input | Response200 |
| --- | --- | --- |
| GET /me/cart | Không query | {cartId,version,items}, tối đa 20dòng, sort skuId ổn định |
| PUT /me/cart/items/{skuId} | {cartId,productId,quantity,expectedVersion}; CSRF + UUID Idempotency-Key | {version}; quantity tuyệt đối1..99 |
| DELETE /me/cart/items/{skuId} | {cartId,expectedVersion}; CSRF + UUID Idempotency-Key | {version}; không gọi Catalog |

Chưa có cart row thì GET trả version 0/items rỗng, không ghi DB. Item chỉ productId/skuId/quantity/offer; current offer nullable khi mất product/SKU/ảnh/shop public. Giỏ nhiều shop nhưng tối đa 20 SKU tổng cộng. PUT mới chỉ chấp nhận product/SKU public và inStock=true, không cam kết đủ exact quantity. PUT quantity không đổi hoặc DELETE dòng đã thiếu là no-op với version đúng. Giỏ không giữ hàng/checkout.

cartId UUID opaque ổn định theo account đã xác minh; cartId account cũ trả409 CART_CHANGED trước replay/write dù version trùng. Version0..9007199254740991. Body tối đa 4096 bytes; field/query/UUID/key/quantity/version sai 422; duplicate/trailing/malformed JSON 400. Mutation mới xung đột version/cap/availability 409, session401, CSRF 403, dependency503, SQL defect500 an toàn.

Key scope **actor+key**; operation/hash phải khớp. Hash bind operation/cartId/pathSKU/toàn body gồm product/quantity/expectedVersion khi có. Giữ đúng body/key khi retry intent chưa rõ kết quả; khác payload 422 IDEMPOTENCY_MISMATCH. Key giữ tối thiểu 7 ngày, cleanup hàng giờ. PUT preflight replay trước Catalog rồi recheck dưới cart row lock trước version/write; lookup lỗi/không khả dụng cũng tra lại replay. DELETE chỉ dùng Identity/Commerce store. Replay trả version gốc sau auth/CSRF/cartId checks; phải GET lại sau 200.

[ResolveOffers](../../contracts/proto/agora/catalog/v1/catalog_offers.proto) nhận1–20 distinct SKU UUID, omit missing/ineligible. Response subset/unique/product mapping phải đúng. Listener optional 9093 loopback, token32–256 URL-safe, inbound4 KiB / max 32 calls mỗi connection; Commerce inbound64 KiB/deadline 10 giây/không retry. Identity 2 giây/call, Merchant tối đa 4 batch × 5shop/2 giây mỗi call. [Offer allowlist/validation](catalog-security.md#m3a--catalogoffers-cho-giỏ-hàng).

Sau 200 phải GET mới trước PUT tiếp. Refresh503 chỉ giữ cartId/version cùng refs productId/skuId/quantity của chính giỏ để DELETE, bỏ offer/tên/giá/ảnh cũ; replay version cũ có thể 409 và cần refresh. Khi401/403 hoặc đổi cartId phải xóa dữ liệu/intent riêng và bỏ response account cũ. Đây là hành vi cần nghiệm thu frontend, chưa phải proof frontend/live PASS.

Nguồn: [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html).
