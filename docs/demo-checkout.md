# Demo checkout M3c — đã nghiệm thu local ngày27/10/2026

M3c LOCAL ACCEPTED ngày27/10/2026, Asia/Ho_Chi_Minh, theo bằng chứng main độc lập xác minh. Worker docs chỉ cập nhật Markdown/đọc source, không chạy lại Maven/npm/runtime. [ADR-0004](decisions/0004-order-and-payment-only.md), [plan M3c](../tasks/slice-3-checkout.md), [task index](../tasks/plan.md), [HTTP OpenAPI](../contracts/openapi/commerce-checkout.v1.openapi.json), [CatalogCheckout proto](../contracts/proto/agora/catalog/v1/catalog_checkout.proto).

## Final gates và reviews

| Phạm vi | Bằng chứng final |
| --- | --- |
| Backend root | 303 PASS =13Identity+59Merchant+191Catalog+40Commerce;39current classes,0failure/error/skip ngày27/10/2026 lúc17:12:38+07:00 |
| Root artifacts | [root-verify.log](../target/checkout-proof-20261027/root-verify.log), [source-manifest.json](../target/checkout-proof-20261027/source-manifest.json), [verified-jars.json](../target/checkout-proof-20261027/verified-jars.json);180backend sourcefiles không đổi sau gates |
| Catalog focused/review | 75 PASS lúc17:01:22+07:00, gồm13CheckoutStoreTests; actual Pauli review CLEAR |
| Commerce module/review | 40 PASS; actual Banach review CLEAR |
| Frontend static/build | lint/typecheck/production build PASS; final buildId VDJE0OiaLvYN78ua-ytLE |
| Frontend full gates | 143unit PASS,272fullproductionfixture PASS,audit0;119/120frontend manifest entries không đổi; contracts/README.md chỉ cập nhật trạng thái nghiệm thu, không ảnh hưởng build/test |
| Goodall review fixes | 2P2 fixed,3RED→5GREEN: late-preview503/malformed privacy; recoveryGET độc lập Cart/Catalog503. Test409 cũ cập nhật explicit reload |
| Provider live | Real4app+Cloudinary1/1PASS,10.046s; [checkout-provider-final-20261027.json](../../frontend/test-results/checkout-provider-final-20261027.json) |
| Baseline live | 5/5PASS,12.560s |
| Database/media cleanup | [live-database-and-cleanup.json](../target/checkout-proof-20261027/live-database-and-cleanup.json) đã được main xác minh |
| Runtime cleanup | [runtime-cleanup.json](../target/checkout-proof-20261027/runtime-cleanup.json) đã được main xác minh |

M3c đã nghiệm thu local; chưa commit/push/deploy hoặc xác minh GitHub CI run. Commerce29PASS preliminary, frontend268fixture/build Ag1QgyNGfVl_6CRARmPNb là kết quả trước final gates. M3b root274PASS lúc27/10 10:14:28+07:00 và M3a root226PASS ngày26/10 giữ riêng trong [demo reservation](demo-reservations.md)/[demo cart](demo-cart.md); không cộng focused/module runs vào root total.

## Source hiện có

Commerce HTTP8084 có năm operation dưới /api/v1, dùng Identity hiện tại cho mọi request; hai POST cần CSRF và UUID Idempotency-Key trước replay. Strict JSON/field/query/UUID, no-store và house error shape. Không nhận giá/tổng từ browser.

| Operation | Input và kết quả |
| --- | --- |
| POST /me/checkout-previews | cartId/cartVersion/shopId/skuIds; 200 preview server cho1–20distinct SKU, quantity1–99 lấy từ giỏ |
| POST /me/checkouts | previewId; 200 terminal hoặc202 PROCESSING, trả requestKey/checkoutId/state/orderId/failureCode |
| GET /me/checkouts/{requestKey} | Attempt của account hiện tại;404 chưa thấy không chứng minh POST concurrent thất bại |
| GET /me/orders | cursor tùy chọn actor-bound;20summary/trang, createdAt/id giảm dần, không total |
| GET /me/orders/{orderId} | Snapshot chi tiết riêng buyer; người khác404 |

Preview lưu giá/tên/quy cách/quantity/tổng VND do server tính; expiresAt giữ nguyên Catalog DB clock+5phút, không restart TTL ở Commerce. Product versions chỉ nội bộ; HTTP không lộ lotrefs. Quote kiểm public/ảnh/shop ACTIVE/inStock, chưa giữ tồn hoặc cam kết đủ exact quantity. Replay trả snapshot/expiry gốc kể cả đã hết hạn.

Confirm commit durable attempt trước network. Catalog HoldCheckout kiểm accepted price/product version cùng FEFO hold15phút, đủ tất cả hoặc không giữ. Typed result là reservation HELD/RELEASED hoặc closed OFFER_UNAVAILABLE/CHECKOUT_EXPIRED. V7 permanent decision cùng checkout advisory lock chặn raw/new hold hồi sinh closed identity. Business rejection rollback savepoint partial rows/counters/audit, giữ identity lock rồi ghi closed; SQL/timeout rollback không tạo closure giả. Accepted quote/admission/reservation dùng Catalog DB time, raw M3b hash/time semantics giữ nguyên.

Commerce lease30giây/revision fencing, worker10giây/batch20; RPC ngoài local transaction. Timeout/unknown giữ PROCESSING, recover bằng cùng key/preview/checkout và cùng HoldCheckout payload kể cả sau deadline. Không dùng Get NOT_FOUND + local clock để chốt FAILED. FAILED chỉ từ typed closed hoặc validated RELEASED; không suy terminal từ đồng hồ Commerce.

Validated HELD từ Catalog là authority tạo Order: complete Order+attempt+cart cùng transaction dưới lease/revision guard bất kể Commerce clock. Commerce không biến validated HELD thành FAILED vì expiresAt đã qua trên đồng hồ cục bộ. Chỉ dọn selected lines nếu cart version còn bằng preview; nếu đổi giữ giỏ, cartCleared=false. Replay không tạo đơn hoặc dọn giỏ lần hai. Snapshot đơn vẫn đọc khi product/shop/offer không còn public. AWAITING_PAYMENT/EXPIRED tính lúc đọc bằng expires_at và Commerce DB clock; worker hiện recover attempt, không cập nhật order state riêng.

[Catalog V7](../services/catalog-service/src/main/resources/db/migration/V7__checkout_decisions.sql) thêm checkout_decisions/accepted_not_after. [Commerce V2](../services/commerce-service/src/main/resources/db/migration/V2__commerce_checkout.sql) thêm checkout_previews/checkout_attempts/orders, snapshot/items JSONB; không order_lines hoặc cột order state riêng. Attempt identity không theo retention7ngày của cart. Final root/live đã kiểm schema trên fixture biệt lập; không áp migration vào native trong lượt này.

## Kịch bản provider thật đã nghiệm thu

Main kiểm flow qua Identity/Merchant/Catalog/Commerce thật và Cloudinary,1/1PASS10.046s. Preview giá35000/gói rồi đổi45000 trước confirm: checkout cũ bị đóng, không âm thầm chấp nhận giá mới. Preview mới xác nhận đúng một Order tổng90000 cho2gói×45000; main đã xem screenshot. Replay/cross-buyer/snapshot sau hide và cleanup có bằng chứng trong provider artifact và database proof, không coi fixture browser là provider thật.

Exact database proof trước khi gỡ fixture: **order1/closed1/HELD quantity2/on_hand12/allocated0/auditHOLD1/cartversion2/items rỗng**. Đây là2đơn vị HELD của hold thành công, không phải2đơn hoặc2hold. Product cuối HIDDEN; image DELETED,0relations và exact Cloudinary lookup404. Không tạo PAID/CONSUMED hoặc thanh toán giả.

## Runtime đã dọn và giới hạn

Tất cả8app ports3000/8081–8084/9091–9093 đã đóng; supervisor và Next đã dừng. Owned fixture fullID510eba9534aaabe1d92b5661b6026a80c4b1bfe72dfdb52d1472bed317273a95 đã removed, theo runtime-cleanup.json.180backend và119frontend execution inputs giữ nguyên sau gates; contracts/README.md chỉ đổi trạng thái nghiệm thu sau gate. Native PostgreSQL và backend.env không bị thay đổi; không commit/push/deploy.

frontend.env.live-test vẫn STALE,mode600; lần live sau cần fixture và ADMIN mới, không tái dùng credentials cũ. Local proof artifacts nằm trong target/test-results có thể bị dọn, không phải artifact deploy hoặc GitHub CI.

Chưa triển khai cleanup preview; hiện preview rows được giữ, không áp retention7ngày của cart vào attempt/checkout identity. Stock vẫn chịu giới hạn1000candidate lô chưa hết hạn/request của M3b; admission/replay typed và lease không là distributed transaction. Commerce DB tính Order EXPIRED khi đọc, validated HELD từ Catalog vẫn tạo Order dù Commerce clock lệch; FAILED chỉ typed closed/validated RELEASED.

Giữ nguyên Merchant contact/pickup_address, metadata SKU và allocated legacy. Checkout không thêm shipping/GHN/pickup/addresses/fees. **Dừng ở ranh giới M3c đã nghiệm thu local. M3d VNPAY là bước tiếp theo, chưa triển khai; PAID/CONSUMED/Payment không thuộc nghiệm thu này.**
