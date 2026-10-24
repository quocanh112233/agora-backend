# M2c1 — Lô hàng của người bán

Ngày: 24/10/2026. Tiếp nối M2b2, theo UC-04. Người dùng đã giao quyền tự quyết và tiếp tục sau commit backend ff27aaa/frontend 875863b. Canonical task là claude-mem `slice-2-inventory`; tài liệu này là thiết kế và thứ tự thực hiện, không thay tracker. Không tự commit/push/deploy hoặc sửa dữ liệu PostgreSQL native.

## Phạm vi và quyết định

Chủ shop ACTIVE nhập lô theo SKU, xem danh sách và khóa/mở lô trong Catalog hiện có. Áp dụng cho DRAFT/HIDDEN/PUBLISHED; nhập tồn không sửa nội dung hoặc visibility sản phẩm. Chưa làm reservation, điều chỉnh số lượng, xuất hàng, checkout hay storefront. Các AC đồng thời/FEFO của reservation sẽ được triển khai trong lát cắt sau, không coi M2c1 hoàn tất toàn UC-04.

Một lô có mã uppercase 1–40 theo mẫu SKU, duy nhất trong cùng SKU; số lượng nhập là 1–1.000.000 gói nguyên. Ngày thu hoạch/sản xuất `producedOn` tùy chọn; `expiresOn` bắt buộc, theo ngày ISO bốn chữ số năm. Ngày sản xuất không sau hôm nay hoặc sau hạn dùng. Lô mới phải có hạn dùng sau hôm nay. Đây là lựa chọn triển khai cho demo hàng thực phẩm, không chứng minh chất lượng/an toàn thực phẩm.

Ngày hiện tại dùng Asia/Ho_Chi_Minh; bắt đầu ngày expiresOn thì lô hết hạn. Stored state chỉ ACTIVE/BLOCKED; response tính BLOCKED trước, rồi EXPIRED nếu expiresOn <= hôm nay, rồi DEPLETED nếu available bằng 0, còn lại ACTIVE. Không phụ thuộc scheduler. available = onHand-held-allocated là tồn vật lý khả dụng, không khẳng định đủ điều kiện checkout. Chặn mở lại lô hết hạn; khóa lô không thay số lượng. Version lô độc lập với version sản phẩm, giới hạn số nguyên an toàn JavaScript.

Lot input không nhận actor/shop/onHand/held/allocated/status/version. Lưu các bộ đếm có constraint không âm và held+allocated <= onHand; M2c1 held/allocated luôn 0. Không có API sửa/xóa lô hoặc sửa ngày/mã/SKU sau nhập. Mã trùng trả409. FK cùng product/shop/SKU giữ lịch sử. Không thêm service, dependency hoặc broker.

## Contract và persistence

Canonical `contracts/openapi/catalog-inventory.v1.openapi.json` riêng, version1.0.0, ba thao tác:

- GET /api/v1/seller/products/{productId}/lots: paginated LotPage, page0..1000000/pageSize1..100, mặc định0/20; sắp xếp expiresOn ASC,id ASC ổn định.
- POST cùng path: {skuId,code,quantity,producedOn?,expiresOn}, trả201 InventoryLot/version0; Idempotency-Key UUID và CSRF.
- PUT /api/v1/seller/products/{productId}/lots/{lotId}/status: {status:ACTIVE|BLOCKED,expectedVersion}, trả200; cùng target với key mới409, stale409, mở lô hết hạn422.

Mọi request kiểm current session/ACTIVE owner qua Identity/Merchant gRPC trước read/replay. Cross-owner/product/lot/SKU trả404. Malformed/duplicate/trailing/empty JSON hoặc body trên2048bytes400; semantic/UUID/date/key/payload422; state/version/inflight409; dependency503. Response private/no-store và safe ApiError hiện có.

V4 thêm inventory_lots, inventory_requests và inventory_audit; không sửa migration cũ. Requests lưu actor/operation/key/hash/product/shop/result trong transaction, advisory try-lock và seven-day retention giống pattern hiện có. Replay là snapshot lịch sử của lần mutation; danh sách mới trả trạng thái theo thời gian hiện tại. Không dùng replay để quyết định phân bổ/xuất trong lát cắt tương lai.

Hash status bind cả productId và lotId cùng payload, như media bind imageId; key không được replay lô A qua URL lô B. Kiểm tra cấu trúc/date ISO trước claim, nhưng quy tắc phụ thuộc hôm nay, state và version phải sau replay lookup để retry qua mốc hết hạn trả snapshot cũ đúng ý định. API test dùng Clock cố định/có thể dịch ngày, không chờ nửa đêm thật. Frontend refresh GET sau mutation/replay trước hiển thị trạng thái hiện hành; BLOCKED có thể đã hết hạn nên không mở chỉ dựa vào label. Intent/key của409 in-flight được giữ qua reload khi payload không đổi.

Tạo lô và sửa sản phẩm dùng chung khóa product row trước kiểm SKU, tránh race giữa nhập lô và xóa SKU. Thay cơ chế DELETE tất cả SKU rồi INSERT bằng cập nhật tại chỗ theo ID/code, chỉ xóa ID không còn. Nếu SKU từng có lô, không xóa/đổi code hoặc thay netWeight/shippingWeight/dimensions/saleUnit; vẫn được đổi label/price. Unique positions cần chuyển vị trí tạm trong transaction hoặc constraint DEFERRABLE; giữ constraint0..9, kiểm reorder và thêm/xóa/reuse code. FK không cascade.

## Các nhịp thực hiện

1. Chốt plan/contract, review rủi ro ngày giờ, replay, FK và SKU reorder. Pin frontend contract và sinh type theo công cụ hiện có.
2. Domain RED/GREEN: input/date/status/expiry/count/version. PostgreSQL API tests RED trước endpoint; thêm V4 và application/adapters. Test owner/CSRF/current permission/replay, atomic audit/result rollback, code collision, migrationV3data và SQL quantity constraints.
3. Sửa SKU persistence và guard trong transaction trước khi mở nhập lô. Regression giữ ID và lot refs khi reorder/đổi giá/label, chặn xóa/physical changes; race tạo lô/xóa SKU chỉ một thao tác thắng. Cập nhật reset TRUNCATE của test hiện có cho bảng FK mới.
4. UI riêng /seller/products/{productId}/lots, link từ editor đã lưu kể cả PUBLISHED. Form nhập SKU/mã/ngày/số gói, danh sách ngày/trạng thái/counters, khóa/mở. Giữ intent trên lỗi chưa rõ; lỗi409 khóa/reload; focus/pageshow/visibility revalidation và late-result guard, không mất form đang nhập khi còn quyền.
5. Gate frontend unit/fixture/static/build, root Maven tuần tự, review source; live qua ba backend thật và disposable PostgreSQL; không cần tạo Cloudinary asset. Test expired projection bằng domain/fake clock hoặc seed đúng fixture, không chờ thời gian thực. Xác minh lịch sử ảnh/visibility và SKU cũ không regress.
6. Đồng bộ README/demo/architecture/data-model/HANDOFF, contract digest và proof; final diff/secret/link checks. Dừng app/fixture test đã tạo, không thay database native. Chỉ đóng M2c1 khi mọi gate và review đạt; reservation/storefront vẫn chưa triển khai.

## Review focus

- Không mất SKU ID hoặc FK lịch sử khi cập nhật/reorder. Test có lô vẫn đổi giá/label, reorder thành công, physical/delete409 và rollback toàn bộ.
- Product lock được lấy trước kiểm SKU trong create và replace; test race có đúng một kết quả hợp lệ, không lot orphan/500.
- Biên hết hạn theo giờ Việt Nam và replay cũ không trở thành tồn đủ điều kiện. Test ngày hiện tại/expiry và lô hết hạn khi scheduler chưa chạy.
- Current permission áp dụng trước replay; result/audit/lô cùng commit, test injected failure rollback và retry không nhập hai lần.
- UI tránh ghi trùng sau timeout, phản hồi muộn sau denial không khôi phục dữ liệu riêng và focus không xóa form nhập lô.
