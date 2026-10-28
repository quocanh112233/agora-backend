# Agora — Payment M3d

## Trạng thái ngày28/10/2026

Payment đã có implementation và tests local; **M3d còn partial, chưa nghiệm thu toàn slice**. M3c đã nghiệm thu local ngày27/10 với proof riêng ở [demo checkout](demo-checkout.md); proof đó không chứng minh Payment. [Plan M3d](../tasks/slice-3-payment.md), [Payment](../tasks/slice-3-payment-payment.md), [Commerce](../tasks/slice-3-payment-commerce.md) và [Catalog](../tasks/slice-3-payment-catalog.md) ghi thiết kế/lát triển khai; các trạng thái khởi đầu trong plan là lịch sử.

Theo cập nhật của main tối28/10: lượt Payment projection **83 PASS**, sau durable wakeup **86 PASS**, read-only review **CLEAR**. Đây là hai lượt local trước root, không cộng dồn. Root verify local do main xác minh ngày **28/10/2026 lúc19:53:44+07:00: BUILD SUCCESS, 443 PASS =13 Identity+59 Merchant+206 Catalog+77 Commerce+88 Payment, 0failure/error/skip**. Artifacts: [root log](../target/payment-proof-20261028-evening/root-verify.log), [manifest238sourcefiles](../target/payment-proof-20261028-evening/source-manifest.json), [năm verified JAR](../target/payment-proof-20261028-evening/verified-jars.json). Manifest có238file, digest nguồn khớp với verified-jars có năm JAR. Main đang boot năm JAR trên fixture mới; proof nghiệm thu toàn M3d và actual provider/live vẫn **pending**. Root local proof không tự chứng minh live provider success. Tài liệu này không báo provider thành công từ fixture hoặc callback tự ký.

## Ranh giới và kết nối

[ADR-0004](decisions/0004-order-and-payment-only.md) giữ sáu service Identity/Merchant/Catalog/Commerce/Payment/Feedback; tất cả dữ liệu ứng dụng dùng PostgreSQL theo [ADR-0003](decisions/0003-postgresql-only.md). Reactor hiện có năm service, Feedback còn dự kiến. Một đơn/một shop, tổng đơn chỉ tiền hàng; không GHN, vận chuyển, pickup, phí giao nhận hoặc inventory refund.

| Thành phần | Kết nối local | Trách nhiệm |
| --- | --- | --- |
| Identity | HTTP8081 / gRPC9091 | Current session, actor và CSRF |
| Merchant | HTTP8082 / gRPC9092 | Shop authority |
| Catalog | HTTP8083 / gRPC9093 | Giá/hold gốc, Consume và tồn kho |
| Commerce | HTTP8084 / gRPC9094 | Đơn, capture receipt, winner và settlement |
| Payment | HTTP8085 | Attempt, VNPAY, capture ledger, query/refund jobs |

Payment gọi Identity và Commerce qua gRPC; Commerce gọi Catalog, Payment không truy cập database của các service khác. [CommercePayments](../contracts/proto/agora/commerce/v1/commerce_payments.proto) gồm GetPayableOrder/ApplyCapture/GetCaptureApplication, generated qua commerce-contract. `COMMERCE_GRPC_ENABLED=true` bật listener loopback9094, mặc định false; các RPC dùng INTERSERVICE_TOKEN. Payment HTTP mặc định127.0.0.1:8085, cấu hình qua PAYMENT_SERVER_PORT/PAYMENT_SERVER_ADDRESS, database PAYMENT_DB_NAME (mặc định agora_payment), schema payment. gRPC plaintext ở đây chỉ cho loopback local.

## Cấu hình VNPAY

`VNPAY_ENABLED=false` là mặc định và chỉ chặn **tạo attempt mới**. Replay và đọc attempt không cần bật lại initiation; callback/IPN/Return và reconciliation của attempt đã có vẫn hoạt động nếu cấu hình provider hợp lệ. Thiếu cấu hình hợp lệ thì fail closed: không tạo URL thanh toán giả, callback không xác nhận tiền thành công; IPN lỗi cấu hình/storage trả mã99 để retry.

Chỉ Payment đọc các tên biến server-only: `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`. Không ghi giá trị, raw callback hoặc secret vào tài liệu/log/frontend. Return URL cố định, không nhận redirect đích từ client. Codec hiện cho phép HTTP với host chính xác127.0.0.1 cho sandbox local; public return cần HTTPS, không có userinfo/query/fragment. Public IPN cần callback HTTPS và cấu hình provider thực riêng; không tự mở tunnel hay publish cổng. Việc có biến cấu hình không chứng minh provider đã chấp nhận checksum hoặc cấp quyền query/refund.

## HTTP, ownership và replay

[OpenAPI Payment](../contracts/openapi/payment.v1.openapi.json) là contract canonical cho năm operation:

| Operation | Ngữ nghĩa |
| --- | --- |
| POST /api/v1/me/orders/{orderId}/payment-attempts | JSON đúng `{}`, UUID Idempotency-Key; tạo201 hoặc replay200 |
| GET /api/v1/me/payment-attempts/{attemptId} | Current actor đọc attempt sở hữu |
| GET /api/v1/me/payment-attempts/by-request-key/{requestKey} | Recovery khi mất phản hồi POST |
| GET /api/v1/payments/vnpay/ipn | Xác minh thông báo provider trước lookup/ghi tiền |
| GET /api/v1/payments/vnpay/return | Xác minh/correlate, enqueue query rồi303 về route đơn cố định |

Ba route `/me` kiểm current session/ownership; POST kiểm CSRF **trước replay**. Key bền vững theo actor, gắn đúng order và body; đổi order với cùng key trả422. Replay lookup trước Commerce/provider initiation config, giữ attempt/key/txnRef/deadline gốc. Status recovery đọc Payment local, không cần Commerce khả dụng. GET theo key trả404 là chưa biết kết quả, không phải bằng chứng an toàn để đổi key; retry đúng POST/key/body. Không tự tạo charge mới sau timeout.

Tiền lấy từ owned order server qua Commerce, không nhận total từ client; amount nguyên VND **5000..9999999999**, scale100 khi gửi VNPAY. New preview/unclaimed confirm có range guard; existing work/replay vẫn được recovery, legacy order ngoài range không được tạo thanh toán mới. Payment dùng **expiry gốc của reservation15phút**, không cấp thêm15phút khi tạo link/replay. Chỉ trả redirect khi attempt còn PENDING/live và chưa có captured money cho order. Local EXPIRED chỉ hết thời gian chờ, không chứng minh chưa thu tiền; attempt FAILED được xác minh mới cho phép yêu cầu mới rõ ràng.

Attempt `SUCCEEDED` là đã có capture xác minh; order `PAID` còn cần settlement Catalog. Projection trả `applicationState`, `refundState`, `needsReconciliation` từ toàn bộ captures/refunds của attempt; trạng thái PROCESSING/REFUND_REQUIRED hoặc nghĩa vụ refund chưa rõ vẫn hiển thị, không che bởi một capture đã áp thành công. Return không ghi capture hoặc PAID; người dùng đọc lại owned Payment/Order bằng session hiện hành.

## IPN, ledger và áp tiền vào đơn

Callback kiểm merchant/HMAC và bounded fields trước lookup attempt, rồi bind txnRef/amount. Mỗi merchant + provider transaction number chuẩn hóa có một capture; provider transaction mới trên attempt đã SUCCEEDED vẫn phải ghi nhận. Duplicate đúng capture ACK02; không ghi thêm ledger/job. Mismatch/contradiction giữ bằng chứng tiền và reconciliation, không ghi đè capture cũ hoặc suy hoàn tiền từ timeout.

IPN chỉ ACK00 sau transaction Payment commit **capture + CAPTURE ledger + capture job + attempt state** nguyên tử. Commerce outage không làm mất callback đã nhận. Worker claim job với lease30giây/revision, commit trước gọi ApplyCapture ngoài transaction; complete chỉ ghi khi lease/revision còn hợp lệ. Worker chạy mỗi10giây, mỗi lượt tối đa20 job. Mất reply thì retry cùng capture/payload, không tạo capture hoặc refund mới; NOT_FOUND không chứng minh remote chưa commit.

Commerce lưu receipt/winner và PAYMENT_PROCESSING trước Consume; chỉ xác nhận APPLIED/PAID khi Catalog trả CONSUMED đúng capture. Catalog giảm held/on_hand cùng transaction với state/capture/audit, giữ TTL gốc và terminal replay; không đổi allocated. Lot BLOCKED/hết hạn hoặc hold đã RELEASED không được revive. Duplicate/late capture hoặc amount mismatch nhận typed REFUND_REQUIRED khi authority xác nhận; RPC unknown giữ PROCESSING/reconciliation. Đơn EXPIRED không mở lại, PAID terminal. Order reads dùng stored state thay vì tự EXPIRED chỉ theo đồng hồ local.

## Durable query wakeup và recovery

[Payment V1](../services/payment-service/src/main/resources/db/migration/V1__payments.sql) lưu attempts, captures, ledger_entries, capture_jobs, observations, refunds và provider_queries. Attempt/txnRef/amount/expiry giữ nguyên; các FK chỉ trong database Payment. [V2](../services/payment-service/src/main/resources/db/migration/V2__provider_query_wakeups.sql) thêm query_wakeups, seed các attempt/capture đã có reconciliation flag; không rewrite migration V1.

Return hợp lệ hoặc callback cần đối soát ghi wakeup bền vững. Wakeup không phải cờ chỉ trong RAM: query đang leased vẫn giữ được yêu cầu mới; claim tiêu thụ wakeup nguyên tử khi lấy lease/revision mới, completion cũ không xóa wakeup đến sau claim. Wakeup có thể mở lại query đã done, nhưng vẫn tôn trọng lease đang chạy. Query fallback bắt đầu sau expiry gốc +5giây; pending/unknown retry có lịch, không tự cho phép attempt mới. Provider query/refund và Commerce RPC đều ở **ngoài DB transaction**; claim/complete là transaction local có fence. Signed/bound query success đi qua cùng capture/ledger dedup như IPN.

## Refund tài chính

Chỉ typed REFUND_REQUIRED tạo một full-refund liability cho capture. Payment reserve đúng số tiền đã capture và ghi REFUND_RESERVED ledger nguyên tử, unique theo capture; một request identity bền vững. `RESERVED`/`PENDING` là nghĩa vụ đang giữ ngân sách, **khác `SUCCEEDED` đã có bằng chứng**. Không có partial refund/buyer return flow trong M3d.

HTTP200 hoặc provider API accepted không đủ chứng minh đã hoàn tiền. Chỉ signed/bound refund terminal success mới ghi REFUND_SUCCEEDED. Query transaction type01/status00 là original payment success, không phải refund success; query refund cần type02 và đúng refund transaction number đã biết. Nếu provider trả pending và có refund transaction number thì tiếp tục query giao dịch đó.

Lost reply/crash sau gửi refund mà chưa có refund transaction number giữ reservation/request identity và chuyển NEEDS_RECONCILIATION; **không gửi lại refund chưa rõ, không tự tạo request mới**. Chưa đủ bằng chứng/quyền provider thì nghĩa vụ vẫn pending cho đối soát, không báo đã hoàn thành. Refund không tăng lại on_hand/held, không đảo CONSUMED hoặc PAID; không có inventory refund.

## Demo và provider gate

Demo M3d do main quản lý dùng fixture Docker **năm database riêng** cho Identity/Merchant/Catalog/Commerce/Payment, không phải database native và không thêm native migration. Không tái dùng fixture bốn database M3c như proof Payment. Runtime/assets/DB cleanup và proof chính thức do main xác minh riêng; tài liệu này không tuyên bố đã hoàn tất cleanup hoặc live payment.

Local API/codec/PostgreSQL tests và synthetic provider fixtures chứng minh hành vi trong phạm vi test. Actual sandbox gates vẫn cần bằng chứng riêng: provider chấp nhận checksum/redirect, giao dịch sandbox thực, signed query/IPN khi callback công khai đã được cấu hình, capture→CONSUMED→PAID/replay và refund permission/reconciliation nếu được hỗ trợ. Không dùng fake provider success để đóng gate. Root local443PASS ngày28/10 lúc19:53:44+07:00 đã có artifacts ở trên; actual provider/live, cleanup runtime mới và proof nghiệm thu toàn M3d vẫn **pending**, không lấy root local hoặc các count M3c/83/86 thay thế.

## Việc hoãn đến giai đoạn deploy

Ngày 28/10/2026, người dùng quyết định hoãn cấu hình và kiểm thử IPN public đến khi deploy có HTTPS public. Không mở tunnel để giải quyết IPN trong giai đoạn local. Giữ nguyên endpoint, kiểm chữ ký, capture và cơ chế đối soát; Return không đủ để ghi nhận thanh toán thành công.

Khi deploy cần xác minh domain/TLS và routing `/api/v1/payments/vnpay/ipn`, cấu hình callback trong SIT/theo VNPAY, điều tra lỗi sandbox querydr `99 — Unexpected error`, rồi kiểm thử IPN thật, capture → CONSUMED → PAID, callback trùng, thanh toán muộn và nghĩa vụ refund. Chưa có bằng chứng thì giữ trạng thái chờ xác nhận/đối soát, không đánh dấu PAID hoặc coi nghiệm thu provider đã hoàn tất. Việc hoãn IPN không chặn các công việc local độc lập và không tự cấp quyền deploy/public dịch vụ.
