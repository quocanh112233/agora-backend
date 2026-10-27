# Catalog M3b — Giữ tồn FEFO

Trạng thái: **đã nghiệm thu local ngày27/10/2026**, root verify274tests đạt và review cuối CLEAR. [Thiết kế và kế hoạch](../tasks/slice-3-reservations.md), [proto](../contracts/proto/agora/catalog/v1/catalog_reservations.proto).

## Luồng và giới hạn

Catalog giữ hàng cho một checkout của một buyer tại một shop. Một request có 1–20 SKU khác nhau, mỗi SKU 1–99 gói. Chọn lô ACTIVE còn hạn theo ngày Việt Nam, ưu tiên expires_on rồi UUID. Một SKU có thể lấy từ nhiều lô. Thiếu bất kỳ SKU nào rollback toàn bộ; available luôn là on_hand-held-allocated.

`CatalogReservations.Hold` kiểm shop hiện ACTIVE trước transaction mới. `Get` trả trạng thái hiện tại. `Release` hủy hold, lặp lại không đổi tồn lần hai. Token nội bộ được kiểm trước mọi thao tác. Buyer/checkout identity phải khớp; Caller phải xác thực người mua trước khi gọi; Commerce M3c hiện thực bước này trước HTTP preview/confirm. Không có HTTP public trực tiếp cho raw reservation; [demo checkout](demo-checkout.md) ghi source hiện có/đã nghiệm thu local ngày27/10/2026.

checkout_id là identity bền vững: cùng payload replay cùng reservation; payload khác bị từ chối. Retry không gia hạn TTL15 phút. Reservation RELEASED không được hồi sinh; row được giữ để chặn retry checkout cũ tạo hold mới. Thời gian RPC trả epoch microseconds, cùng precision PostgreSQL. Worker và Get/Release cùng kiểm hạn bằng server Clock, không phụ thuộc riêng lịch chạy worker.

Khi shop/product/lot bị ẩn hoặc khóa sau Hold, replay và release vẫn hoạt động. Release giảm đúng held, không tăng on_hand. M3b chưa chuyển ALLOCATED/SHIPPED, chưa khóa giá hoặc tạo đơn. Checkout/GHN/VNPAY thuộc phần sau.

## Cạnh tranh và tài nguyên

Advisory transaction lock theo checkout_id bảo vệ cả row chưa tồn tại. Hold khóa product theo UUID rồi lot theo UUID; FEFO dùng khi phân bổ sau khóa. Release khóa reservation rồi lot theo cùng thứ tự UUID, không khóa product. Không giữ connection/transaction trong lúc gọi Merchant. Replay không cần Merchant; lỗi Merchant được đối soát lại với lần concurrent đã commit.

Tối đa1000 candidate lô chưa hết hạn của SKU được yêu cầu, kể cả BLOCKED/depleted, mỗi request; đọc1001 để nhận biết vượt giới hạn và trả RESOURCE_EXHAUSTED, không dùng tập bị cắt để cấp hàng hoặc báo thiếu tồn. Lock timeout2giây, statement timeout5giây. Request cap4KiB, reservation response cap128KiB. Client Offers hiện có64KiB vẫn chỉ gọi Offers; caller typed HoldCheckout trong Commerce M3c hiện dùng128KiB.

Cleanup trễ có thể làm available thấp hơn thực tế một thời gian; không làm bán vượt tồn. Khi hết hạn, trạng thái trả về không còn HELD kể cả worker chưa chạy. Lô hết hạn khi đang chờ khóa được kiểm lại sau khóa. Cấu hình loopback/token là phạm vi local hiện tại, không phải triển khai mạng production.

## Kiểm chứng

Focused core: `./mvnw -B -ntp -pl services/catalog-service -am -Dtest=ReservationDomainTests,ReservationServiceTests,ReservationStoreTests -Dsurefire.failIfNoSpecifiedTests=false -Dagora.test.postgres-image=postgres:16 test`.

RPC: `./mvnw -B -ntp -pl services/catalog-service -am -Dtest=ReservationGrpcTests,ReservationRpcIntegrationTests,CatalogOffersGrpcTests -Dsurefire.failIfNoSpecifiedTests=false -Dagora.test.postgres-image=postgres:16 test`.

Root: `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify`.

Các lệnh chạy từ backend, sử dụng PostgreSQL Docker test biệt lập. Không khởi động ứng dụng với backend/.env hoặc migrate native5432 để chạy bài test. Test integration dùng Catalog listener thật, Merchant fixture gRPC và Clock kiểm soát; không gọi Cloudinary/GHN/VNPAY. Số liệu và review cuối ở phần nghiệm thu bên dưới.

## Nghiệm thu local ngày27/10/2026

Root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify` **BUILD SUCCESS lúc10:14:28+07:00**, total1phút36giây. **274tests = 13Identity + 59Merchant + 178Catalog + 24Commerce, 0failure/error/skip**. Catalog178 gồm130baseline và48M3b: 2domain +9service +23store +11RPC +3SpringRPCintegration. Không cộng các lượt focused lần nữa vào root total.

| Gate | Bằng chứng |
| --- | --- |
| RPC ban đầu | 10test RED do UNIMPLEMENTED trên listener cũ; nối endpoint rồi25test gồm12Offers/10ReservationRPC/3integration PASS lúc10:03:17 |
| Review core | 7test RED gồm2failure/2error tái hiện các gap; sửa rồi45focused PASS lúc10:09:14, gồm34core+11RPC |
| Error classification | 1test RED vì trạng thái nội bộ sai bị báo INVALID_ARGUMENT; fix parse UUID riêng, trạng thái nội bộ lỗi ra INTERNAL; nằm trong11RPC đã GREEN |
| Root | 274PASS như trên, migration V6/legacyfixtures và mọi service đều đạt |
| Review cuối | Singer đọc actual core patch/regressions và RPC/wiring: CLEAR; không còn finding mở trong phạm vi M3b |

Review tìm và đã sửa: (1) một reservation contention không được làm dừng xử lý phần còn lại của batch; (2) chọn chỉ lô đang có available ở snapshot cũ có thể bỏ sót lô vừa release/reopen trong lúc chờ, nay khóa mọi candidate chưa hết hạn theo UUID rồi mới lọc; (3) TransactionTimedOutException phải map503. Regression thật dùng hai connection và latch/quan sát DB lock, kiểm các race release/reopen, cap BLOCKED/depleted, cleanup cạnh tranh và parent mutation hide/edit/ảnh cuối. Lỗi lập trình trong cleanup vẫn được ném ra, không bị coi là retryable.

Root lần đầu **FAIL lúc10:11:41** vì CatalogConfigurationTests chưa có mock dependency ReservationRpc mới: expected IllegalArgumentException cho token sai nhưng got NoSuchBeanDefinitionException. Bổ sung bean fixture, không hạ assertion; focused2PASS lúc10:12:21 rồi root mới274PASS. Không gọi lần FAIL là PASS.

Integration dùng Spring listener TCP thật, protobuf/token, PostgreSQL Docker và Merchant gRPC fixture; kiểm Hold/replay/Get/Release/owner, lỗi Merchant không chặn replay/release, lô BLOCKED/productHIDDEN và đúng mốc expiry/worker. Không có checkout/4service manual live mới hoặc cuộc gọi provider thật. Frontend/publicHTTP contract không đổi nên không chạy lại frontend; proof M3a trước đó giữ riêng.

Proof local ignored: `target/reservation-proof-20261027/` giữ log RED/GREEN/root FAIL/root PASS, manifest165source/config/contract và acceptance.json; không phải file cần push. Một XML IdentityApplicationTests lịch sử từ21/10 còn trong target nhưng không có source/current run; **loại khỏi tổng**, lấy danh sách Running của root log đối chiếu XML. Không báo275test từ việc cộng mọi XML cũ.

Testcontainers tự dọn sau JVM; không có app runtime/provider cần cleanup mới. Native PostgreSQL và backend/.env không đổi. Tại nghiệm thu M3b 27/10 lúc10:14:28+07:00, giới hạn là1000candidate lô chưa hết hạn, request4KiB/response128KiB và chưa có checkout. M3c hiện có source theo [demo riêng](demo-checkout.md), đã nghiệm thu local ngày27/10/2026; không thêm ALLOCATED/SHIPPED. GitHub CI thật/deploy chưa được kiểm trong lượt này.
