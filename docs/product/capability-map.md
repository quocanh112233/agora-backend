# Agora — Bản đồ nghiệp vụ MVP v0.1

## Tiến độ M3b ngày27/10/2026

Catalog giữ tồn FEFO qua RPC nội bộ Hold/Get/Release, TTL15phút và cleanup/replay bền vững; M3d mới thêm CONSUMED. Giỏ vẫn chỉ giữ ý định mua; checkout một shop và VNPAY lần lượt M3c/M3d, M3c đã có implementation preview/accepted hold/attempt/orders, đã nghiệm thu local ngày27/10/2026; [demo/evidence](../demo-checkout.md). Các mô tả reservation chưa triển khai ở mốc M2/M3a dưới đây là lịch sử theo ngày. [Plan M3b](../../tasks/slice-3-reservations.md), [trạng thái nghiệm thu](../demo-reservations.md).

Trạng thái: thiết kế đích cập nhật theo ADR-0004 ngày27/10/2026; các mốc implementation ghi riêng theo ngày. Bộ tài liệu gồm ranh giới nghiệp vụ, use case và trạng thái; chưa phải kế hoạch triển khai hoặc hợp đồng API hoàn chỉnh.

Cập nhật implementation25/10/2026: M1, Catalog sản phẩm/SKU/ảnh/visibility và M2c1 quản lý lô đã nghiệm thu local. M2c2 đã triển khai public browse/detail qua đúng hai GET, đã nghiệm thu local ngày 25/10/2026; backend proof theo ngày ở [demo storefront](../demo-storefront.md), frontend/live/review đã đạt, xem bằng chứng cuối trong demo storefront. Các khả năng checkout/giữ hàng/GHN/VNPAY/review/Q&A lúc đó chưa triển khai; GHN nay bị loại theo ADR-0004, M3b giữ hàng đã nghiệm thu 27/10.

Cập nhật 26/10/2026: Commerce là service thứ tư đã có giỏ theo account, nhiều shop/tối đa 20 SKU/1–99 gói mỗi dòng. Current offers do Catalog xác minh qua RPC9093; giỏ không giữ tồn/checkout. Root backend 226 tests đạt theo main 10:55:16 +07:00; giỏ M3a đã nghiệm thu local ngày 26/10; checkout chưa triển khai nên đã nghiệm thu local ngày27/10/2026 toàn UC-06. [Demo giỏ](../demo-cart.md), [plan M3a](../../tasks/slice-3-cart.md).

## 1. Mục tiêu và phạm vi đã xác nhận

Cập nhật 27/10/2026 theo [ADR-0004](../decisions/0004-order-and-payment-only.md): chỉ đặt hàng/thanh toán, không vận chuyển hoặc nhận tại shop. Bỏ GHN/Fulfillment, địa chỉ người nhận, kiện, phí và tracking khỏi target. [M3c checkout](../../tasks/slice-3-checkout.md) đã có implementation, đã nghiệm thu local ngày27/10/2026; Payment/PAID thuộc M3d. Những mốc implementation ở trên là lịch sử, không proof M3c.

Website marketplace B2B2C cho nông sản và thủy hải sản, phục vụ portfolio cá nhân. Người bán dự kiến là hợp tác xã, cơ sở sản xuất và cửa hàng; khách mua là cá nhân. Người dùng làm một mình, không đặt hạn chót cố định.

Tiêu chí thành công: hoàn thành một luồng mua hàng có thể demo, bảo vệ tồn kho/tiền khi xảy ra lỗi, giải thích được ranh giới DDD và các quyết định kiến trúc.

| Thành phần | Quyết định |
|---|---|
| Kiến trúc/backend | Microservice + DDD; Java, Spring Boot; gRPC nội bộ |
| Frontend | Next.js + Zustand |
| Dữ liệu | Chỉ PostgreSQL, gồm cả review, hỏi đáp và phản hồi; database thuộc từng service |
| Hình ảnh | Cloudinary cho sản phẩm/avatar; đề xuất thêm ảnh review |
| Thanh toán | VNPAY sandbox ở M3d; tổng chỉ tiền hàng |
| Checkout | Một shop mỗi lần thanh toán |
| Vận hành | Docker, GitHub Actions |

Nền tảng: Java 25/Spring Boot 4.1.1, Maven Wrapper 3.9.16, Next.js 16/TypeScript/npm; session JDBC và cookie HttpOnly. Xem [ADR-0001](../decisions/0001-foundation.md), [ADR-0002](../decisions/0002-separate-identity-merchant.md) và [ADR-0003](../decisions/0003-postgresql-only.md). Hiện có Identity/Merchant/Catalog/Commerce; Commerce có giỏ M3a và checkout/đơn M3c đã nghiệm thu local ngày27/10/2026. Payment/Feedback và deployment chưa triển khai.

## 2. Mặc định thiết kế đề xuất

1. SKU đóng gói cố định, mua số lượng nguyên; không điều chỉnh giá theo cân sau thanh toán. Metadata quy cách đã lưu giữ nguyên như legacy, không dùng tính kiện/phí.
2. Một chủ shop có một shop; chưa quản lý nhân viên. Contact/pickup_address hiện có giữ nguyên, không xóa dữ liệu hoặc đổi form/API đã nghiệm thu và không tạo luồng nhận tại shop.
3. Checkout một shop, backend tính snapshot giá/số lượng/tổng tiền hàng; không nhận tổng do client gửi. Proposal thay đổi cần khách xác nhận lại.
4. Giữ hàng FEFO 15 phút. Replay không gia hạn hoặc hồi sinh. M3c dùng HELD/RELEASED; M3d thêm HELD → CONSUMED giảm held/on_hand nguyên tử. Giữ cột allocated legacy, không thêm ALLOCATED/SHIPPED cho luồng mới.
5. Order AWAITING_PAYMENT → PAID hoặc EXPIRED/CANCELLED. PAID là terminal sau xác minh tiền và tiêu thụ tồn; tiền/hold chưa rõ phải đối chiếu trước quyết định hết hạn/hủy.
6. Refund tài chính cho khoản thu trễ/trùng vẫn cần, chống hoàn vượt captured. Refund không tự tăng tồn hay tạo trạng thái sau PAID.
7. Feedback xác minh buyer sở hữu dòng đơn PAID; không chờ DELIVERED/COMPLETED. QualityClaim sau giao và return shipment ngoài phạm vi.
8. Lô hết hạn/bị khóa không được giữ/consume mới; ngày tháng không tự chứng minh an toàn thực phẩm.
9. Hoa hồng/đối soát shop chỉ mô phỏng; không ví hoặc chuyển tiền thật.

## 3. Vai trò

| Vai trò | Quyền nghiệp vụ |
|---|---|
| Khách vãng lai | Xem/tìm sản phẩm, shop, review và hỏi đáp |
| Người mua | Giỏ, checkout, đơn của mình, đánh giá dòng đơn PAID |
| Chủ shop | Shop, sản phẩm, lô, đơn của shop và phản hồi |
| Admin | Duyệt shop, kiểm duyệt, đối chiếu tiền/refund và đối soát mô phỏng; có audit |
| Hệ thống | Hết hạn, retry, IPN/webhook, đối chiếu kết quả |

Một người có thể vừa mua vừa bán. Backend xác minh quyền sở hữu từng tài nguyên; không dựa vào nút đã ẩn trên frontend.

Public hiện chỉ PUBLISHED có ảnh attached UPLOADED và shop hiện ACTIVE. Merchant chỉ công khai `{id,name}`, name tối đa120codepoints, omit legacy blank; không có profile shop đầy đủ. Browse giới hạn20candidate, Merchant tối đa4batch×5ID, có thể trả trang rỗng mà còn nextCursor. Product/SKU `inStock` chỉ là boolean từ lô ACTIVE còn hạn Việt Nam và available>0; không phải hứa giữ/mua/giao hàng. Không public PII, version/audit/upload intent, mã/ngày lô hoặc số lượng tồn; [allowlist/trường private](../architecture/catalog-security.md#storefront-m2c2--public-projection).

## 4. Bounded context và dữ liệu sở hữu

Module ID ổn định, dùng để truy vết tài liệu sau. Phụ thuộc là phụ thuộc hợp đồng nghiệp vụ; thông điệp hai chiều không có nghĩa cùng sở hữu dữ liệu.

| Module ID | Trách nhiệm/dữ liệu sở hữu | Phụ thuộc |
|---|---|---|
| `identity-access` | Account, session, hồ sơ/avatar, vai trò toàn sàn | Không |
| `merchant` | Shop, chủ sở hữu, duyệt/khóa, hồ sơ/contact legacy | `identity-access` |
| `catalog-inventory` | Product, SKU, Category, InventoryLot, Reservation, ảnh sản phẩm, nguồn gốc khai báo | `merchant` |
| `payment` | PaymentAttempt, ProviderTransaction, Refund, sổ tiền, đối soát mô phỏng | Nhận snapshot/tham chiếu; không gọi Commerce để sửa đơn |
| `commerce` | Cart, CheckoutPreview/Attempt, Order; điều phối giữ tồn/thanh toán/hủy | `identity-access`, `merchant`, `catalog-inventory`, `payment` |
| `feedback` | Review, Question, Answer, SellerReply, kiểm duyệt; PostgreSQL | `identity-access`, `merchant`, `catalog-inventory`, `commerce` |
| `notification` | Thông báo trong ứng dụng, đã đọc, lịch sử gửi | Sự kiện module nguồn; không quyết định giao dịch |

Thứ tự làm rõ hợp đồng: Identity và Payment; Merchant; Catalog/Inventory; Commerce; Feedback và Notification. Đây là quan hệ phụ thuộc, chưa phải lịch triển khai.

### Nhóm triển khai ban đầu đề xuất

| Ứng dụng | Context |
|---|---|
| `identity-service` | Identity/Access |
| `merchant-service` | Merchant |
| `catalog-service` | Catalog/Inventory |
| `commerce-service` | Commerce, Notification |
| `payment-service` | Payment |
| `feedback-service` | Feedback |

Đích có 6 ứng dụng theo ADR-0002; Identity bootstrap trước, Merchant tách riêng. Context không bắt buộc là process riêng. Catalog/Inventory được nhóm ở MVP để làm rõ transaction cục bộ cho SKU/giá và giữ hàng; đánh giá lại khi nghiệp vụ phát triển.

Có thể dùng chung instance PostgreSQL nhưng tách database/role theo ứng dụng. Context cùng ứng dụng dùng interface module và bảng/schema có chủ sở hữu; không đọc/ghi trực tiếp dữ liệu service khác hoặc dùng foreign key xuyên service.

M3a đã có [ba HTTP operation giỏ](../../contracts/openapi/commerce-cart.v1.openapi.json) và [CatalogOffers.ResolveOffers](../../contracts/proto/agora/catalog/v1/catalog_offers.proto) đọc giá/quy cách/stock boolean hiện tại. Reservation Hold/Get/Release M3b đã nghiệm thu 27/10; quote/typed accepted-price-version hold và Commerce preview/durable attempt/orders M3c hiện có, đã nghiệm thu local ngày27/10/2026. [HTTP contract](../../contracts/openapi/commerce-checkout.v1.openapi.json), [proto](../../contracts/proto/agora/catalog/v1/catalog_checkout.proto), [demo/evidence](../demo-checkout.md).

## 5. Hợp đồng cần đặc tả tiếp

| Provider | Năng lực | Bất biến |
|---|---|---|
| Catalog/Inventory | Quote giá/version, giữ/giải phóng/consume tồn theo lát cắt | Không âm tồn; giữ toàn bộ giỏ của shop hoặc không giữ; chống trùng |
| Payment | Tạo attempt, xác minh, tra cứu, hoàn tiền | Ghi tiền một lần; tổng hoàn không vượt đã thu |
| Commerce | Quyền đánh giá dòng đơn PAID, điều phối đơn/hủy/hết hạn | Một shop/đơn; snapshot ổn định; kiểm tra chủ thể |
| Feedback | Review, hỏi đáp, phản hồi, kiểm duyệt | Một review/dòng đơn đủ điều kiện |

Payment lưu kết quả xác minh rồi thông báo bền vững cho Commerce, không cập nhật database đơn trong handler IPN.

HTTP/JSON cho web qua routing cùng origin, chưa thêm BFF nghiệp vụ; gRPC cho lời gọi nội bộ đồng bộ. Theo [ADR-0001](../decisions/0001-foundation.md), chọn RabbitMQ, transactional outbox và consumer dedup/inbox khi bắt đầu luồng liên service; chưa cần broker ở lát cắt mở shop. Không giả định giao thông điệp đúng một lần.

## 6. PostgreSQL và Cloudinary

- PostgreSQL lưu dữ liệu của mọi service, gồm đơn, tiền, kho và nội dung review/Q&A/reply. Feedback có database riêng; chỉ tạo khi triển khai M5.
- Review, question, answer và seller reply dùng bảng quan hệ; nội dung là text, rating/khóa/trạng thái có cột và constraint rõ. JSONB chỉ dành cho metadata tùy chọn, không thay mô hình quan hệ chính.
- Feedback xác minh quyền sở hữu dòng đơn PAID qua Commerce, không tự xem bản sao đơn là nguồn sự thật. Không join/FK xuyên service dù cùng dùng PostgreSQL.
- MVP tính điểm sao từ review đang hiển thị; sửa/ẩn review phải được phản ánh khi tính. Projection ở service khác có thể trễ và phải tính lại được; không cần transaction phân tán toàn sàn.
- Cloudinary chứa ảnh; context sở hữu tài nguyên lưu `public_id`, metadata và tham chiếu đối tượng.
- Backend kiểm soát quyền upload/gắn/xóa; secret không ra client. Dọn ảnh mồ côi có retry; không xóa ảnh còn tham chiếu hợp lệ.

## 7. Hoãn khỏi MVP

Checkout nhiều shop; chia/chuyển tiền thật; giá theo cân thực tế; bán sỉ thương lượng; đặt trước theo mùa; nhiều kho; COD/ví; flash sale/quảng cáo/livestream/AI; chat realtime; Kubernetes/service mesh.

## 8. Tài liệu và nguồn

- [Use case và tiêu chí nghiệm thu](use-cases.md)
- [Trạng thái và quy tắc chuyển](state-machines.md)
- [VNPAY thanh toán/IPN](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html)
- [Data ownership trong microservices](https://learn.microsoft.com/en-us/azure/architecture/microservices/design/data-considerations)
- [PostgreSQL JSONB](https://www.postgresql.org/docs/current/datatype-json.html)
- [Zustand với Next.js](https://zustand.docs.pmnd.rs/learn/guides/nextjs)

Quyền tự quyết người dùng giao cho phép tiếp tục thiết kế/kế hoạch theo các ranh giới và mặc định này; xem [kiến trúc](../architecture/architecture-overview.md) và [kế hoạch](../../tasks/plan.md). Đây vẫn là mô hình demo, không suy ra chính sách production hoặc quyền publish/deploy ngoài phạm vi.
