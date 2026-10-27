# Catalog — Ranh giới bảo mật và dữ liệu

## M3b — reservation nội bộ

CatalogReservations chia sẻ loopback9093/token constant-time với Offers. Token là service authentication; buyer_id không tự chứng minh người dùng, Commerce M3c hiện xác thực account/session/CSRF trước HTTP preview/confirm; [demo checkout](../demo-checkout.md) ghi implementation đã nghiệm thu local ngày27/10/2026. M3b chưa expose public HTTP. Mỗi Get/Release/replay kiểm buyer/checkout, mismatch NOT_FOUND; payload mismatch cùng owner ALREADY_EXISTS. Replay không bỏ qua token hoặc owner và không cần shop còn ACTIVE để trả tồn.

Lot references chỉ trả trên RPC nội bộ, không thêm vào PublicProduct/CartOffer. Không nhận counters/deadline/state do caller cấp. Hold local eligibility kiểm dưới parent lock; stock allocation và permanent identity commit cùng audit/counters. Error mapping không lộ SQL/host/token. Timeout/worker contention phải retry an toàn, không bỏ qua kiểm tồn. [Thiết kế](../../tasks/slice-3-reservations.md), [guide/proof](../demo-reservations.md).

M2a quản lý sản phẩm/SKU theo [kế hoạch](../../tasks/slice-2-catalog.md) và [OpenAPI](../../contracts/openapi/catalog.v1.openapi.json). M2b1 bổ sung ảnh qua [contract riêng](../../contracts/openapi/catalog-media.v1.openapi.json), M2b2 bổ sung công bố/ẩn qua visibility endpoint; không thêm URL/public ID vào Product input. M2c2 đã triển khai hai GET browse/detail public, đã nghiệm thu local ngày 25/10/2026; chưa có endpoint mua hàng. Kết quả backend và UI/live ghi riêng để phân biệt giới hạn bằng chứng.

## Danh tính và quyền shop

Trình duyệt gửi cookie AGORA_SESSION và masked CSRF qua Next rewrite cùng origin. Catalog gọi ResolveSession của Identity để xác minh phiên/account hiện tại; sau đó gọi ResolveActiveShop của Merchant với account ID từ Identity, không lấy shop ID/owner từ JSON hoặc X-User-Id.

Mỗi request seller, kể cả read và idempotency replay, phải đi qua hai kiểm tra này. Shop chưa duyệt/bị đình chỉ và account không còn hợp lệ phải bị từ chối. Không đọc session/merchant tables từ Catalog. Product của shop khác trả 404, không trả snapshot replay hoặc dữ liệu riêng.

Đây là hai lời gọi đồng bộ trước transaction Catalog, không phải transaction khóa xuyên service. Nếu shop đổi trạng thái sau lúc xác minh, thao tác đang chạy có thể hoàn tất; request tiếp theo phải đọc trạng thái mới. Visibility cũng xác minh quyền tại request, không cấp quyền checkout. Storefront kiểm shop hiện tại qua FindPublicShops; checkout M3c xác minh lại shop ACTIVE/giá/version và giữ đủ stock, không chỉ tin status hoặc boolean tồn đã đọc.

## Transport và lỗi

Local Identity gRPC bind 127.0.0.1:9091, Merchant gRPC bind 127.0.0.1:9092. Caller dùng shared INTERSERVICE_TOKEN URL-safe 32–256 ký tự từ env; không trả token/session/CSRF vào response hoặc log. Listener giới hạn request, client có deadline 2 giây mỗi call, không tự retry. Lỗi phụ thuộc phải fail closed, không principal/shop dự phòng.

Plaintext loopback chỉ phục vụ local. Triển khai nhiều container/máy cần TLS/mTLS, policy và quản lý secret; không mở rộng bind rồi coi token là đủ. HTTP private responses no-store; frontend origin config chỉ ở server, không dùng NEXT_PUBLIC_ cho secret.

## Dữ liệu và transaction

Catalog sở hữu database agora_catalog, schema catalog. Tiền VND số nguyên, khối lượng gram/kích thước cm có giới hạn rõ trong contract. Create/replace không nhận status, ảnh hoặc trường sở hữu do client khai báo. Visibility endpoint riêng chỉ nhận PUBLISHED/HIDDEN cùng expectedVersion. Chuỗi bắt buộc không chỉ khoảng trắng; response validation phải dùng độ dài raw theo contract.

Ràng buộc PostgreSQL bảo vệ code SKU duy nhất trong shop và điều kiện số học. Version guard bảo vệ hai ý định cạnh tranh. Product, thay đổi SKU, audit và idempotency result cùng transaction; lỗi một SKU không được để lại product/audit nửa chừng.

Idempotency key UUID thuộc actor/operation và payload/resource đã băm, giữ 7 ngày. Cùng key/payload replay snapshot gốc sau xác minh quyền hiện tại; đổi payload trả 422, in-flight trả 409. Client giữ key cho đến khi nhận response thành công có cấu trúc hợp lệ, không tự retry mutation.

PUT nội dung là thay toàn bộ thông tin DRAFT hoặc HIDDEN và danh sách SKU; sửa HIDDEN giữ HIDDEN. Code giữ nguyên thì giữ ID; V4 chặn xóa/đổi code hoặc quy cách vật lý của SKU từng có lô. Chỉ xóa SKU không được tham chiếu; label/price/reorder dùng upsert tại chỗ và giữ lịch sử. Cùng product row lock bảo vệ race nhập lô/sửa SKU.

## Kiểm chứng và credential

Unit/integration tests dùng PostgreSQL Docker riêng, không reset database native. Live phải gọi ba app thật, đăng ký owner, mở shop và được ADMIN duyệt trước khi tạo sản phẩm; fixture browser phải gắn nhãn rõ. Synthetic account/ADMIN chỉ nằm trong test database. Không lưu trace/video/ảnh form có credential hoặc dump cookie.

Cloudinary đã cấu hình trong ignored backend/.env mode600; không log/in giá trị. Test media mặc định tắt env import và dùng storage giả. Real-provider probe chỉ tạo/xóa ảnh tổng hợp mới có reserved namespace; không quét hoặc xóa tài nguyên cũ của tài khoản.

## Ảnh M2b1

Mọi thao tác ảnh/replay xác minh phiên/CSRF/shop ACTIVE và product DRAFT/HIDDEN thuộc shop hiện tại; PUBLISHED phải ẩn trước khi quản lý ảnh. Multipart chỉ một part file, JPEG/PNG có magic/header phù hợp, tối đa5MiB/20triệupixel. ImageIO bỏ qua metadata tùy chọn và chỉ đọc kích thước; không giải nén/giữ compressed-text chunks để xác thực. Provider phải xác minh ảnh cuối cùng. Server dành trước public ID `agora/products/{productUUID}/{uploadUUID}`, overwrite=false. Provider phải xác nhận đúng ID/type/format/bytes/dimensions; URL dựng từ cloud/metadata server, không tin secure_url từ response. BasicAuth chỉ gửi tới HTTPS api.cloudinary.com cố định, không redirect; body tối đa64KiB, deadline8giây gồm cả body trên JDK25. Lỗi trả503 chung, không gắn exception provider vào response/log.

Intent commit trước network. Retry PENDING chỉ đọc metadata cùng ID; chưa thấy resource thì503, không tạo duplicate upload. Quota20upload chưa gắn theo actor được khóa xuyên các product. Gắn/gỡ dùng row lock/version và key snapshot7ngày; audit/relation/version/key commit cùng transaction, không thay SKU. Tối đa5ảnh gắn. Cleanup claim không còn relation và hết hạn trước I/O, chỉ xóa reserved ID; DELETE_PENDING không được attach, lỗi provider giữ để retry. Không có HTTP endpoint gọi cleanup hoặc nhận ID bất kỳ để xóa.

Root verify22:57 ngày23/10 đạt114tests (13Identity44Merchant57Catalog), gồm19mediaAPI PostgreSQL,2cleanupunit,11provider. Probe Cloudinary thật upload/xác minh/xóa đúng ảnh PNG mới đã đạt. UI/live media có bằng chứng riêng sau nghiệm thu; không suy ra từ mock provider tests.

Root verify24/10 lúc10:05:50 đạt116tests sau fix metadata PNG, thêm hai regression validator. Tệp PNG nhỏ chứa32zTXt chunks đã gây cấp phát25,278,800bytes trước sửa; cùng tệp qua validator sau sửa nằm dưới2MiB. Giới hạn byte/pixel vẫn giữ; không dùng thời gian chạy làm assertion chống giải nén metadata.

## Visibility M2b2

Body visibility tối đa1024bytes, đọc sau SellerAccess; parser từ chối duplicate/trailing JSON. Key bind actor/operation/shop/product/status/version. Publish chỉ nhận DRAFT/HIDDEN có dữ liệu bắt buộc không trắng,1–10SKU và relation tới ít nhất1uploadUPLOADED đúng product/shop. Product row được khóa trước đọc ảnh; attach/detach cũng khóa product, nên công bố và gỡ ảnh cuối không đồng thời vượt điều kiện. PUBLISHED chỉ đọc; hide không sửa SKU/ảnh, chỉnh sửa HIDDEN không tự công bố lại.

Status/version/audit/key snapshot cùng transaction; lỗi rollback toàn bộ, không giữ key nửa chừng. Guard version tối đa9_007_199_254_740_991 tránh phản hồi số nguyên không an toàn trong JavaScript. Frontend cần lưu form trước khi Công bố để không công bố snapshot giá/nội dung cũ; bảo vệ này không thay validation/ownership/version của backend.

Root verify24/10 lúc10:57:21 đạt133tests (13Identity44Merchant76Catalog), gồm6domain/11PostgreSQL API mới. MigrationV2 có dữ liệu lênV3 giữSKU ID/image relation/completed DRAFT snapshot. Replay sau thu hồi account/shop bị401/403; key đổi payload/resource/version bị422; race publish/detach có một winner, audit rollback và incomplete saved data đã kiểm tra. Review backend độc lập không có blocker; UI/live còn bằng chứng riêng.

## Bằng chứng backend ngày 23/10/2026

Root Maven verify 22:16 đạt 82 tests (13 Identity, 44 Merchant, 25 Catalog), zero fail/error/skip. Catalog API integration dùng PostgreSQL thật với RPC fixture; client/provider RPC có test riêng. Vì vậy main chạy thêm probe qua ba app HTTP/gRPC thật trên ba database Docker biệt lập để đo ranh giới tích hợp.

Probe đã chứng minh: owner khác read/update trả 404; anonymous 401, shop chưa ACTIVE 403, CSRF sai 403; create/PUT replay đúng snapshot và không tăng số bản ghi; SKU id ổn định; stale version 409, payload đổi 422, code SKU trùng 409. Product version/SKU/audit/key counts giữ nguyên khi thao tác bị từ chối. Sau khi suspend đúng shop/account fixture, read và replay lần lượt bị chặn 403/401 ngay; khôi phục fixture và đọc lại thành công.

Đây là bằng chứng local, không dùng PostgreSQL native và không chứng minh TLS/ingress production, Cloudinary upload hoặc luồng mua hàng. Bộ browser fixture/live UI được ghi trong proof frontend, không gộp vào các test backend trên.

## Inventory M2c1

Ba operation lô riêng theo [contract](../../contracts/openapi/catalog-inventory.v1.openapi.json) giữ current Identity/Merchant/CSRF checks trước cả read/replay; không nhận shop/actor/counters từ client. JSON tối đa2048bytes, duplicate/trailing/empty/malformed400, UUID/input/date/payload422, cross-owner/parent/SKU404, version/state/inflight409 và dependency503; private/no-store, lỗi an toàn.

Hash bind productId+lotId+body. Claim/replay trước kiểm expiry/state/version động để retry qua nửa đêm trả snapshot gốc, không nhập/phân bổ hai lần. Snapshot không phải quyết định bán hàng; UI phải GET trạng thái mới. Khóa/mở không đổi bộ đếm; lô hết hạn không mở lại. Lot/audit/key commit cùng transaction, constraint không âm/không vượt tồn và FK triple không cascade. Root150tests16:03:17 gồm11API/6domain mới, kiểm concurrent key/race SKU, rollback audit, midnight replay/GET expiry, SQL bounds và migrationV3→V4.

Review frontend đã sửa việc lỗi GET đầu che denial của GET thứ hai và select tự đổi sang SKU khác sau refresh. Mỗi GET quan sát denial riêng; lựa chọn SKU mất phải chọn lại rõ ràng, giữ các field khác. Focus error server dùng effect sau commit, còn validation local focus field. Bằng chứng runtime177fixture/98unit/5live ở [proof](../../../frontend/docs/catalog-inventory-proof.md) là M2c1, không thay proof storefront. Tại nghiệm thu M2c1 ngày24/10 chưa có reservation/checkout hoặc proof production; không dùng proof đó cho M3c hiện tại.

## Storefront M2c2 — public projection

Theo [contract riêng](../../contracts/openapi/catalog-storefront.v1.openapi.json), chỉ GET `/api/v1/products` và GET `/api/v1/products/{productId}` là public. Không cần cookie/CSRF, không gọi Identity; cookie sai không làm read này thành seller authorization. Response `Cache-Control: no-store`. Chỉ lấy PUBLISHED có relation tới ảnh UPLOADED cùng product/shop và shop hiện ACTIVE. HIDDEN/DRAFT, thiếu ảnh hoặc shop không khả dụng không được trả public; detail trả404. Không lọc bỏ sản phẩm hết tồn.

Allowlist JSON thành công:

| Projection | Các trường duy nhất |
| --- | --- |
| Page | `items`, `nextCursor` |
| Card | `id`, `name`, `category`, `condition`, `origin`, `shop`, `inStock`, `minPriceVnd`, `thumbnail` |
| Detail | `id`, `name`, `category`, `condition`, `origin`, `shop`, `inStock`, `description`, `producerName`, `storageInstructions`, `skus`, `images` |
| Shop | `id`, `name` |
| SKU | `id`, `label`, `priceVnd`, `netWeightGrams`, `saleUnit`, `inStock` |
| Image/thumbnail | `url`, `width`, `height` |

Không serialize Product/Shop/lot/upload private vào public response. Không trả `ownerAccountId`, `actorAccountId`, `contactPhone`, `pickupAddress`, shop `description`/`status`/`lastReviewReason`; product/shop/lot `version`, `createdAt`, `updatedAt` hoặc audit/idempotency snapshots. SKU không trả `code`, `shippingWeightGrams`, `lengthCm`, `widthCm`, `heightCm`. Không trả lot ID/code, `producedOn`, `expiresOn`, `onHand`, `held`, `allocated`, `available`, trạng thái lô hoặc exact count. Không trả upload intent/ID, `publicId`, key/hash, trạng thái/expiry upload hoặc provider credential. URL ảnh public là URL đã xác minh; không thêm các trường metadata private vào JSON. Cursor opaque chứa mốc candidate, không phải quyền truy cập hay tổng số kết quả.

`inStock` trên product/card là tồn tại lô đủ điều kiện; trên SKU chỉ xét lô của SKU đó: stored state ACTIVE, expires_on>ngày hiện tại Asia/Ho_Chi_Minh, on_hand-held-allocated>0. Không giữ hàng, không khẳng định mua/giao được, đủ thời gian sử dụng hoặc an toàn thực phẩm. `minPriceVnd` là min mọi SKU, kể cả SKU hết tồn.

Browse sort created_at DESC,id DESC, SQL đọc tối đa21candidate và chỉ xét20đầu. Merchant lookup tối đa4batch×5ID khác nhau, không lookup candidate thứ21. `nextCursor` lấy candidate cuối đã xét khi còn lookahead; sau lọc shop có thể còn0items nhưng cursor vẫn khác null. Không total count/OFFSET hoặc quét bù toàn bộ shop. Cursor tối đa256ký tự base64url, bind normalized filters; không giữ snapshot xuyên request. Unknown/duplicate/malformed query, UUID hoặc cursor sai/cross-filter trả422; tên/xuất xứ tìm literal contains, escape SQL wildcard.

`MerchantAccess.FindPublicShops` chỉ nhận1–5UUID khác nhau, giữ token nội bộ/loopback/4KiB. Chỉ trả `{id,name}` cho shop ACTIVE hiện tại, không cache status. Tên tối đa120codepoints giữ nguyên; tên legacy chỉ khoảng trắng bị omit, tên null/quá giới hạn là lỗi. Catalog kiểm subset/unique/shape/name; RPC down/timeout/malformed hoặc một batch sau lỗi làm cả request503, không trả page một phần. Deadline2giây/call, không retry. Public không suy ra trạng thái account owner từ shop ACTIVE; account bị khóa không tự đổi status shop.

Detail đọc product/SKU/images/stock trong snapshot REPEATABLE_READ ngắn, đóng transaction trước gọi Merchant. Không có transaction xuyên service; lần GET sau phải kiểm lại trạng thái hiện tại. Public StorefrontService chỉ map lỗi kết nối/resource DB hoặc không mở được transaction thành503 an toàn; lỗi SQL lập trình vẫn500. [Demo và proof backend theo ngày](../demo-storefront.md); frontend/live/review đã hoàn tất, bằng chứng cuối ghi trong demo storefront. Tại nghiệm thu M2c2 ngày25/10 chưa có reservation/FEFO allocation hoặc checkout. M3b nay đã nghiệm thu, M3c đã nghiệm thu local ngày27/10/2026; VNPAY/review/Q&A/profile shop đầy đủ chưa triển khai, GHN đã bỏ theo ADR-0004.

## M3a — CatalogOffers cho giỏ hàng

[CatalogOffers.ResolveOffers](../../contracts/proto/agora/catalog/v1/catalog_offers.proto) là RPC nội bộ cho Commerce. CATALOG_GRPC_ENABLED=true bật 127.0.0.1:9093; token32–256 URL-safe so constant-time trước xử lý, inbound4 KiB / max 32 calls mỗi connection. Request1–20 SKU UUID khác nhau; Catalog RPC không gọi Identity hoặc đọc Commerce DB.

[SQL projection](../../services/catalog-service/src/main/java/com/agora/catalog/adapter/outbound/JdbcStorefrontStore.java) dùng một statement snapshot: SKU/product PUBLISHED, ảnh đầu tiên attached UPLOADED đúng product/shop và stock EXISTS lô ACTIVE, expires_on>ngày Việt Nam, available>0. Sau SQL gọi Merchant FindPublicShops tối đa 4 batch × 5IDs, chỉ trả shop hiện ACTIVE. Hết tồn vẫn có offer inStock=false; missing/ineligible bị omit. Không gọi public detail20lần hoặc có snapshot xuyên service.

Offer chỉ productId/skuId/shopId/shopName/productName/skuLabel/priceVnd/netWeightGrams/inStock/thumbnail; thumbnail chỉ url/width/height. Không owner/contact/pickup, lot/exact count/version/audit, SKU code/shipping fields hoặc upload intent. Commerce kiểm IDs subset/unique/product mapping; tên raw nonblank theo codepoints (shop 1..120, product 3..160, label 1..100), giá nguyên1..1e9 VND, net 1..50000 gram, URL Cloudinary namespace đúng product≤512 ký tự, kích thước dương/tích≤20 triệu pixel.

Commerce inbound64 KiB/deadline 10 giây, Merchant 2 giây/call, không retry. RPC/dependency/malformed response làm503 toàn cart, không partial success hoặc fallback giá/tên cũ. DELETE không gọi Catalog; khi refresh lỗi client chỉ giữ refs tối thiểu của chính giỏ để xóa. [HTTP contract](../../contracts/openapi/commerce-cart.v1.openapi.json), [demo/proof](../demo-cart.md). Giỏ không giữ hàng hoặc checkout theo inStock.

## M3c — accepted checkout đã nghiệm thu local ngày27/10/2026

[CatalogCheckout proto](../../contracts/proto/agora/catalog/v1/catalog_checkout.proto), [HTTP contract](../../contracts/openapi/commerce-checkout.v1.openapi.json), [plan](../../tasks/slice-3-checkout.md), [demo/evidence](../demo-checkout.md). Token kiểm trước input/persistence; Commerce xác thực buyer hiện tại/CSRF trước replay. HTTP không nhận giá/version/deadline hay lot do browser cấp; deadline accepted nội bộ lấy exact expiry do Catalog DB cấp, không đổi raw Hold contract. Product versions và lotrefs chỉ nội bộ.

Accepted hold kiểm giá/version cùng FEFO dưới parent locks. Permanent decision/identity lock bảo vệ stale worker và raw Hold; replay kiểm owner/hash trước trả reservation/closed. Không chốt FAILED bằng Get NOT_FOUND+local clock hoặc transport rejection chưa rõ. M3c đã nghiệm thu local ngày27/10/2026: root303PASS, Catalog PauliCLEAR/Commerce BanachCLEAR, real4app+Cloudinary và cleanup đã được main xác minh; đây là local evidence, chưa deploy. Validated HELD từ Catalog tạo Order bất kể Commerce clock; chỉ typed closed/validated RELEASED chốt FAILED, còn expiry khi đọc dùng Commerce DB.
