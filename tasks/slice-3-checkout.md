# M3c — Checkout và đơn một shop, không vận chuyển

Ngày27/10/2026. Backend base `c14b388bc4f6729cbaae43cc10e70d360534ab62`, frontend `d67ddbf0e55d7338159e528f5ed3f9d9a1979631`, hai repo sạch khi bắt đầu. Người dùng giao quyền tự quyết bước tiếp, sau đó bỏ giao hàng và chọn rõ chỉ đặt hàng/thanh toán. [ADR-0004](../docs/decisions/0004-order-and-payment-only.md) là yêu cầu hiện hành. Canonical tracker `slice-3-checkout`; Caveman/Ponytail full. Không tự commit/push/deploy hoặc sửa native PostgreSQL.

## Mục tiêu và lựa chọn

Từ giỏ, khách chọn một shop, xem proposal giá/tổng do server tính, xác nhận và nhận Order từ validated HELD của Catalog; response AWAITING_PAYMENT/EXPIRED tính bằng Commerce DB khi đọc. Xem danh sách/chi tiết đơn của mình. Tổng chỉ là tiền hàng; không địa chỉ/kiện/GHN/nhận tại shop. M3d mới có thanh toán VNPAY và PAID, không fake payment trong M3c. Không guest checkout, nhiều shop một đơn, promotion, quản trị giao nhận hoặc tranh chấp chất lượng.

Chọn preview lưu PostgreSQL thay vì giá do client gửi hoặc quote token ký. Cách này dùng JDBC/Flyway hiện có, snapshot dùng được cho recovery và không thêm secret/signature lifecycle. Order/attempt thuộc Commerce; giá/quy cách và giữ tồn thuộc Catalog. Không transaction/FK xuyên database.

## Catalog contract bổ sung

Proto mới `agora/catalog/v1/catalog_checkout.proto` cùng catalog-contract và listener9093, service `CatalogCheckout`:

- `QuoteCheckout`: shop_id và1–20distinct dòng product_id/sku_id/quantity1–99. Chỉ thành công khi tất cả đang public, có ảnh, shop ACTIVE và inStock; response shop_id/shop_name và mọi dòng gồm product/sku/quantity/product_name/sku_label/unit_price_vnd/net_weight_grams/product_version. Version chỉ nội bộ, không đưa vào HTTP preview. Tổng client không được tin.
- `HoldCheckout`: buyer_id/checkout_id/shop_id/not_after_epoch_micros và các dòng được chấp nhận {product_id,sku_id,quantity,unit_price_vnd,product_version}; response typed oneof ReservationResponse hoặc CheckoutClosed(reason). So version/giá trong cùng transaction và parent lock của giữ tồn; không hợp lệ thì typed closed reason, không giữ một phần; lỗi transport chưa rõ không là terminal decision.

Thêm metadata accepted-lines/notAfter vào Request nội bộ bằng overload để giữ nguyên raw Hold/Get/Release M3b và hash bytes của request cũ. Canonical hash checkout-aware phân biệt mode và bao gồm accepted prices/versions/deadline. Quote trả expires_at_epoch_micros từ Catalog DB clock_timestamp()+5phút. Admission mới cũng dùng cùng DB clock sau locks, không tin đồng hồ Commerce để kết luận kết quả.

V7 `catalog.checkout_decisions` lưu permanent checkout_id/buyer/shop/hash/closure_reason/closed_at. Mọi raw/new Hold lấy cùng advisory identity lock; trước tạo mới kiểm reservation và quyết định đã đóng. Existing reservation trả trạng thái hiện tại trước deadline/Merchant; existing closed trả cùng typed closed, owner/hash mismatch vẫn404/422. Không hồi sinh closed checkout dù stock/shop/giá đổi hoặc worker lease cũ gửi muộn.

Accepted create dùng savepoint sau khi đã có identity lock, tái sử dụng FEFO trong transaction hiện có. Business409 như giá/version/stock không hợp lệ: rollback savepoint mọi partialrows/counters/audit và giữ identity lock rồi ghi decision đóng, commit và trả typed closure. SQL/timeout/retryable fullrollback503, không ghi quyết định giả. Merchant inactive được chốt đóng trong shorttransaction cũng recheck reservation/decision trước; Merchant outage không phải terminal. RawHold gặp closedidentity không được tạo lại và phải áp owner/hash guard.

Luôn đối soát bằng **cùng HoldCheckout payload**, kể cả sau deadline; không dùng Get NOT_FOUND + đồng hồ Commerce làm bằng chứng. FAILED chỉ từ typed closure bền vững hoặc reservation RELEASED; gRPC mutable rejection/unknown không tự thành terminal. Decision vĩnh viễn đóng admission nên revision fencing cục bộ và closure Catalog cùng bảo vệ cả local/remote side effects.

Quote đọc snapshot SQL ngắn có product.version, rồi đóng transaction trước Merchant. Product version bảo vệ toàn bộ tên/nhãn/giá/quy cách đã chấp nhận, kể cả thay đổi rồi quay lại. Giữ stock cap1000candidates/TTL15phút/token/UUID/error sanitization của M3b. Không thay nghĩa CartOffer public hoặc nhận giá client. Request4KiB, responses bounded128KiB; không retry transport. Người dùng có thể cần preview lại khi sản phẩm đổi; không âm thầm đổi giá khi xác nhận.

## HTTP Commerce

Auth hiện tại qua Identity mọi request; mutation kiểm CSRF trước replay. Cookie ambiguous bị từ chối. Strict JSON/UUID/fields/query, Idempotency-Key UUID cho cả hai POST. UUID cartId phải thuộc account. Không nhận price/total/address từ client.

1. POST `/api/v1/me/checkout-previews` body {cartId,cartVersion,shopId,skuIds[1..20distinct]} →200 Preview. Selected refs/quantity lấy từ CartStore, mọi SKU phải thuộc cart/version và shop đã chọn. Preview tồn tại5phút theo expiresAt do Catalog DB cấp, chưa giữ tồn. Replay cùng actor/key/body trả snapshot gốc (có thể đã hết hạn); payload khác422. Không giữ DB transaction trong lúc gọi Catalog. Preflight replay trước dependency, recheck khi dependency lỗi như giỏ.
2. POST `/api/v1/me/checkouts` body {previewId}, key →200 nếu terminal hoặc202 nếu PROCESSING, cùng CheckoutAttempt shape. Trước RPC phải commit attempt và checkoutId bền vững. Preview chỉ dùng một lần, differentkey cùng preview409; replay cùng key/body không tạo thêm đơn, không kiểm cart version đã bị chính order dọn lại. Auth/CSRF luôn trước replay.
3. GET `/api/v1/me/checkouts/{requestKey}` →200 attempt riêng account,404 chưa thấy. Dùng key do client đã có để đối soát cả khi POST mất response;404 trước khi workflow bắt đầu không chứng minh POST concurrent thất bại. Có thể retry POST đúng key/preview cũ; không tạo key mới tự động.
4. GET `/api/v1/me/orders` chỉ query cursor (optional), page20, order createdAt/id desc, actor-bound cursor, no total →{items,nextCursor}. GET `/api/v1/me/orders/{orderId}` →Order riêng account;404 với người khác. Đọc snapshot không cần Catalog/Merchant còn public.

Preview HTTP: {id,cartId,cartVersion,shopId,shopName,currency:'VND',totalVnd,expiresAt,items}. Item {productId,skuId,productName,skuLabel,netWeightGrams,unitPriceVnd,quantity,lineTotalVnd}. LineTotal và total do server tính exact long; max20×99×1e9 vẫn trong JS safeinteger. Không thêm shippingFee=0 giả.

CheckoutAttempt: {requestKey,checkoutId,state:PROCESSING|ORDER_CREATED|FAILED,orderId:null|UUID,failureCode:null|OFFER_UNAVAILABLE|CHECKOUT_EXPIRED|RESERVATION_RELEASED}. Những lỗi dependency/timeout chưa biết kết quả giữ PROCESSING, không báo FAILED giả. Order: {id,state:AWAITING_PAYMENT|EXPIRED,shopId,shopName,currency,totalVnd,createdAt,expiresAt,cartCleared,items}. M3d mới mở PAID sau xử lý tiền/trừ tồn, không tạo dữ liệu paid giả.

HTTP errors house {code,message,traceId,details}: malformedJSON400/semantic422/session401/CSRF403/cartversion or previewused/expired409/dependency503; sau attempt đã claim thì business result được lưu và trả state FAILED, không ném mất workflow identity. Tất cả no-store/securityheaders.

## Workflow, khóa và recovery

Commerce V2 lưu checkout_previews(actor/key/hash,cart binding/snapshot/internalproductversions/expiry), checkout_attempts(actor/key,checkoutId,preview,PROCESSING|ORDER_CREATED|FAILED,lease revision/until), orders(immutable preview/items snapshotJSONB + reservation expiry/cart_cleared); không bảng order_lines hoặc cột order state riêng. Unique preview và checkoutId; permanent attempt identity/order, không dọn sau7ngày như cart key.

Claim mới kiểm preview owner/expiry và cart version dưới row locks, snapshot/buyer immutable, commit attempt trước network. Đừng tạo AWAITING_PAYMENT khi chưa biết đã HELD. Giao lease hữu hạn30giây để một worker xử lý; completion dùng revision fencing, worker cũ không ghi đè kết quả mới. Mọi network ngoài local transaction. Worker batch20 mỗi10giây và POST được phép thử xử lý ngay sau claim; các app instance có thể cạnh tranh an toàn.

Process luôn gọi HoldCheckout đúng snapshot/identity/deadline; Catalog replay reservation/closed trước, hoặc tự chốt deadline bằng DB time. Không dùng Get NOT_FOUND để chốt FAILED. RPC503/unknown giữ PROCESSING; typed closure chốt FAILED theo reason. Validated HELD từ Catalog là authority để commit Order+attempt ORDER_CREATED bất kể Commerce clock, không để commit nửa chừng. FAILED chỉ từ typed closed hoặc validated RELEASED; không suy RELEASED/hết hạn từ đồng hồ Commerce. Order khi đọc có thể EXPIRED theo Commerce DB, không đảo attempt ORDER_CREATED. Crash sau Hold trước local commit để attempt pending và worker hoàn tất bằng replay; không release mù khi response mất. Permanent closed quyết định dưới identity lock chặn worker lease cũ tạo Hold sau khi worker mới đã chốt FAILED.

Local completion không xóa những thay đổi giỏ từ tab khác: chỉ remove các dòng selected và tăng cartversion nếu cartversion còn bằng preview.cartVersion, trong cùng transaction với Order. Nếu cart đã đổi thì giữ nguyên giỏ, Order.cartCleared=false; UI giải thích giỏ vừa được cập nhật. Replay không dọn lại giỏ lần hai. Chốt thứ tự lock nhất quán, kiểm concurrent firstclaim/differentkeys/complete-vs-cartmutation; không giữ locallock khi RPC.

Đơn AWAITING_PAYMENT hết reservation.expiresAt thành EXPIRED khi đọc bằng Commerce DB; worker hiện recover attempt, không ghi order state riêng; snapshot giữ nguyên. Không thu tiền trong M3c. Khi M3d thêm Payment phải đổi expiry coordination để không hủy mù khoản tiền chưa rõ; PAID chỉ khi tiền hợp lệ và HELD→CONSUMED nguyên tử. Financial refund cho tiền trễ/trùng giữ riêng, không đưa logistics trở lại.

## UI và bằng chứng

Cart group thêm link/nút Đặt hàng của shop; `/checkout/[shopId]` hiện selected lines/tổng, Xem lại đơn và Xác nhận đặt hàng. Không có shipping form/fee. Phải preview lại khi hết hạn/offer thay đổi; không tự chấp nhận giá mới. Sau confirm chưa rõ kết quả giữ exact key/preview và có thể recover sau reload bằng refs trên URL hoặc cơ chế tương đương không chứa credentials/PII. Không tạo key mới tự động. `/orders` và `/orders/[orderId]` đọc snapshot/current state; không có payment button giả trước M3d.

Session401/403/đổi account xóa mọi private snapshot/intent, abort/fence response cũ; focus/pageshow revalidate. Không persist token/session trong Zustand/storage. GET503 không hiển thị proposal cũ như còn được mua. Browser keyboard/axe/320–1440px và các regressions unknownPOST/auth/account/cart races. Read installed Next docs trước frontend đổi.

O01 thiết kế/proto/OpenAPI và fresh buildercheck; O02 Catalog quote/acceptedHold TDD và backcompat M3b; O03 Commerce preview/workflow/lease/orders TDD PostgreSQL+RPC failures; O04 frontend/generatedcontract/fixture+staticgate; O05 rootverify/review/freshisolatedlive/docs/scans/HANDOFF. Maven tuần tự, Nextgate một owner, native/env không thay. Không cần GHN account nữa. Dừng ở M3c đã nghiệm thu, không tự triển khai VNPAY cùng acceptance.

## Builder review

Fresh reviewer chỉ ra hai blocker: Get NOT_FOUND theo deadline ở Commerce không chứng minh Catalog đã đóng khi clock lệch; local lease fencing không ngăn remote side effect của worker cũ sau business rejection. Đã thay bằng permanent Catalog admission decision và typed replay-or-closed, thời gian quote/admission lấy từ Catalog DB. Không trả FAILED từ mutable gRPC rejection chưa có quyết định bền vững. Cần test workerstale→stockreturns, clockskew, rollbackpartial/savepoint, closedreplay và backcompat rawhash.

## Nghiệm thu local M3c — 27/10/2026

Main độc lập xác minh **root303PASS =13Identity+59Merchant+191Catalog+40Commerce**,39current classes,0fail/error/skip lúc17:12:38+07:00; [log](../target/checkout-proof-20261027/root-verify.log), [manifest](../target/checkout-proof-20261027/source-manifest.json),180sourcefiles không đổi. Catalog75focused (13CheckoutStore), actual PauliCLEAR; Commerce40module, actual BanachCLEAR.

Frontend final lint/typecheck/build PASS, buildId VDJE0OiaLvYN78ua-ytLE,143unit/272fullproductionfixture/audit0. Goodall review2P2 đã sửa với3RED→5GREEN: late-preview503/malformed privacy và recoveryGET độc lập Cart/Catalog503; test409 cũ đổi thành explicit reload.119/120frontend manifest entries không đổi; contracts/README.md chỉ cập nhật trạng thái nghiệm thu, không ảnh hưởng build/test. Commerce29PASS, frontend268fixture/build Ag1QgyNGfVl_6CRARmPNb là proof preliminary trước final gates, không thay kết quả cuối.

Real4app+Cloudinary1/1PASS10.046s; baseline5/5PASS12.560s. Main xem screenshot tổng90000 (2×45000); [database/media cleanup](../target/checkout-proof-20261027/live-database-and-cleanup.json) và [runtime cleanup](../target/checkout-proof-20261027/runtime-cleanup.json) đã xác minh. [Demo/evidence](../docs/demo-checkout.md) ghi artifact frontend, exact stock/order/cart và cleanup fixture/ports.

M3c đã nghiệm thu local, dừng ở ranh giới này. M3d VNPAY/PAID/CONSUMED chưa triển khai; không fake payment hoặc logistics. Native/backend.env giữ nguyên, không commit/push/deploy. Preview/attempt/orders và V7/V2 đã được kiểm qua source cuối; Merchant/SKU legacy giữ nguyên.
