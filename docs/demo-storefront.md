# Demo storefront — M2c2, đã nghiệm thu local ngày 25/10/2026

Đã triển khai đúng hai GET public: `/api/v1/products` và `/api/v1/products/{productId}`. [Contract1.0.0](../contracts/openapi/catalog-storefront.v1.openapi.json), [plan canonical](../tasks/slice-2-storefront.md), [ranh giới/allowlist](architecture/catalog-security.md#storefront-m2c2--public-projection). Frontend/live/review đã hoàn tất, bằng chứng cuối ghi trong demo storefront; các lệnh dưới là hướng dẫn, docs worker không chạy chúng.

## Chuẩn bị theo luồng demo hiện có

Dùng môi trường/database đã chọn theo [demo Catalog](demo-catalog.md) và [onboarding](demo-onboarding.md). Live dùng ba database PostgreSQL thử nghiệm biệt lập, không reset/migrate native database để tạo proof. Cấu hình local ignored giữ server-only; không in/source secret. Không giả định fixture/ADMIN cũ còn dùng được.

Từ `backend/`, cài contracts trước khi chạy service độc lập:

```bash
./mvnw -B -ntp -pl libraries/identity-contract,libraries/merchant-contract -am install
```

Mỗi lệnh sau chạy ở một terminal riêng, cwd `backend/`; Identity/Merchant bật gRPC theo demo Catalog:

```bash
./mvnw -pl services/identity-service spring-boot:run
./mvnw -pl services/merchant-service spring-boot:run
./mvnw -pl services/catalog-service spring-boot:run
```

Từ `frontend/` đã cài dependencies theo hướng dẫn hiện có:

```bash
npm run dev
```

Tạo owner/shop, ADMIN riêng duyệt ACTIVE; lưu product, upload và attach ảnh đã xác minh rồi công bố theo [demo Catalog](demo-catalog.md). Có thể nhập lô theo [demo inventory](demo-inventory.md); không cần lô để xem công khai. Catalog V5 chỉ thêm partial index PUBLISHED(created_at DESC,id DESC), V1–V4 không đổi.

## Quan sát browse/detail

1. Mở cửa sổ chưa đăng nhập tại `http://127.0.0.1:3000/products`, chọn product đã công bố để mở `/products/{productId}`. Public GET không cần cookie/CSRF và không gọi Identity; response no-store. Chỉ PUBLISHED+attached UPLOADED photo+shop hiện ACTIVE xuất hiện; detail không khả dụng404.
2. Tìm tên/lọc category, origin, minPriceVnd/maxPriceVnd hoặc shopId. Giá từ là min mọi SKU, kể cả hết tồn. Khi đổi bộ lọc phải bỏ cursor cũ; unknown/duplicate/malformed query, UUID hoặc cursor sai trả422.
3. Browse đọc21candidate, chỉ xét20đầu theo created_at DESC,id DESC; Merchant kiểm tối đa4batch×5ID, không kiểm candidate thứ21. Sau lọc shop, trang có thể rỗng nhưng còn `nextCursor`; tiếp tục **Xem tiếp** khi cursor chưa null. Không total count hay snapshot xuyên request.
4. Detail hiển thị giá/quy cách gói, ảnh, nguồn gốc/bảo quản, tên shop và stock boolean theo product/SKU. Public shop chỉ `{id,name}`, name tối đa120codepoints giữ nguyên; legacy blank bị omit. Không trả owner/contact/pickup/review reason, version/audit/upload intent, SKU code/thông số giao hàng, mã/ngày lô hoặc exact stock; [allowlist/trường private](architecture/catalog-security.md#storefront-m2c2--public-projection).
5. `inStock` chỉ xét lô ACTIVE, expires_on>hôm nay Asia/Ho_Chi_Minh và on_hand-held-allocated>0. Khóa/hết hạn/hết tồn làm boolean false, sản phẩm vẫn xem được. Seller ẩn product thì GET mới không còn trả public. Mỗi GET kiểm shop ACTIVE hiện tại; account owner bị khóa không tự đổi shop status. RPC lỗi/timeout/malformed làm cả request503, không trả page một phần.

Chưa có reservation/FEFO allocation, checkout, GHN/VNPAY, review/Q&A hoặc profile shop đầy đủ. `inStock` không hứa giữ/mua/giao hàng hay chứng minh an toàn thực phẩm. Chuẩn bị/cleanup ảnh và final live proof theo plan hiện có do main thực hiện; không suy ra provider cleanup từ việc UI ẩn sản phẩm.

## Bằng chứng backend ngày25/10/2026

Main báo root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify` kết thúc **20:50:38 +07:00 BUILD SUCCESS**, **185tests:13Identity+59Merchant+113Catalog**, **0fail/error/skip**. Catalog mới20=4query+13API+3service, gồm HTTP DB outage trả503 an toàn và SQL syntax vẫn500. StorefrontService map lỗi DB public ở lớp hẹp; không đổi private error mapping. Đây là backend proof do main cung cấp, không phải lượt chạy của docs worker hoặc proof frontend/live/review. Main cung cấp final proof và quyết định nghiệm thu toàn lát cắt theo plan canonical.

## Nghiệm thu local ngày 25/10/2026

Backend 185 tests, frontend build/lint/typecheck, 130 unit và 199 fixture production đều đạt. Năm normal live đạt trong 12s; storefront provider live đạt 1/1 trong 5.1s trên build `zx004P8288ULcLGmmtqDn`. Review backend/frontend không còn finding cần sửa trong phạm vi này.

Live dùng ba service và PostgreSQL Docker test biệt lập, ảnh PNG mới trên Cloudinary; không intercept API. Đã kiểm anonymous browse/filter/detail, cookie sai, UUID hoa, projection, khóa/mở lô làm mới stock, ẩn sản phẩm trả404 và biến mất khỏi list. Ba intent ảnh mới đều DELETED/0 liên kết; exact provider lookup cả ba trả missing, exit0. Một lượt trước vướng selector của bài test, một lượt upload503 được giữ riêng; không tính chúng là PASS. Failed intent được cho hết hạn có kiểm soát trong fixture riêng rồi worker dọn.

[Bằng chứng frontend và giới hạn](../../frontend/docs/storefront-proof.md). Chưa có GitHub Actions run hoặc deploy được xác nhận; chưa triển khai reservation/checkout/review/Q&A.
