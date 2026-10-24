# M2a — Sản phẩm nháp và SKU đóng gói

Ngày: 23/10/2026. Kế hoạch dưới quyền tự quyết người dùng đã giao; tracker canonical là claude-mem `slice-2-catalog`, không dùng file này làm checklist trạng thái thứ hai.

## Mục tiêu và phạm vi

Chủ shop ACTIVE tạo, xem danh sách, đọc và sửa sản phẩm DRAFT với 1–10 SKU đóng gói. Luồng chạy xuyên Next → Catalog HTTP → Identity/Merchant gRPC → PostgreSQL Catalog riêng. Giữ PostgreSQL-only theo ADR-0003. Đây là phần đầu M2; chưa có upload/công bố sản phẩm, tồn kho/lô, mua hàng hoặc tìm kiếm công khai. Không tạo nút công bố hay ảnh giả.

Ảnh Cloudinary nối ở phần kế tiếp với quyền upload/gắn/xóa và metadata xác minh theo UC-15. Người dùng đã có tài khoản; chưa cấu hình credential. Chỉ xin cấu hình secret trong ignored env khi có implementation cần dùng.

## Lựa chọn triển khai

Catalog là service riêng theo kiến trúc đã chốt; database dự kiến `agora_catalog`, HTTP loopback 8083. Tái sử dụng mẫu JDBC/Flyway, lỗi, CSRF, idempotency và version guard của Merchant, không import JAR nghiệp vụ Merchant. Một service mới cần thêm wiring/test/runtime nhưng giữ đúng quyền sở hữu Catalog/Inventory.

Merchant bổ sung RPC `MerchantAccess.ResolveActiveShop(account_id) -> shop_id`, metadata `x-agora-internal-token`; local bind 127.0.0.1:9092, giới hạn request 4 KiB. Lỗi account ID sai → INVALID_ARGUMENT; chưa có shop hoặc shop không ACTIVE → PERMISSION_DENIED; lỗi persistence → UNAVAILABLE. Identity tiếp tục ResolveSession ở 9091. Catalog gọi cả hai với deadline 2 giây, không retry và fail closed. Mỗi request seller xác minh phiên/quyền shop hiện tại trước cả replay idempotency; không nhận shop/owner từ client.

## Hợp đồng HTTP và dữ liệu

Nguồn canonical: `contracts/openapi/catalog.v1.openapi.json`, artifact riêng không sửa contract M1.

- GET/POST `/api/v1/seller/products`: list phân trang hoặc tạo DRAFT, POST trả 201/Location.
- GET/PUT `/api/v1/seller/products/{productId}`: đọc hoặc thay toàn bộ thông tin nháp, PUT cần expectedVersion; tài nguyên khác shop trả 404.
- Mutation cần cookie + masked CSRF + UUID Idempotency-Key. Key giữ 7 ngày, retry cùng payload trả snapshot gốc; payload khác 422, in-flight 409; mọi thay đổi và audit/result trong một transaction.
- Product: name 3–160, description nullable tối đa 5000, category PRODUCE/SEAFOOD, condition FRESH/FROZEN/DRIED/PROCESSED, origin 1–120, producerName nullable tối đa 160, storageInstructions 1–1000; trường bắt buộc không chỉ khoảng trắng. Server sinh id/shopId/status/version/timestamps.
- SKU: code 1–40 theo `[A-Z0-9][A-Z0-9_-]{0,39}`, label 1–100, priceVnd số nguyên 1–1.000.000.000, netWeightGrams/shippingWeightGrams số nguyên 1–50.000, shippingWeightGrams >= netWeightGrams, lengthCm/widthCm/heightCm số nguyên 1–200. Đơn vị PACKAGE cố định. Giới hạn này là quy tắc nhập MVP, chưa khẳng định GHN hỗ trợ mọi kiện.
- Code SKU duy nhất trong shop, có constraint PostgreSQL. PUT thay danh sách SKU của DRAFT; code giữ nguyên thì giữ SKU id, code mới sinh id mới, code bỏ khỏi draft được xóa. Chưa có inventory tham chiếu SKU ở M2a; lát cắt lô phải khóa việc xóa/đổi định danh đang được tham chiếu trước khi mở tính năng đó.
- Giá VND dùng bigint/Java long; client chỉ nhận safe integer. Không nhận giá float, field ngoài allowlist, trạng thái công khai, ảnh hoặc ID sở hữu do client khai báo.
- List page 0, pageSize mặc định 20/tối đa 100, thứ tự createdAt DESC/id DESC; response gồm items/page/pageSize/totalItems.

## Các nhịp triển khai và bằng chứng

1. Chốt HTTP/proto contract và wiring reactor. File: OpenAPI trên, `contracts/proto/agora/merchant/v1/merchant_access.proto`, `libraries/merchant-contract/pom.xml`, root POM. Kiểm tra schema/ref/operationId và protobuf generation; không đổi revision artifact M1.
2. Merchant RPC. File: Merchant POM/configuration/resources và `MerchantGrpcTests`. Viết test trước, thấy RED, sau đó listener/auth + đọc ShopService.mine. Test caller sai/thiếu token, UUID sai, chưa shop, DRAFT/PENDING/REJECTED/SUSPENDED, ACTIVE, thay trạng thái hiện tại, outage và shutdown; không trả PII.
3. Catalog backend theo contract. File: `services/catalog-service/{pom.xml,src/**}`; chia bootstrap/domain, persistence+API, auth/security thành các nhịp nhỏ. Viết test trước mỗi nhịp. Test real PostgreSQL: create/list/read/update, two-shop isolation, CSRF/session, shop chưa ACTIVE/outage, strict/Unicode/numeric validation, code trùng, stable SKU id, optimistic race, idempotency/in-flight/expiry/payload mismatch và rollback audit/SKU/version/result.
4. Frontend. File: artifact/type Catalog; `src/features/catalog`, routes `/seller/products`, `/seller/products/new`, `/seller/products/[productId]`, API path allowlist, rewrite Catalog, account navigation và tests/docs. Dùng component/style có sẵn; loading/empty/error/403/401/409; form SKU 1–10, retry giữ key, stale form yêu cầu tải lại, không persist credential. Test unit RED/GREEN, browser fixture/mobile/keyboard/axe; không tải thư viện UI mới.
5. Tích hợp và review. Full Maven verify; frontend audit/lint/typecheck/unit/build/fixture; live ba backend với PostgreSQL Docker biệt lập và chủ shop ACTIVE do admin duyệt. Chứng minh reload/edit và owner khác bị chặn, không dùng mock RPC trong live. Dừng đúng app/container do task tạo; native database không reset. Cập nhật README/HANDOFF, không tự stage/commit/push/deploy.

## Điểm review bắt buộc

- Phiên còn cookie nhưng shop bị thu hồi ACTIVE: mutation và replay đều bị chặn.
- Hai SKU trùng hoặc code xung đột sản phẩm khác: toàn bộ mutation rollback, không có product/audit nửa chừng.
- Unicode, số thập phân/số rất lớn, SKU rỗng hoặc vượt 10: trả lỗi có kiểm soát trước JDBC write.
- Client giả owner/status/shopId hoặc thay ID để đọc/sửa sản phẩm shop khác: không vượt quyền.
- HTTP thành công nhưng payload lỗi, timeout hoặc mất mạng: retry cùng ý định giữ key cho tới response hợp lệ.

## Điều kiện kết thúc M2a

Luồng tạo/sửa sản phẩm nháp và SKU hoạt động qua UI + ba backend thật; các kiểm tra trên đạt và M1 không regress. Không gọi toàn bộ M2 hoàn tất khi chưa có ảnh Cloudinary, công bố sản phẩm, lô/tồn hoặc browse công khai.
