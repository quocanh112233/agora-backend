# Demo lô hàng — M2c1

M2c1 cung cấp nhập/xem/khóa/mở lô hàng cho chủ shop ACTIVE, trên Product DRAFT/HIDDEN/PUBLISHED. Chưa có reservation, điều chỉnh số lượng, xuất hàng hoặc checkout. [Plan](../tasks/slice-2-inventory.md), [contract](../contracts/openapi/catalog-inventory.v1.openapi.json), [proof local](../../frontend/docs/catalog-inventory-proof.md).

## Chuẩn bị

Chạy Identity/Merchant/Catalog và Next theo [README](../README.md), [demo onboarding](demo-onboarding.md) và [demo Catalog](demo-catalog.md). Chọn đúng database từng service; integration/live dùng PostgreSQL Docker biệt lập, không truncate/reset native database. Flyway áp dụng V4 khi Catalog khởi động vào database được cấu hình. Không sửa migration đã áp dụng hoặc đưa credential vào tài liệu.

Tạo tài khoản người bán, mở shop, dùng ADMIN riêng duyệt ACTIVE. Không có API public cấp ADMIN. Tạo sản phẩm đã lưu với1–10SKU đóng gói. Không cần ảnh Cloudinary để nhập tồn; ảnh vẫn là điều kiện công bố riêng.

## Luồng UI

1. Mở sản phẩm đã lưu, chọn **Quản lý lô hàng** tại `/seller/products/{productId}/lots`.
2. Chọn SKU rõ ràng, nhập mã lô uppercase1–40, số lượng nguyên1–1.000.000gói. Ngày thu hoạch/sản xuất tùy chọn, không sau hôm nay; hạn dùng bắt buộc sau hôm nay.
3. Bấm **Nhập lô**. GET mới hiển thị số gói và trạng thái; lô bắt đầu version0, held/allocated0. Nhập lô không đổi version/nội dung/visibility Product.
4. **Khóa** và **Mở** tăng version lô, giữ nguyên bộ đếm. Hết hạn từ00:00 ngày hạn dùng tại Asia/Ho_Chi_Minh. BLOCKED có thể đã hết hạn; không mở lô đó. EXPIRED được tính trên request, không chờ scheduler.
5. Tải lại trang xác nhận dữ liệu được lưu. Đổi giá/nhãn hoặc reorder SKU vẫn giữ ID/lô; SKU đã có lô không được xóa/đổi code hoặc đổi net/shipping weight/dimensions/saleUnit. Phải ẩn PUBLISHED trước khi sửa nội dung, nhưng quản lý lô không cần ẩn.
6. Form chưa gửi được giữ khi focus/pageshow/tab visible còn quyền. SKU bị xóa khỏi product phải chọn lại, không tự đổi sang SKU khác. Đăng xuất tab khác rồi focus sẽ xóa dữ liệu riêng.

Retry sau timeout/5xx giữ cùng ý định/key trong bộ nhớ; không tự gửi lại. Snapshot replay là lịch sử, GET mới xác định status hiện tại. Nếu GET sau thành công lỗi, khóa thao tác đến khi tải được bản mới. 409 yêu cầu tải lại, giữ key của ý định chưa rõ khi body không đổi. Reload toàn trang mất intent; kiểm tra danh sách trước khi nhập lại.

## API và dữ liệu

- GET/POST `/api/v1/seller/products/{productId}/lots`.
- PUT `/api/v1/seller/products/{productId}/lots/{lotId}/status` nhận ACTIVE/BLOCKED và expectedVersion.
- Cookie+CSRF hiện hành, Idempotency-Key UUID; current Identity/Merchant permission trước mọi request/replay. Không nhận shop/actor/counters do client khai báo.
- Body strict tối đa2048bytes;400 JSON,422 input/key mismatch,401/403 quyền/CSRF,404 tài nguyên ngoài phạm vi,409 state/version/inflight,503 dependency.
- Lot/audit/key commit cùng transaction. available=onHand-held-allocated không âm, là số gói vật lý, không khẳng định đủ điều kiện mua.

## Kiểm chứng local ngày24/10

Backend150tests13/44/93 đạt16:03:17, gồm17inventory tests mới; review backend clear. Source frontend cuối đạt build/lint/types,98unit,177production fixtures và5live; review lại hai P2 inventory và focus auth không có blocker. Đối chiếu DB của live cuối: ACTIVE12/0/0, lotVersion2/productVersion1, audit lô3/request3/SKU2/audit product2. Bằng chứng chi tiết và các lượt RED/failed trước sửa ở [proof](../../frontend/docs/catalog-inventory-proof.md).

Runtime test là fixture tạm `--rm`, không phải demo có named volume. Sau khi dọn phải tạo fixture/ADMIN mới trước lần live tiếp theo; không dùng file ADMIN cũ cho database mới. Chưa có production hoặc GitHub Actions run, không tự commit/push/deploy.
