# M2b2 — Công bố và ẩn sản phẩm Implementation Plan

> For agentic workers: triển khai theo các bước dưới bằng native execution và sidecar frontend/review có write scope tách biệt. Canonical checklist là claude-mem `slice-2-catalog`, không ghi checklist trạng thái thứ hai trong file này. Người dùng đã giao quyền tự quyết định và tiếp tục; không cần duyệt lại lựa chọn thông thường, không tự commit/push/deploy.

**Goal:** Chủ shop ACTIVE công bố sản phẩm có ảnh hợp lệ, ẩn và chỉnh sửa sản phẩm đã ẩn, có version/audit/idempotency.

**Architecture:** Giữ Catalog PostgreSQL/DDD và SellerAccess hiện tại. Thêm một thao tác visibility, tái sử dụng ProductStore/key/audit/transaction; FlywayV3 mở rộng trạng thái, không sửa V1/V2. Frontend quản lý trạng thái ngay editor/list đã có, không thêm service hoặc SDK.

**Tech Stack:** Java25/SpringBoot4.1.1/JDBC/Flyway/PostgreSQL16, Next16/React19/TypeScript, gRPC xác minh Identity/Merchant hiện tại.

**Spec:** [UC-03](../docs/product/use-cases.md), [trạng thái Product](../docs/product/state-machines.md), [quyền Catalog](../docs/architecture/catalog-security.md). Phạm vi M2b2 quản lý visibility; UC-04 inventory/UC-05 storefront và checkout là lát cắt sau.

## Global Constraints

- Chỉ thực hiện sau nghiệm thu M2b1: fix PNG, FE revalidation, root/live/provider cleanup và review/docs phải có proof.
- Mọi request/replay cần phiên/currentACTIVEshop/ownership hiện tại; mutation có CSRF. Private responses no-store, lỗi ApiError hiện có. Không đọc/join database service khác.
- Native database không dùng test/reset; provider test chỉ ảnh tổng hợp namespace ứng dụng. Không thêm dependency, không secret trong Git/log/frontend.
- Đây là revision chưa phát hành, có một frontend đi kèm: mở rộng Product.status trong canonical `catalog.v1.openapi.json` và pin/sinh lại type frontend cùng thay đổi. Giữ field/path/key/DTO cũ; không duy trì hai client/schema song song hoặc âm thầm báo PUBLISHED thành DRAFT.
- BLOCKED/admin moderation chưa triển khai; không nhận trạng thái tùy ý từ client. Chưa có public browse/checkout API; PUBLISHED trong lát cắt này không chứng minh sản phẩm đã có tồn hoặc mua được.

## Interface và trạng thái

`PUT /api/v1/seller/products/{productId}/visibility`, JSON đúng hai field `{status:"PUBLISHED"|"HIDDEN",expectedVersion:integer}`, UUID Idempotency-Key;200Product gồm toàn bộ SKU như GET.400 malformed/duplicate JSON hoặc body quá1024bytes,401/403 quyền,404ownership,409version/state/inflight,422UUID/input/payload/publication chưa đủ ảnh/dữ liệu,503dependency. Đọc body có giới hạn sau SellerAccess, parse bằng mapper strict duplicate/trailing tokens; không auto retry.

- DRAFT/HIDDEN →PUBLISHED: currentshopACTIVE, product có dữ liệu bắt buộc không trắng và1–10SKU, ít nhất1ảnh relation tới uploadUPLOADED cùng product/shop; không dùng upload chưa attach. Khóa product trước kiểm tra ảnh để detach/edit cạnh tranh không vượt điều kiện công bố.
- PUBLISHED →HIDDEN: giữ nguyên nội dung/SKU/images, tăng version/audit. Cùng target trạng thái nhưng key mới trả409; replay key thành công cũ vẫn trả snapshot sau xác minh quyền/payload.
- Product/SKU và media chỉ được sửa khi DRAFT hoặc HIDDEN. Sửa HIDDEN giữ HIDDEN, không tự công bố lại. PUBLISHED chỉ đọc thông tin quản lý; cần ẩn trước khi sửa/đổi ảnh. Không mở public image-management route cho PUBLISHED.
- Visibility không thay SKU ID, giá, ảnh hoặc updated schema cũ. Guard version tối đa9_007_199_254_740_991; trạng thái/version/audit/key snapshot cùng transaction. Key7ngày theo actor/operation/resource/hash.

## Review Focus

1. Race công bố với gỡ ảnh cuối: khóa product, chỉ một trạng thái hợp lệ thắng; công bố trước thì detach bị409, detach trước thì publish422.
2. Replay sau shop/account bị thu hồi: xác minh quyền trước snapshot, giữ401/403/404.
3. Key đổi status/product/version:422, không đổi dữ liệu/audit; failure rollback không giữ key nửa chừng.
4. Chỉnh sửa HIDDEN không tự PUBLISHED, và PUBLISHED không cho cập nhật SKU/ảnh; frontend không làm mất form khi thao tác status chưa thành công.
5. Response mạng/malformed hoặc version conflict: key giữ cho cùng ý định; lỗi409 khóa và yêu cầu reload rõ ràng; dữ liệu private bỏ khi401/403.
6. Giá/nội dung đang gõ chưa lưu: không công bố snapshot cũ. Nút/handler Công bố bị khóa khi form dirty, có hướng dẫn Lưu trước; thêm/xóa SKU cũng đánh dấu dirty. Chỉ reset dirty sau save/reload có chủ ý hoặc load product mới.

## Task1 — Contract, domain và persistence

Files: sửa `contracts/openapi/catalog.v1.openapi.json` (Product.status enum và visibility operation/input), `services/catalog-service/src/main/java/com/agora/catalog/domain/Product.java`, `application/{ProductStore,ProductService}.java`, `adapter/outbound/JdbcProductStore.java`, `adapter/inbound/ProductController.java`, `adapter/outbound/JdbcMediaStore.java`; tạo `resources/db/migration/V3__product_visibility.sql` và test `ProductVisibilityTests.java`. Dùng đúng package/path của Catalog hiện có, không tạo bộ framework visibility riêng.

Interfaces: `Product.changeVisibility(long expectedVersion,String status) -> Product`; `ProductStore.lockOwned(UUID shopId,UUID id)->Optional<Product>`, `hasVerifiedImage(UUID shopId,UUID id)->boolean`, `changeVisibility(Product,long expectedVersion)->void`; `ProductService.changeVisibility(Key,UUID,long,String,String traceId)->Result`. Controller ánh xạ status/key/hash, gọi SellerAccess trước usecase. ProductStore.Result/key/claim/complete/audit giữ nguyên kiểu và retention.

1. Viết domain/integration RED: thiếu ảnh422 kể cả uploadPENDING/chưaattach; DRAFT→PUBLISHED→HIDDEN→editHIDDEN→republish, version mỗi mutation+1, SKU/images giữ nguyên; samekey replay snapshot/audit đúng1, changedpayload422; stale/versionmax409, foreign404, nonACTIVE/CSRF bị chặn; race publish/detach, rollback, malformed JSON/status. Test FlywayV2 có dữ liệu lênV3 giữ SKU/ảnh/key.
2. Chạy focused test, ghi failure đúng thiếu behavior. Dùng PostgreSQLcontainer/fakeImageStorage/RPC fixture hiện có, không gọi Cloudinary mặc định.
3. ThêmV3 thay check status thànhDRAFT/PUBLISHED/HIDDEN; domain transition và lock/ảnh check như trên; reuse transaction/key/audit. Product.replace cho HIDDEN và giữ trạng thái, JDBC update guard cùng rule. Media product guard nhận DRAFT/HIDDEN, tiếp tục chặn PUBLISHED.
4. Chạy focusedGREEN, root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify`, review source/migration. Chạy Maven tuần tự; không clean/ghi đè JAR app đang chạy.

## Task2 — Frontend đồng bộ contract và controls

Files: pin `frontend/contracts/catalog.v1.openapi.json`, sinh `frontend/src/generated/catalog.ts`; sửa `frontend/src/features/catalog/api.ts`, `frontend/src/features/catalog/product-editor.tsx`, `frontend/src/features/catalog/products-view.tsx`, `frontend/src/lib/api/http.ts`, `frontend/next.config.ts`, `frontend/tests/unit/{catalog,routing}.spec.ts`, `frontend/tests/fixtures/catalog-browser.ts`, `frontend/tests/e2e/catalog.spec.ts`; cập nhật text route/layout nếu còn ghi mọi sản phẩm là nháp. Không sửa ảnh/source auth ngoài chỗ cần tương thích trạng thái.

Interface: thêm `createCatalogIntent` kind `visibility` với `{productId,status,expectedVersion}`, trả Product đã kiểm schema. Reader chấp nhận đúng3status; UI label Bản nháp/Đã công bố/Đã ẩn. Saved editor có nút Công bố hoặc Ẩn, cùng busy guard với Save; Công bố còn cần form không dirty (ProductForm báo cả input/add/removeSKU). PUBLISHED không hiện form sửa hoặc link quản lý ảnh, có hướng dẫn ẩn để sửa. HIDDEN vẫn sửa/ảnh và giữ HIDDEN; trạng thái/error422 công bố có thông báo an toàn yêu cầu ảnh/dữ liệu.

1. TDD request/status reader/key stability, unchangedfailedform,409reload,401/403clearing, stableSKU, no duplicate status submit; nhập giá mới hoặc thêm/xóa SKU chặn Công bố cho đến khi Save thành công, không mất nội dung sau lỗi.
2. Thêm strict visibility allowlist/rewrite; sinh type bằng openapi-typescript đã cài, không thêm package.
3. Lint/typecheck/build/unit/full fixture production, keyboard/mobile/axe tại controls thay đổi. Giữ artifact browser/private credential guard hiện có.

## Task3 — Live, tài liệu và điểm dừng

Main copyJAR mới từ rootverify vào path mới, restart Catalog trên fixtureDocker hiện thuộc dự án. Opt-in live dùng owner/shop tổng hợp, ADMIN fixture hiện có và PNG mới: create→upload/attach→publish→reload/liststatus→hide→edit vẫnHIDDEN→republish→hide→detach; SKU giữID, version/audit/key đúng, cleanup chỉ ảnh mới và providerfind404 sauworker. Không triển khai public browse/checkout giả để chứng minh visibility.

Cập nhật README hai repo, contractdigest, architecture/data-model/security/demo và HANDOFF với số test thực tế/process/limitations; giữ proof M2a/M2b1 theo ngày. Kiểm tra diff/secret scan và review cuối. Đóng S2-06B chỉ khi behavior/gates/live đã đạt; inventory/storefront là bước kế tiếp, không đánh dấu M2 toàn bộ đã xong.
