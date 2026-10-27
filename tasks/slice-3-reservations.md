# M3b — Giữ tồn FEFO trong Catalog

Ngày 26/10/2026. Base backend `760fc70`, frontend `d67ddbf`, cả hai sạch lúc tiếp tục. Người dùng giao quyền tự quyết các lựa chọn thông thường. Tài liệu này là quyết định kỹ thuật của agent dựa trên UC-06 và state machines; chưa phải bằng chứng implementation hoặc nghiệm thu.

## Phạm vi

Catalog cung cấp RPC nội bộ để giữ đủ hàng của một shop, đọc trạng thái và giải phóng. HELD có thời hạn cố định 15 phút, chuyển RELEASED khi hủy hoặc hết hạn. Không triển khai ALLOCATED/SHIPPED, checkout/order, GHN, VNPAY hoặc giao diện trong lát cắt này. Giỏ hiện tại vẫn không giữ tồn. Commerce sẽ là caller khi M3c có checkout; M3b kiểm bằng RPC và PostgreSQL biệt lập.

## Lựa chọn

1. PostgreSQL row locks và transaction cục bộ: chọn vì Catalog đã sở hữu lô, bộ đếm và các khóa chỉnh sửa sản phẩm. Một transaction giữ toàn bộ SKU, rollback khi thiếu hàng.
2. Optimistic CAS trên từng lô: khả thi nhưng cần retry cả tập nhiều SKU và kiểm tra lại FEFO. Không chọn vì phức tạp hơn cho tải portfolio.
3. Redis hoặc hàng đợi: không chọn; tạo thêm nguồn trạng thái và phục hồi phân tán khi PostgreSQL đã đủ.

## Hợp đồng dự kiến

Thêm `CatalogReservations` trong proto riêng cùng package `agora.catalog.v1`, dùng thư viện catalog-contract và listener Catalog hiện có. Giữ token nội bộ, loopback, message cap và giới hạn concurrency hiện tại. Không có HTTP public hoặc thông tin lô trong CartOffer.

- `Hold`: buyer_id, checkout_id (UUID bền vững do Commerce cấp trước RPC), shop_id, 1–20 dòng product_id/sku_id/quantity (1–99). SKU không trùng, cùng shop. checkout_id là idempotency identity lâu dài, không phải credential. Canonical payload sắp theo SKU; cùng checkout_id khác buyer/shop/payload bị từ chối, không tiết lộ snapshot của người khác.
- `Get`: buyer_id và checkout_id, trả trạng thái hiện tại, không snapshot HELD cũ.
- `Release`: buyer_id và checkout_id; lặp lại là no-op, không trừ held lần hai. Chỉ giải phóng HELD; trạng thái cuối không hồi sinh.
- Response: checkout_id, buyer_id, shop_id, HELD/RELEASED, created_at/expires_at, lý do release khi có, các dòng SKU/quantity và tham chiếu lot/quantity nội bộ. Không địa chỉ, session, token hoặc thông tin liên hệ. Contract chỉ dùng cho server đã được tin cậy; buyer binding không thay thế xác thực người mua ở Commerce.

Hold replay trả trạng thái hiện tại của reservation gốc và expires_at gốc, không gia hạn. Giữ row/identity sau release, không áp dụng cleanup idempotency 7 ngày của giỏ cho reservation: retry checkout cũ không được tạo hold mới. Lỗi thiếu tồn rollback và không ghi reservation thành công; caller giữ cùng checkout_id khi chưa biết kết quả. Get NOT_FOUND cho chưa commit không chứng minh request concurrent đã thất bại.

RPC errors: INVALID_ARGUMENT cho UUID/count/quantity/duplicate không hợp lệ; NOT_FOUND khi identity không thuộc buyer hoặc chưa có; FAILED_PRECONDITION cho shop/sản phẩm/SKU không bán được hoặc thiếu tồn; ALREADY_EXISTS cho checkout_id cùng owner nhưng payload khác; UNAVAILABLE cho dependency hoặc DB connection; INTERNAL cho lỗi lập trình, không trả SQL/secrets. Token kiểm trước đọc/replay/write. Không retry tự động tại transport.

## Transaction và FEFO

Hold mới kiểm shop ACTIVE qua Merchant trước transaction, không giữ DB lock khi network I/O. Trong transaction khóa identity checkout trước, sau đó product UUID theo thứ tự ổn định, rồi lô theo thứ tự ổn định. Kiểm lại product PUBLISHED, SKU mapping đúng và có attached UPLOADED photo dưới product lock; tương thích khóa sửa/ẩn/ảnh hiện có. Merchant có thể thay đổi sau RPC; không tuyên bố snapshot xuyên service. M3c phải kiểm lại các điều kiện checkout.

Ngày hợp lệ theo Asia/Ho_Chi_Minh: lot ACTIVE, expires_on lớn hơn hôm nay. Lấy hàng theo expires_on tăng dần, tie-break lot UUID; có thể chia một SKU qua nhiều lô. available = on_hand - held - allocated. Không dùng SKIP LOCKED để bỏ lô sớm khi cấp hàng vì có thể sai FEFO hoặc báo thiếu giả; chờ khóa với timeout hữu hạn. Tăng held và version từng lô có thay đổi; không giảm on_hand hoặc allocated. Thiếu bất kỳ dòng nào rollback mọi row, bộ đếm và audit.

Thời điểm bắt đầu TTL lấy sau khi đã lấy các khóa cần thiết, không dùng thời gian bắt đầu transaction nếu vừa chờ khóa lâu. Dùng server Clock nhất quán; lấy instant một lần cho thao tác và suy ra ngày Việt Nam. expires_at = created_at + 15 phút; đúng mốc expires_at coi là hết hạn. Retry không thay thời gian này.

Release/Get hết hạn: khóa reservation trước, rồi các lot UUID thứ tự ổn định; giảm đúng phần held của reservation và tăng version của lot cùng transaction với trạng thái/audit. Không cần Merchant, product public hoặc lot ACTIVE để trả lại held: hàng bị ẩn/khóa vẫn phải được giải phóng. Release chưa từng Hold: NOT_FOUND, không tạo tombstone; caller chỉ được phát hủy sau đã xác định Hold hoặc tiếp tục đối soát bằng cùng checkout_id.

Worker giải phóng HELD quá hạn theo batch hữu hạn, mỗi reservation một transaction. Có thể dùng SKIP LOCKED khi claim reservation cho cleanup, không khi cấp tồn. Get/Release cũng kiểm hạn; scheduler trễ không làm HELD quá hạn trở thành hợp lệ. Chấp nhận lượng tồn khả dụng bị đánh giá thấp tạm thời đến lúc cleanup; không bao giờ bán vượt tồn. Không kéo cleanup tùy ý các reservation khác vào transaction Hold vì dễ đảo thứ tự khóa và deadlock.

## Dữ liệu và phạm vi sửa

Migration V6 mới, không sửa V1–V5. Bảng reservations có checkout_id PK, buyer/shop, canonical request hash, state, created/expires/released timestamps và release reason. Reservation lines lưu product/SKU/quantity; lot allocations lưu reservation/sku/lot/quantity, unique tránh đếm lặp, FK cục bộ giữ mapping. Không FK tới Commerce/Identity/Merchant. Audit reservation riêng ghi HOLD/RELEASE/EXPIRE và các tham chiếu; không sửa nghĩa quantity_delta của inventory_audit hiện có.

Các chỗ cần chạm: proto/catalog-contract generated build; Catalog domain/application/store/JDBC; RPC endpoint đăng ký trên listener cũ; migration; scheduler; tests PostgreSQL/concurrency/RPC; tài liệu kiến trúc/data/state/security/demo và HANDOFF. Kiểm các constructor CatalogGrpcServer trong tests khi wiring đổi. Không cần đổi frontend, .env secrets hoặc native PostgreSQL.

## Kế hoạch và điều kiện nghiệm thu

- R-01: rà thiết kế/khóa hiện có, chốt proto và giới hạn payload; kiểm response đủ bounded. Read-only review thiết kế trước implementation.
- R-02: domain và migration/store; test FEFO nhiều lô/tie-break, rollback multi-SKU, competing last stock, same checkout concurrent, payload mismatch, expiry boundary, release lặp, replay sau expiry, lot BLOCKED khi release, version/audit và dữ liệu migration cũ.
- R-03: RPC/service/worker; test token/strict UUID/limits/owner binding, Merchant unavailable và không gọi dependency cho release/replay, deadline và DB error mapping. Test race expiry-vs-release, hold-vs-block/edit, cleanup nhiều worker; không chỉ test happy path.
- R-04: root Maven verify tuần tự với PostgreSQL Docker biệt lập; independent review hoặc tự review mới nếu không có subagent. Kiểm RPC thật với expiry có Clock kiểm soát trong fixture. Đồng bộ docs/HANDOFF, kiểm diff/secrets/links, dọn đúng runtime sở hữu. Không rerun frontend khi frontend và contract public không đổi.

Điểm dừng: M3b có bằng chứng nghiệm thu. Không tự bắt đầu M3c trong cùng acceptance. Không stage/commit/push/deploy; không reset/migrate native5432. Không dùng fixture/ADMIN cũ đã xóa. Chưa cần credential mới từ người dùng.

## Chốt sau review thiết kế

Reviewer độc lập phát hiện sáu điểm; xử lý trước implementation:

- Identity checkout chưa có row: dùng advisory transaction lock theo checkout_id, rồi đọc lại; PK vĩnh viễn là backstop. lock_timeout 2 giây và statement_timeout 5 giây, contention/timeout trả UNAVAILABLE để caller retry cùng identity.
- Replay transaction kiểm owner/hash/hạn trước Merchant. Nếu chưa có mới gọi Merchant ngoài transaction, sau đó claim/recheck. Khi Merchant lỗi, kiểm lại replay để không che lần concurrent vừa commit.
- Khóa tất cả product theo UUID, sau đó tất cả lot theo UUID; chỉ thứ tự phân bổ trong memory dùng expires_on/UUID. Release cũng khóa lot UUID, không lấy product lock.
- Lấy tối đa 1001 candidate lô chưa hết hạn, bất kể state/counters, giới hạn xử lý 1000 lô/request. Vượt giới hạn trả RESOURCE_EXHAUSTED, tuyệt đối không báo thiếu hàng hoặc chọn FEFO từ tập bị cắt. Lock/statement timeout chặn chờ vô hạn. Response tối đa 1980 lot references và 20 dòng; reservation client/fixture dùng receive cap128KiB, có test serialized worst case. Offers client64KiB không đổi vì chưa dùng reservation.
- Đọc Clock sau khóa, kiểm lại ngày Việt Nam/state/available; lô hết hạn trong lúc chờ bị loại. created_at/expires_at chuẩn hóa microsecond để round-trip PostgreSQL nhất quán.
- V6 thêm UNIQUE(lot_id,sku_id) tương ứng và composite FK cho allocation, cùng FK về reservation line.

### File và interfaces triển khai

Core: `domain/Reservation.java` immutable records Request/Line/Item/Allocation; `application/ReservationStore.java`; `application/ReservationService.java`; `adapter/outbound/JdbcReservationStore.java`; `db/migration/V6__reservations.sql`. Service public `hold(Request)`, `get(UUID buyer, UUID checkout)`, `release(UUID buyer, UUID checkout)` trả Reservation; `expire()` trả số reservation đã giải phóng.

RPC: `contracts/proto/agora/catalog/v1/catalog_reservations.proto`; `adapter/inbound/ReservationRpc.java`; đăng ký endpoint vào `configuration/CatalogGrpcServer.java`. Test `ReservationGrpcTests.java` và cập nhật constructor trong `CatalogOffersGrpcTests.java`. Các test core riêng `ReservationDomainTests`, `ReservationStoreTests`, `ReservationServiceTests`. Rà TRUNCATE fixture hiện có trước rootverify do V6 thêm FK.

Thứ tự TDD: worker core độc quyền Maven focused RED/GREEN trước; main viết proto/docs song song, chưa đưa RPC Java phụ thuộc core vào compile. Sau đó main test RPC RED, wiring/endpoint GREEN, rootverify tuần tự. Không tự commit theo mẫu skill vì người dùng giữ quyền commit.

Re-review thiết kế đã đóng sáu finding. Giữ precision microsecond của PostgreSQL; proto dùng `created_at_epoch_micros`/`expires_at_epoch_micros` để không cắt mất phần lẻ khi trả thời hạn. Không thay precision core xuống millisecond.

## Điều chỉnh sau review implementation ngày27/10

Lượt RPC đầu25tests đạt, chưa là acceptance. Review actual core tìm ba gap đã được sửa bằng regression: worker không bỏ đói batch khi một reservation bị contention; Hold phải khóa cả lô chưa hết hạn đang depleted/BLOCKED trước lọc eligibility để không bỏ sót lô vừa release trong lúc chờ; TransactionTimedOutException phải ra503. Cap1000 áp dụng **mọi lô chưa hết hạn của SKU được yêu cầu**, không chỉ lô đang có available. Đây là giới hạn tài nguyên rõ ràng, có thể từ chối request dù tồn đủ; khi có nhu cầu nhiều lô hơn cần thiết kế paging/locking riêng, không cắt tập FEFO rồi cấp hàng.

## Kết quả nghiệm thu ngày27/10/2026

R01–R04 hoàn tất local. Root274testsPASS lúc10:14:28+07:00, 0failure/error/skip, gồm48M3b (2domain/9service/23store/11RPC/3integration). Review độc lập actual core patch và RPC/wiring CLEAR. Các lỗi review đã có RED/GREEN; root lần đầu fail một fixture configuration thiếu bean mới, đã bổ sung đúng dependency và giữ assertion trước khi root274PASS. [Guide/proof](../docs/demo-reservations.md) ghi log, giới hạn và môi trường test.

Hai draft RPC trong target đã đưa vào source chính thức; không còn phụ thuộc file nháp để chạy test. Frontend không đổi, nativeDB/backend.env/providers không đổi, không stage/commit/push/deploy. Dừng ở nghiệm thu M3b; bước sau là thiết kế M3c checkout một shop/GHN, chưa bắt đầu.
