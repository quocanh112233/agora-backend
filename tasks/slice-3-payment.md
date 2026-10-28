# M3d — VNPAY sandbox, capture và tiêu thụ tồn

Ngày27/10/2026. Base sạch: backend `ff6857b4f75149cd8da1a4708f9bc818d2645b55`, frontend `35ae6acc43de3b2ba59262bef72558d4d40594d0`. M3c đã commit/nghiệm thu, không chạy lại chỉ vì phiên mới. Canonical `slice-3-payment`. **Thiết kế đã builder review CLEAR sau sửa3blocker; đang bắt đầu P-02TDD. Chưa nghiệm thu M3d.**

## Mục tiêu, quyền tự quyết và giới hạn

Người dùng đã giao quyền tự quyết các bước thường lệ, yêu cầu làm tiếp sau commit. Các lựa chọn kỹ thuật dưới là quyết định của agent theo ủy quyền, không phải lời phê duyệt riêng của người dùng. Giữ Java/Spring/JDBC/Flyway, Next/Zustand, PostgreSQL-only, gRPC, Docker/GitHub Actions và Cloudinary. Payment là service thứ năm trong sáu service đích; Feedback chưa làm. [ADR-0004](../docs/decisions/0004-order-and-payment-only.md) giữ chỉ đặt hàng/thanh toán, không GHN/giao nhận/pickup. Chỉ sandbox, không tiền thật, không autocommit/push/deploy/nativeDBchange.

Buyer mở đơn, yêu cầu phiên VNPAY, chuyển cổng, quay về xem kết quả; chỉ PAID khi tiền xác minh và Catalog CONSUMED được xác nhận. Callback lặp/đảo thứ tự, timeout/crash, tiền muộn/trùng không mất bằng chứng tiền, không nhân đôi trừ tồn hoặc hoàn tiền. Không COD, lưu thẻ, khuyến mãi, settlement ngân hàng thật, refund hàng hóa hoặc UI quản trị tài chính tổng quát. Chưa API buyer hủy đơn trong M3d; CANCELLED trong sơ đồ đích vẫn tương lai.

## Cách triển khai đã chọn

1. Cho Commerce gọi cổng trực tiếp: ít module hơn nhưng trộn trách nhiệm tiền vào đơn, trái ranh giới Payment đã chốt; bỏ.
2. Thêm RabbitMQ ngay: hữu ích khi có nhiều consumer nhưng tăng vận hành cho một luồng portfolio; chưa cần.
3. **Payment HTTP + database riêng, durable jobs và gRPC Payment→Commerce→Catalog**: chọn. Job rows là outbox cục bộ, retry hữu hạn mỗi lượt bằng lease/revision; không DB/FK/transaction xuyên service. Không thêm framework saga/common layer/dependency nếu thư viện hiện có hoặc JDK đáp ứng.

Payment HTTP8085/database test riêng `agora_payment_test`, đích local `agora_payment` nhưng không tự tạo/migrate native. Commerce thêm listener9094, contract module `commerce-contract`; Payment xác thực qua Identity9091 và gọi Commerce9094. Commerce gọi Catalog9093. Payment không gọi Catalog hoặc sửa Order DB trực tiếp. Network luôn ngoài transaction. Token/loopback/disable transport retry theo house patterns, production TLS/network chưa thuộc lượt này.

## Nguồn chính thức và cấu hình

Nguồn VNPAY: [PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html), [query/refund](https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html), [mô hình](https://sandbox.vnpayment.vn/apis/docs/mo-hinh-ket-noi/), [mã lỗi](https://sandbox.vnpayment.vn/apis/docs/bang-ma-loi/). PAY page timeout qua browser tool nhưng main đã tải HTML chính thức vào ignored target/payment-research-20261027/official-pay.html. Không dùng sample từ bên thứ ba làm chuẩn.

Dùng API2.1.0, HMAC-SHA512. Redirect/notification canonical khác query/refund pipe-delimited: không dùng chung quy tắc ký. Amount VND nguyên lưu long, nhân100 exact khi gửi cổng; timezone Asia/Ho_Chi_Minh. Return không là bằng chứng thu tiền. IPN chỉ ACK sau commit nhận tiền/outbox; remote Commerce lỗi không làm mất callback đã nhận. Query/refund phải kiểm chữ ký/binding và trạng thái giao dịch, không suy thành công từ HTTP200 hoặc API responsecode riêng.

Đã kiểm tên biến trong backend.env, lúc đầu chưa có VNPAY; user trả lời sẽ điền `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`. Main đã thấy hai biến nonempty lúc22:53; chưa providerverified. Không in secret hoặc yêu cầu gửi vào chat. Thêm example placeholders và config riêng cho sandbox gateway/API, fixed return origin, enabled flag; mặc định disabled/failclosed khi thiếu key. Key chỉ Payment, không NEXT_PUBLIC. Khi tới realIPN mới cần URLHTTPS công khai do người dùng cấu hình/cho phép; không tự mở tunnel hoặc publish cổng. Chưa kiểm được quyền query/refund của tài khoản. Provider sandbox fixture không là giao dịch thật.

PAY numeric12digits scaledamount đã xác minh nguồn chính thức; FAQ nêu tối thiểu5000VND. Chặn preview mới ngoài5000..9999999999VND và preview cũ chưa claim trước khi tạo checkout work/Hold; existingattempt replay/reconciliation luôn đi trước rangecheck để không strand hold đã commit. legacy order ngoài khoảng trả PAYMENT_AMOUNT_UNSUPPORTED tại initiation, không tách/giảm tổng. Order lịch sử vẫn đọc được, không rewrite snapshot.

## P-02 Catalog settlement — slice độc lập đầu tiên

V8 mở rộng reservations sang CONSUMED, `consumed_capture_id`, `consumed_at`; CHECK đảm bảo metadata chỉ ở CONSUMED, RELEASED/CONSUMED terminal. Audit thêm CONSUME. Không rewrite V1–V7. `ReservationState.CONSUMED` additive; raw Hold/Get/Release replay terminal không đổi hash/TTL cũ. Domain Reservation không bắt buộc expose capture trong HTTP hoặc đổi mọi fixture constructor.

Tái dùng CatalogReservations/ReservationService/ReservationStore: thêm RPC Consume(ConsumeReservationRequest buyerId/checkoutId/captureId), Get/replay/release vẫn trả ReservationResponse128KiB hiện có, thêm consumed_capture_id/consumed_at_epoch_micros. Không service/port settlement mới. Domain Reservation thêm metadata và constructor8args tương thích fixture. Chỉ CONSUMED có metadata. Token trước input/DB, strictUUID/4KiBrequest/safeerrors. Capture khác binding cũ trả ALREADY_EXISTS, không tự refund winner chưa rõ. CaptureID UNIQUE toàn reservations; Consume lấy advisory capture lock trước identity lock, rồi lotlocks; các đường expire/release không lấy capturelock nên không tạo vòng lock.

Consume lấy advisorycapturelock trước advisorycheckoutidentitylock hiện có, rồi lot UUID locks toàn cục. Consume chỉ accepted-checkout reservation, không raw Hold. Replay terminal trước eligibility; samecapture replay không trừ thêm. DB clock sau locks là authority; nếu deadline đã qua, release đúng một lần và trả RELEASED. Không Merchant/network trong transaction hoặc kiểm lại giá/tên/product visibility đã chốt.

Policy agent cho hàng có hạn: product HIDDEN không ngăn tiêu thụ hợp đồng đã đặt; nhưng allocation lot BLOCKED hoặc expires_on<=hôm nay VN sau khi khóa thì không bán. Release toàn hold thành RELEASED/UNAVAILABLE bền vững để hoàn tiền, không chờ hồi sinh khi lot mở lại. Thêm release reason UNAVAILABLE additive, update old RPC/client allowlists; không biến business refusal thành transport error không rõ. Validate state/date/counters sau lock; không dùng ảnh/public state để hủy đơn đã đặt.

Nếu hợp lệ, mỗi allocation giảm held và on_hand cùng quantity, tăng version/auditCONSUME; state/capture binding cùng transaction. Không đổi allocated, không restore on_hand khi refund. Bất kỳ lỗi/counterconflict rollback tất cả; SQL/timeout không tạo terminal giả. Expiry/release/consume cùng identity→lot lock order. Replay/Get/worker expiry hiểu CONSUMED và bỏ qua terminal, không expire consume đã commit.

## P-03 Commerce: tiền đã thu và quyền áp tiền

V3 bổ sung trạng thái order lưu thật AWAITING_PAYMENT/PAYMENT_PROCESSING/PAID/EXPIRED và capture_application receipts. Migration đặt các order cũ AWAITING_PAYMENT để worker đối soát Catalog, không đánh dấu EXPIRED từ Commerce clock. Không tạo PAID hoặc dữ liệu capture giả khi migrate.

Thay M3c expiry-on-read: đọc snapshot/state tại Commerce, không gọi Catalog/Payment để render. EXPIRED chỉ lưu sau Catalog RELEASED xác nhận; expiresAt là deadline hiển thị và hint schedule, không terminal fact. Worker quét chưa terminal có deadline đến hoặc đang settle, Catalog clock kiểm trạng thái. Có thể hiển thị chờ xử lý ngắn sau deadline nếu dependency lỗi; an toàn hơn báo hủy rồi phục hồi PAID. Giữ PAID terminal, không giả ALLOCATED/SHIPPED.

`GetPayableOrder(buyer,order)` nội bộ trả owned snapshot orderId/checkoutId/shopId/currency/amount/holdExpiry/state, không price client hoặc credential. Không cấp order của người khác; missing404. Payment initiation không dựa vào Return query hoặc số tiền client.

`ApplyCapture` nội bộ nhận captureId/attemptId/providerTxnRef/buyer/order/currency/amount; token và binding/hash strict. Chỉ verified Payment caller được gọi. Commerce ghi receipt trước RPC; unique captureId và immutable request hash. Dưới order row lock chọn **một capture winner vĩnh viễn**; không đổi winner sau lỗi/timeout. Loser verifiedcapture có REFUND_REQUIRED riêng; không sửa winner/order.

Dưới orderlock, authoritative EXPIRED nhận receipt REFUND_REQUIRED trực tiếp và giữ EXPIRED; PAID với capture mới cũng REFUND_REQUIRED, replay samecapture trảreceiptgốc. Không bao giờ chuyển terminal order về PAYMENT_PROCESSING. Chỉ nonterminal eligibleorder chọnwinner rồi commit receiptPROCESSING/OrderPAYMENT_PROCESSING trước Consume. Release local locks rồi gọi Consume đúng checkout/buyer/capture. CONSUMED cùng capture xác nhận → orderPAID/receiptAPPLIED cùngTX có revision fencing. RELEASED xác nhận → orderEXPIRED/receiptREFUND_REQUIRED. Unknown/timeout/NOT_FOUND/mismatch giữ PROCESSING + đối soát, không chốt hoàn tiền khi chưa biết đã trừ tồn. Retry cùng identity, không gọi Release để bù khi response Consume mất.

Expiry worker thấy CONSUMED chưa localPAID phải để settlement replay hoàn tất; không EXPIRED. Thấy RELEASED có thể lưu EXPIRED, receipt winner sau đó REFUND_REQUIRED; terminal Catalog bảo đảm không còn Consume muộn. Worker stale không ghi đè PAID hoặc terminalreceipt. Nếu releaseUNKNOWN thì giữ state, không suy kết quả từ đồng hồ local.

RPC `GetCaptureApplication` giúp Payment đối soát sau lostresponse; NOT_FOUND không chứng minh ApplyCapture concurrent chưa commit. Apply lại cùng payload/key; không refund dựa NOT_FOUND. Read/apply response không trả lot refs hoặc secrets.

## P-04/P-05 Payment: identity, provider inbox/outbox và hoàn tiền

V1 schema payment sở hữu:

- payment_attempts: actor/order/key/bodyhash, immutable expected amount/currency/expiry, unique txnRef32hex, signed redirect ingredients; CREATED/PENDING/FAILED/EXPIRED/SUCCEEDED và needs_reconciliation. Key bền vững, không TTL7ngày.
- provider_transactions/captures: unique merchant+providerTxnNo, immutable boundattempt/amount/currency and capture ID; verified provider evidence append-only có giới hạn, không lưu secret/PAN/OTP/rawcookies.
- capture_jobs: capture→Commerce application, lease30s/revision/batch20/10s; statuses PROCESSING/APPLIED/REFUND_REQUIRED. Capture receipt và job cùngTX trướcIPN ACK.
- refunds/refund_requests: nghĩa vụ fullrefund cho capturedmoney không áp được, request identity bền vững; pending reserved amount + succeeded <= capturedamount. Một refund liability/một capture, không tạo lại khi unknown. Chỉ full financialrefundMVP, không partial hoặc buyer-return UI.
- ledger_entries/audit: append-only CAPTURE/REFUND_RESERVE/REFUND_SUCCESS/RELEASE_RESERVE cùng financialstate transaction, unique eventidentity. Không sửa/xóa khoản thu khi orderEXPIRED hoặc callback cũ.

POST tạoattempt lấy ownedorder từ Commerce ngoàiTX, rồi recheck replay/activeattempt dưới localorder advisory lock và lưu durableattempt trước trảredirect. Cùng actor/key/body trảattempt/link gốc; mismatch422. Một order chỉ một attempt đang chưa rõ. Attempt thất bại được provider xác nhận mới cho người dùng tạo mới; localEXPIRED/timeout/query91 không tự chứng minh chưa thu tiền. Callback muộn của attempt cũ vẫn ghi CAPTURE và quy tắc winner/refund xử lý, không làm mất tiền.

IPN GET public không CSRF/session nhưng strictduplicateparams/size/bounds, verify HMAC constant-time trước lookup/financialwrites. Check TmnCode/txnref/scaled amount/provider status/transactionNo/bindings. PAYcallback thường không có CurrCode: dùng originalattemptVND; nếu fieldcurrency có thì phải signedVND. Chỉ00/00 là finalsuccess;07 hoặc trạng thái01/04/05/07 cần đối soát, không coi declined và cấp attempt mới mù. Ký tất cả field theo spec, không tự bỏ field không hiểu hoặc doubledecode. Mismatch không credit; signed contradictory evidence giữ needs_reconciliation, không đổi SUCCEEDED thành FAILED. Response JSON RspCode/Message theo spec; dependencyDBfailure khôngACKsuccess. Duplicate đúng đãcommit ACK02, mớicommit ACK00; invalidsignature97/missing01/amount04/error99 sau xác minh chính thức.

Return handler verify/correlate rồi redirect tới fixed frontend order/status route, không ghi capture hoặc PAID. Queryparameters provider không được coi là auth/orderowner; frontend GET currentauth+ownedPayment/Order. Không render rawquery messages hoặc redirectURL client tự chọn.

Querydr worker cho pending/expired/contradictory được ratebound, requestIDs/JSON/HMAC theo spec và whole-body timeout8s, allowlisted sandbox origin, sanitized logs. Verifiedsuccess ghi capture/job cùng nhưIPN; duplicate financialtransaction không tạo mới. 91/HTTP200/malformed/timeout giữ unknown. Queryfailure không cho phép bắt đầu thu lần nữa mù.

Refund worker chỉ chạy từ typed durable REFUND_REQUIRED, không từ localdeadline hoặc transporterror. Reserve liability trước network; persist exact requestId/fields trước call, không tự generate requestId mới sau timeout. APIaccepted≠refundsucceeded; chỉ signed/bound terminal transaction status xác nhận mới chuyển ledgerREFUND_SUCCESS. Unknown/permissionmissing/unsupportedquery giữ NEEDS_RECONCILIATION/RESERVED và admin đọc được, không báo hoàn thành giả hoặc tự retry refund với identity mới. Cần chứng minh cách đối soát refund thực của tài khoản; nếu API không đủ bằng chứng thì dừng ở nghĩa vụ chờ xử lý rõ ràng.

## P-06 HTTP và UI

Payment REST qua Next rewrite8085:

- POST `/api/v1/me/orders/{orderId}/payment-attempts`, body `{}`, UUID Idempotency-Key + Identity currentCSRF;200replay/201new, owned404, conflict409, validation422, dependency503. Chỉ trả opaqueattemptId/order/state/redirectUrl/deadline, khôngsecret/providerrawdata. Backend allowlist HTTPS gateway sandbox, URL do server ký, không openredirect.
- GET `/api/v1/me/payment-attempts/{attemptId}` owned status và application/refund state; no-store. Frontend không tự thaykey nếu POSTunknown; GET `/api/v1/me/payment-attempts/by-request-key/{requestKey}` owner-bound để tìm attempt khi POSTmấtresponse; route cụ thể trước UUIDcatchall. GET404 giữunknown và retryPOSTexactkey/body, không tự thaykey. Persist opaque requestKey+orderId vào URL trước POST; account được fence bằng currentIdentity trước/sau response và backendactorbinding. RecoveryGET không cần Commerce. Unique(actor,key) hash bind operation/orderId/body, không chỉ body{}; crossorderreuse422.
- GET `/api/v1/payments/vnpay/ipn` và `/return` public providerhandlers, auth exception chỉ đúnghaiendpoint; fixed query/bodylimits/logsanitization. Return→sameorigin frontend, IPN không đi qua page renderer.
- Admin read-only refund/reconciliation queue nếu cần chứng minh pendingliabilities; không nút xác nhận tiền/hoàn tiền bằng tay không bằng chứng provider.

Order UI thêm state PAYMENT_PROCESSING/PAID, nút Thanh toán VNPAY chỉ khi đượcservercho; explicit attempt/link, reload recovery theo opaqueUUIDrefs, polling hữu hạn/manualrefresh. Return không hiện thành công dựaquery. FAILURE báo rõ và cần thao tác mới, không autoPOST/auto charge. Currentaccount/CSRF/abort/revision/cross-tabfences giữ nhưcheckout. Không persist secrets/tokens vàoZustand/storage. No stale private snapshot khi API503/401/403/accountswitch. Read installedNextdocs trước sửa UI.

## Thứ tự thực hiện và gate

P-01 chốt thiết kế, exact provider rules và freshbuilderreview, rồi proto/OpenAPI. P-02 CatalogsettlementTDD/schema/races là lát đầu không phụ thuộc VNPAYkeys. P-03 Commerceauthority/state/receipts/gRPC/recoveryTDD. P-04 Paymentbootstrap/auth/attemptcodec/inbox/capturejobsTDD. P-05 Query/refund/ledger+financialracesTDD. P-06 Frontend/generatedcontract/static/fixture. P-07 Mainroot/reviews/fresh5DBlive/provider/docs/scans/HANDOFF. Maven1owner; only disjoint workers; no sharedcontracttarget races. Không đóng nguyênM3d khi chỉ có một phần foundation.

Tests bắt buộc: sameconsume replay/othercapture/expire-vsconsume/lotlockdeadline/block/date/partialcounterrollback/consumedGetReleaseexpiry; IPNsignature/encoding/scaledamount/duplicateparams/statuscontradiction/crossowner/replay/dedup; persistedclaim before network, staleleases/localrollback, lostconsume response eventuallyPAID notrefund; duplicatecapturessameorder winner+fullrefundliability; capturedlate afterrelease cannotrevive; query91unknown; refundtimeout retainsidentity/reserve; concurrentrefundbudget; auth403late responses; unknowninitiation reload and return spoof; no frontendcallback-inferred success.

Real provider gates riêng: credentialsconfigured, synthetic golden signing vector dựa canonical chính thức, kiểm độc lập rồi provider thật chấp nhận checksum, signed sandboxredirect, actual sandbox success/query/IPN when publiccallbackconfigured, capture→CONSUMED→PAID, exact replay; refundproviderpermissions/reconciliation proven hoặc clearlypendingnotacceptedasrefunded. Không dùng fixture/IPN tự ký để tuyên bố realVNPAYgate. Không nhập thẻ thật. Nếu cần userOTP/tunnel/providerpermission thì báo đúngbước cụ thể, tiếp tục việc không phụ thuộc được.

## Builder cần chốt trước code

Fresh builder Gauss tìm3blocker: terminalEXPIRED bịlatecapturemởlại; thiếuURLrefs/actor+orderbinding cho lostPOST; amountcapbỏsótpreviewcũ hoặcstrandexistingwork. Đã bổ sung terminalguard, recoveryGETbykey+opaqueURLrefs+immutableorderhash, và rangeguard chỉnewwork saureplay. Gauss đã xác nhận corrected design CLEAR. Provider quyềnrefund/callback vẫn là gate thật riêng; không giữ DBTX qua RPC.
