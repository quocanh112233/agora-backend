# Agora — Bản đồ nghiệp vụ MVP v0.1

## Tiến độ M3b ngày27/10/2026

Catalog giữ tồn FEFO qua RPC nội bộ Hold/Get/Release, TTL15phút và cleanup/replay bền vững; chưa có ALLOCATED/SHIPPED. Giỏ vẫn chỉ giữ ý định mua; checkout một shop/GHN và VNPAY lần lượt M3c/M3d. Các mô tả reservation chưa triển khai ở mốc M2/M3a dưới đây là lịch sử theo ngày. [Plan M3b](../../tasks/slice-3-reservations.md), [trạng thái nghiệm thu](../demo-reservations.md).

Trạng thái: bản nháp để review. Bộ tài liệu gồm ranh giới nghiệp vụ, use case và trạng thái; chưa phải kế hoạch triển khai hoặc hợp đồng API hoàn chỉnh.

Cập nhật implementation25/10/2026: M1, Catalog sản phẩm/SKU/ảnh/visibility và M2c1 quản lý lô đã nghiệm thu local. M2c2 đã triển khai public browse/detail qua đúng hai GET, đã nghiệm thu local ngày 25/10/2026; backend proof theo ngày ở [demo storefront](../demo-storefront.md), frontend/live/review đã đạt, xem bằng chứng cuối trong demo storefront. Các khả năng checkout/giữ hàng/GHN/VNPAY/review/Q&A trong bản đồ bên dưới vẫn là đích.

Cập nhật 26/10/2026: Commerce là service thứ tư đã có giỏ theo account, nhiều shop/tối đa 20 SKU/1–99 gói mỗi dòng. Current offers do Catalog xác minh qua RPC9093; giỏ không giữ tồn/checkout. Root backend 226 tests đạt theo main 10:55:16 +07:00; giỏ M3a đã nghiệm thu local ngày 26/10; checkout chưa triển khai nên chưa nghiệm thu toàn UC-06. [Demo giỏ](../demo-cart.md), [plan M3a](../../tasks/slice-3-cart.md).

## 1. Mục tiêu và phạm vi đã xác nhận

Website marketplace B2B2C cho nông sản và thủy hải sản, phục vụ portfolio cá nhân. Người bán dự kiến là hợp tác xã, cơ sở sản xuất và cửa hàng; khách mua là cá nhân. Người dùng làm một mình, không đặt hạn chót cố định.

Tiêu chí thành công: hoàn thành một luồng mua hàng có thể demo, bảo vệ tồn kho/tiền khi xảy ra lỗi, giải thích được ranh giới DDD và các quyết định kiến trúc.

| Thành phần | Quyết định |
|---|---|
| Kiến trúc/backend | Microservice + DDD; Java, Spring Boot; gRPC nội bộ |
| Frontend | Next.js + Zustand |
| Dữ liệu | Chỉ PostgreSQL, gồm cả review, hỏi đáp và phản hồi; database thuộc từng service |
| Hình ảnh | Cloudinary cho sản phẩm/avatar; đề xuất thêm ảnh review/khiếu nại |
| Thanh toán/vận chuyển | VNPAY sandbox; GHN staging/sandbox |
| Checkout | Một shop mỗi lần thanh toán |
| Vận hành | Docker, GitHub Actions |

Nền tảng: Java 25/Spring Boot 4.1.1, Maven Wrapper 3.9.16, Next.js 16/TypeScript/npm; session JDBC và cookie HttpOnly. Xem [ADR-0001](../decisions/0001-foundation.md), [ADR-0002](../decisions/0002-separate-identity-merchant.md) và [ADR-0003](../decisions/0003-postgresql-only.md). Hiện có Identity/Merchant/Catalog/Commerce; Commerce mới triển khai giỏ M3a. Payment/Feedback, checkout và deployment chưa triển khai.

## 2. Mặc định thiết kế đề xuất

1. SKU đóng gói cố định, ví dụ gạo túi 5 kg hoặc tôm gói 500 g; mua số lượng nguyên. Chưa cân lại để điều chỉnh giá sau thanh toán.
2. Một chủ shop có một shop, một điểm lấy hàng; chưa quản lý nhân viên. Mỗi đơn một kiện; checkout báo không hỗ trợ nếu kiện vượt giới hạn dịch vụ.
3. Trả trước qua VNPAY, chưa COD. Khách trả tiền hàng và cước cho sàn; bên gửi trả cước GHN, không thu lần hai từ người nhận.
4. Giữ hàng 15 phút. Cấu hình thời hạn thanh toán tương thích; tiền đến muộn vẫn phải được ghi nhận và xử lý.
5. Hủy trước bàn giao. Nếu đã có vận đơn, xác nhận hủy vận đơn trước khi chốt hủy đơn/giải phóng hàng.
6. Đề xuất cửa sổ khiếu nại 24 giờ từ mốc giao thành công đã xác nhận. Đây là chính sách demo, không phải tuyên bố về nghĩa vụ pháp lý thực tế. Khiếu nại mở chặn hoàn tất/đối soát.
7. Lô phải còn hạn và đáp ứng thời gian sử dụng còn lại theo chính sách sản phẩm, xét thời gian giao dự kiến nếu có. Ngày tháng không tự chứng minh an toàn thực phẩm.
8. Hoàn tiền và thu hồi hàng là hai quyết định riêng. Hàng hư hỏng không tự nhập lại tồn bán được.
9. Tính hoa hồng và đối soát trả shop ở mức mô phỏng; không ví nội bộ hoặc chuyển tiền thật.
10. Sandbox không chứng minh khả năng vận chuyển lạnh hoặc việc GHN nhận mọi loại hàng ở production.

## 3. Vai trò

| Vai trò | Quyền nghiệp vụ |
|---|---|
| Khách vãng lai | Xem/tìm sản phẩm, shop, review và hỏi đáp |
| Người mua | Giỏ, checkout, đơn của mình, khiếu nại, đánh giá |
| Chủ shop | Shop, sản phẩm, lô, đơn của shop và phản hồi |
| Admin | Duyệt shop, kiểm duyệt, giải quyết khiếu nại và đối soát mô phỏng; có audit |
| Hệ thống | Hết hạn, retry, IPN/webhook, đối chiếu kết quả |

Một người có thể vừa mua vừa bán. Backend xác minh quyền sở hữu từng tài nguyên; không dựa vào nút đã ẩn trên frontend.

Public hiện chỉ PUBLISHED có ảnh attached UPLOADED và shop hiện ACTIVE. Merchant chỉ công khai `{id,name}`, name tối đa120codepoints, omit legacy blank; không có profile shop đầy đủ. Browse giới hạn20candidate, Merchant tối đa4batch×5ID, có thể trả trang rỗng mà còn nextCursor. Product/SKU `inStock` chỉ là boolean từ lô ACTIVE còn hạn Việt Nam và available>0; không phải hứa giữ/mua/giao hàng. Không public PII, version/audit/upload intent, mã/ngày lô hoặc số lượng tồn; [allowlist/trường private](../architecture/catalog-security.md#storefront-m2c2--public-projection).

## 4. Bounded context và dữ liệu sở hữu

Module ID ổn định, dùng để truy vết tài liệu sau. Phụ thuộc là phụ thuộc hợp đồng nghiệp vụ; thông điệp hai chiều không có nghĩa cùng sở hữu dữ liệu.

| Module ID | Trách nhiệm/dữ liệu sở hữu | Phụ thuộc |
|---|---|---|
| `identity-access` | Account, session, hồ sơ/avatar, vai trò toàn sàn | Không |
| `merchant` | Shop, chủ sở hữu, duyệt/khóa, điểm lấy hàng, liên kết GHN | `identity-access` |
| `catalog-inventory` | Product, SKU, Category, InventoryLot, Reservation, ảnh sản phẩm, nguồn gốc khai báo | `merchant` |
| `payment` | PaymentAttempt, ProviderTransaction, Refund, sổ tiền, đối soát mô phỏng | Nhận snapshot/tham chiếu; không gọi Commerce để sửa đơn |
| `fulfillment` | Shipment, kiện, báo giá, mã/trạng thái GHN, lịch sử đồng bộ | `merchant` |
| `commerce` | Cart, Checkout, Order, Cancellation, QualityClaim; điều phối giao dịch | `identity-access`, `merchant`, `catalog-inventory`, `payment`, `fulfillment` |
| `feedback` | Review, Question, Answer, SellerReply, kiểm duyệt; PostgreSQL | `identity-access`, `merchant`, `catalog-inventory`, `commerce` |
| `notification` | Thông báo trong ứng dụng, đã đọc, lịch sử gửi | Sự kiện module nguồn; không quyết định giao dịch |

Thứ tự làm rõ hợp đồng: Identity và Payment; Merchant; Catalog/Inventory và Fulfillment; Commerce; Feedback và Notification. Đây là quan hệ phụ thuộc, chưa phải lịch triển khai.

### Nhóm triển khai ban đầu đề xuất

| Ứng dụng | Context |
|---|---|
| `identity-service` | Identity/Access |
| `merchant-service` | Merchant |
| `catalog-service` | Catalog/Inventory |
| `commerce-service` | Commerce, Fulfillment, Notification |
| `payment-service` | Payment |
| `feedback-service` | Feedback |

Đích có 6 ứng dụng theo ADR-0002; Identity bootstrap trước, Merchant tách riêng. Context không bắt buộc là process riêng. Fulfillment có adapter GHN riêng dù chạy cùng Commerce. Catalog/Inventory được nhóm ở MVP để làm rõ transaction cục bộ cho SKU/giá và giữ hàng; đánh giá lại khi nghiệp vụ phát triển.

Có thể dùng chung instance PostgreSQL nhưng tách database/role theo ứng dụng. Context cùng ứng dụng dùng interface module và bảng/schema có chủ sở hữu; không đọc/ghi trực tiếp dữ liệu service khác hoặc dùng foreign key xuyên service.

M3a đã có [ba HTTP operation giỏ](../../contracts/openapi/commerce-cart.v1.openapi.json) và [CatalogOffers.ResolveOffers](../../contracts/proto/agora/catalog/v1/catalog_offers.proto) đọc giá/quy cách/stock boolean hiện tại. Các hợp đồng reservation/checkout bên dưới vẫn chưa triển khai.

## 5. Hợp đồng cần đặc tả tiếp

| Provider | Năng lực | Bất biến |
|---|---|---|
| Catalog/Inventory | Báo giá, giữ/phân bổ/giải phóng/xuất hàng | Không âm tồn; giữ toàn bộ giỏ của shop hoặc không giữ; chống trùng |
| Payment | Tạo attempt, xác minh, tra cứu, hoàn tiền | Ghi tiền một lần; tổng hoàn không vượt đã thu |
| Fulfillment | Báo giá, tạo/tra cứu/hủy vận đơn, nhận trạng thái | Timeout không dẫn đến tạo kiện trùng mù quáng |
| Commerce | Quyền đánh giá, điều phối đơn, hủy, khiếu nại | Một shop/đơn; snapshot ổn định; kiểm tra chủ thể |
| Feedback | Review, hỏi đáp, phản hồi, kiểm duyệt | Một review/dòng đơn đủ điều kiện |

Payment lưu kết quả xác minh rồi thông báo bền vững cho Commerce, không cập nhật database đơn trong handler IPN. Fulfillment cũng không sở hữu trạng thái Order.

HTTP/JSON cho web qua routing cùng origin, chưa thêm BFF nghiệp vụ; gRPC cho lời gọi nội bộ đồng bộ. Theo [ADR-0001](../decisions/0001-foundation.md), chọn RabbitMQ, transactional outbox và consumer dedup/inbox khi bắt đầu luồng liên service; chưa cần broker ở lát cắt mở shop. Không giả định giao thông điệp đúng một lần.

## 6. PostgreSQL và Cloudinary

- PostgreSQL lưu dữ liệu của mọi service, gồm đơn, tiền, kho, vận đơn và nội dung review/Q&A/reply. Feedback có database riêng; chỉ tạo khi triển khai M5.
- Review, question, answer và seller reply dùng bảng quan hệ; nội dung là text, rating/khóa/trạng thái có cột và constraint rõ. JSONB chỉ dành cho metadata tùy chọn, không thay mô hình quan hệ chính.
- Feedback xác minh điều kiện đăng qua Commerce, không tự xem bản sao đơn là nguồn sự thật. Không join/FK xuyên service dù cùng dùng PostgreSQL.
- MVP tính điểm sao từ review đang hiển thị; sửa/ẩn review phải được phản ánh khi tính. Projection ở service khác có thể trễ và phải tính lại được; không cần transaction phân tán toàn sàn.
- Cloudinary chứa ảnh; context sở hữu tài nguyên lưu `public_id`, metadata và tham chiếu đối tượng.
- Backend kiểm soát quyền upload/gắn/xóa; secret không ra client. Dọn ảnh mồ côi có retry; không xóa ảnh còn tham chiếu hợp lệ.

## 7. Hoãn khỏi MVP

Checkout nhiều shop; chia/chuyển tiền thật; giá theo cân thực tế; bán sỉ thương lượng; đặt trước theo mùa; nhiều kho/tách kiện; chuỗi lạnh/đo nhiệt độ; COD/ví; flash sale/quảng cáo/livestream/AI; chat realtime; Kubernetes/service mesh.

## 8. Tài liệu và nguồn

- [Use case và tiêu chí nghiệm thu](use-cases.md)
- [Trạng thái và quy tắc chuyển](state-machines.md)
- [VNPAY thanh toán/IPN](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html)
- [GHN callback](https://api.ghn.vn/home/docs/detail?id=84)
- [Data ownership trong microservices](https://learn.microsoft.com/en-us/azure/architecture/microservices/design/data-considerations)
- [PostgreSQL JSONB](https://www.postgresql.org/docs/current/datatype-json.html)
- [Zustand với Next.js](https://zustand.docs.pmnd.rs/learn/guides/nextjs)

Quyền tự quyết người dùng giao cho phép tiếp tục thiết kế/kế hoạch theo các ranh giới và mặc định này; xem [kiến trúc](../architecture/architecture-overview.md) và [kế hoạch](../../tasks/plan.md). Đây vẫn là mô hình demo, không suy ra chính sách production hoặc quyền publish/deploy ngoài phạm vi.
