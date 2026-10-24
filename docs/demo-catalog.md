# Demo Catalog — sản phẩm, ảnh và công bố/ẩn

Phạm vi hiện tại: shop ACTIVE quản lý DRAFT/HIDDEN với 1–10 SKU, ảnh Cloudinary và công bố/ẩn. M2a/M2b1 đã nghiệm thu; M2b2 đã nghiệm thu local ngày 24/10/2026 khoảng 15:40 +07:00; P2 đã sửa/review chấp nhận, normal live 4/4 và media/publication live 2/2 PASS; hai asset mới cleanup DB/provider đã xác minh; build/lint/typecheck, 86 unit và 144 fixture production sau sửa đã PASS, cleanup hai asset trước pause đã được main xác minh. Chưa có inventory/reservation, storefront hoặc checkout; PUBLISHED không chứng minh mua được hàng.

Bằng chứng lịch sử: M2a root verify 23/10 lúc 22:16 đạt 82 tests; M2b1 sau sửa PNG ngày 24/10 lúc 10:05:50 đạt 116; M2b2 ngày 24/10 lúc 10:57:21 +07:00 đạt **133 tests**. Frontend trước pause đạt 86 unit/125 fixture/4 normal live/2 media-publication live. Sau resume ngày 24/10, main báo **86 unit/144 fixture production PASS, 23.8s**, gồm **19 regression revalidation mới**, cùng production build/lint/typecheck PASS và native audit exit 0/zero vulnerabilities. Final static review Heisenberg clear; normal live mới **4/4 PASS (6.7s)** và media/publication live **2/2 PASS (8.2s)**. P2 và M2b2 đã nghiệm thu local sau khi main xác minh hai asset mới DELETED/0 relation, final DRAFT/version 2 và HIDDEN/version 7, exact provider lookup 2 asset all_missing=true/exit 0. Runtime cleanup đã xác nhận: Next/ba backend dừng, backend graceful exit 130; container ephemeral `--rm` đã bị xóa và các cổng test đóng. ADMIN file ignored mode 600 hiện STALE; hướng dẫn này dùng để tái dựng môi trường, không có demo đang chạy hoặc named volume. Main xác nhận final secret scan sau cập nhật docs: 216 Git-visible files/0 actual-secret matches; cả hai env mode 600. Không có production/CI claim, inventory chưa triển khai. Xem [README backend](../README.md) và [proof publication frontend](../../frontend/docs/catalog-publication-proof.md). Các lệnh bên dưới là hướng dẫn, không phải bằng chứng đã chạy.

**Môi trường sau nghiệm thu 24/10:** app thử nghiệm đã dừng và fixture database trong container `--rm` đã bị xóa. `.env.live-test` ignored mode 600 hiện STALE. Chạy lại cần fixture/ADMIN giả mới; native database giữ nguyên.

## Chuẩn bị backend

Máy hiện có PostgreSQL native 16, ba database `agora`, `agora_merchant`, `agora_catalog`. File `backend/.env` ignored mode 600 chứa credential local và token nội bộ, không source hoặc in file này vào log.

- Identity: DB_NAME=agora, SERVER_PORT=8081, IDENTITY_GRPC_ENABLED=true, IDENTITY_GRPC_PORT=9091.
- Merchant: MERCHANT_DB_NAME=agora_merchant, MERCHANT_SERVER_PORT=8082, MERCHANT_GRPC_ENABLED=true, MERCHANT_GRPC_PORT=9092.
- Catalog: CATALOG_DB_NAME=agora_catalog, CATALOG_SERVER_PORT=8083.
- Cùng INTERSERVICE_TOKEN ngẫu nhiên URL-safe cho ba app. Khi clone mới, dùng `.env.example` và credential của mình; không ghi đè file local đang có.

Chạy từ `backend/`:

```bash
./mvnw -B -ntp -pl libraries/identity-contract,libraries/merchant-contract -am install
```

Ba terminal riêng, mỗi terminal có cwd `backend/`:

```bash
./mvnw -pl services/identity-service spring-boot:run
./mvnw -pl services/merchant-service spring-boot:run
./mvnw -pl services/catalog-service spring-boot:run
```

Mỗi lệnh chạy một process ở một terminal. Health ở `http://127.0.0.1:8081/actuator/health`, cổng 8082 và 8083 tương ứng. Flyway áp dụng migration vào database của từng app; Catalog hiện có V1–V3, không sửa migration đã áp dụng. Đây là hướng dẫn local nếu chủ động khởi động, không phải yêu cầu migrate native để chạy live tests. Lưu ý health UP không thay kiểm chứng quyền gRPC.

## Web và tài khoản demo

Từ `frontend/`, `npm ci --ignore-scripts`, rồi `npm run dev`. Origin server-only mặc định: Identity 8081, Merchant 8082, Catalog 8083; dùng `.env.example` nếu đổi. Dùng cùng hostname 127.0.0.1 xuyên suốt phiên; rebuild/restart Next khi đổi rewrite origin.

Theo [demo onboarding](demo-onboarding.md), tạo người bán, tạo/gửi shop và dùng tài khoản ADMIN riêng để duyệt ACTIVE. Script cấp ADMIN chỉ chạy thủ công vào đúng database Identity; task không tự cấp ADMIN cho account native. Người chưa có shop ACTIVE sẽ nhận 403 khi gọi API Catalog.

## Luồng kiểm tra

1. Người bán đăng nhập, mở `/seller/products`; danh sách ban đầu rỗng, bấm tạo nháp tại `/seller/products/new`.
2. Điền tên, nhóm Nông sản/Thủy hải sản, trạng thái bảo quản, xuất xứ khai báo, hướng dẫn bảo quản; mô tả/cơ sở sản xuất tùy chọn. Chưa có xác minh chứng nhận/xuất xứ ở bước này.
3. Thêm SKU, ví dụ `RAU-500`, nhãn `Gói 500 g`, giá 25000 VND, khối lượng tịnh 500 g, khối lượng giao 600 g, kích thước 20 × 15 × 10 cm. Không nhập số thập phân cho các trường số nguyên. Thông số này chưa bảo đảm GHN chấp nhận vận chuyển.
4. Tạo nháp, tải lại và mở chi tiết `/seller/products/{id}`; dữ liệu phải được đọc lại từ Catalog/PostgreSQL. Đổi giá hoặc thêm SKU mới, lưu rồi tải lại.
5. SKU có code giữ nguyên phải giữ ID. DRAFT/HIDDEN hiện cho đổi/xóa code; trước khi có tồn/đơn phải bảo vệ tham chiếu, không dùng thao tác này để xóa lịch sử hàng đã bán.
6. Mở cùng sản phẩm ở hai tab. Tab thứ nhất lưu thành công; tab còn lại gửi version cũ phải nhận 409 và yêu cầu tải lại thay vì ghi đè.
7. Người bán khác không được đọc/sửa sản phẩm bằng cách đổi product ID. Code SKU trùng trong cùng shop bị từ chối; cùng code ở shop khác được phép.
8. Mở **Quản lý ảnh**, chọn JPEG/PNG, **Tải ảnh lên**, rồi **Gắn ảnh**. Upload chưa attach không đủ điều kiện công bố. Cần cấu hình [Cloudinary server-only](cloudinary-local.md); tối đa 5 MiB/20 triệu pixel/tệp và 5 ảnh gắn.
9. Quay lại editor, lưu mọi thay đổi rồi **Công bố**; nút bị chặn khi form dirty, kể cả thêm/xóa SKU. Tải lại editor/list và kiểm nhãn Đã công bố. PUBLISHED không có form sửa hoặc link quản lý ảnh; backend cũng chặn sửa/SKU/ảnh.
10. **Ẩn sản phẩm**, sửa giá và lưu: trạng thái vẫn HIDDEN, SKU code không đổi giữ ID. Công bố lại là thao tác riêng. Trước khi gỡ ảnh để dọn, ẩn sản phẩm; gỡ chỉ bỏ relation, worker xử lý provider sau. Main xác minh đúng reserved ID, không suy ra provider deletion từ UI.

Mọi mutation lấy CSRF và UUID intent key; lỗi mạng không tự retry. Khi người dùng thử lại cùng nội dung, key giữ nguyên cho tới response thành công hợp lệ. Tất cả quyền vẫn do backend thực thi.

## Kiểm thử biệt lập

Backend: `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify`. Frontend: audit, lint, typecheck, unit, build và browser fixture theo README frontend. Browser fixture không chứng minh RPC/database thật.

Live cần ba backend trỏ tới **ba database Docker thử nghiệm**, không dùng dữ liệu native; Identity bật gRPC, Merchant bật gRPC, token nội bộ đồng nhất. Tạo ADMIN fixture qua registration + script vào đúng DB thử nghiệm. File `frontend/.env.live-test` ignored, mode 600, chứa TEST_ADMIN_EMAIL/TEST_ADMIN_PASSWORD giả; `npm run test:live` nạp file này. Không gửi password/secret/cookie vào chat hoặc bật trace/video/form screenshot.

Live tạo synthetic account/shop/product, không tự reset database. Dừng các process/container do chính mình tạo sau nghiệm thu, giữ nguyên database native. Thiếu cấu hình ADMIN fixture phải làm test lỗi rõ ràng, không skip rồi báo đạt.

## Contract và giới hạn nghiệm thu

[Catalog 1.1.0](../contracts/openapi/catalog.v1.openapi.json) có năm operation, gồm `PUT /api/v1/seller/products/{productId}/visibility` với `{status:"PUBLISHED"|"HIDDEN",expectedVersion}` và UUID Idempotency-Key. Công bố/ẩn giữ SKU/ảnh, tăng version và ghi audit/key cùng transaction. Dữ liệu bắt buộc, 1–10 SKU và ít nhất một relation tới upload UPLOADED cùng product/shop là điều kiện công bố; product lock dùng chung với media mutation. PUBLISHED phải ẩn trước sửa. [Contract media](../contracts/openapi/catalog-media.v1.openapi.json) có bốn operation upload/list/attach/detach; không nhận URL/public_id tùy ý từ form.

[Hướng dẫn publication frontend](../../frontend/docs/catalog-publication.md) ghi retry/conflict và giới hạn P2 trước pause: quay lại editor/list sau logout/thu hồi quyền ở tab khác có thể còn dữ liệu riêng đã load, trong khi backend vẫn chặn mọi request/replay theo quyền hiện tại. Bản sửa có 19 regression trong full 144 fixture PASS, gồm giữ form dirty, khóa version conflict, recovery và chặn kết quả muộn sau denial/unmount. Recovery/thông báo 409 đã được sửa, final static review clear và P2 được chấp nhận. Normal live mới 4/4 PASS gồm logout/re-login ở tab chia sẻ session, editor/list clearing/recovery và giữ giá dirty khi focus; test-only fix đợi DELETE logout 204 trước focus, production code không đổi. Hai media/publication live cũng PASS; M2b2 đã nghiệm thu local sau main cleanup proof của cả hai asset mới; artifacts/IDs trong publication proof.

Opt-in `media-live` hiện có cả bài media và publication, bật `TEST_CATALOG_MEDIA=1` và chọn `--project=media-live` theo [proof](../../frontend/docs/catalog-publication-proof.md). `npm run test:live` chỉ chạy bốn normal live cases. Không dùng file ADMIN cũ cho fixture mới. Khoảng 15h ngày 24/10, main exact lookup hai reserved ID từ cleanup artifacts trước pause: all_missing=true, exit 0. Hai asset của lần live mới cũng đã được xác minh DB DELETED/0 relation và exact provider lookup all_missing=true/exit 0; artifacts/IDs ở proof nghiệm thu. Không quét/xóa media khác.
