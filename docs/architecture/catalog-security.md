# Catalog — Ranh giới bảo mật và dữ liệu

M2a quản lý sản phẩm/SKU theo [kế hoạch](../../tasks/slice-2-catalog.md) và [OpenAPI](../../contracts/openapi/catalog.v1.openapi.json). M2b1 bổ sung ảnh qua [contract riêng](../../contracts/openapi/catalog-media.v1.openapi.json), M2b2 bổ sung công bố/ẩn qua visibility endpoint; không thêm URL/public ID vào Product input. Chưa có endpoint mua hàng hoặc storefront. Kết quả backend và UI/live ghi riêng để phân biệt giới hạn bằng chứng.

## Danh tính và quyền shop

Trình duyệt gửi cookie AGORA_SESSION và masked CSRF qua Next rewrite cùng origin. Catalog gọi ResolveSession của Identity để xác minh phiên/account hiện tại; sau đó gọi ResolveActiveShop của Merchant với account ID từ Identity, không lấy shop ID/owner từ JSON hoặc X-User-Id.

Mỗi request seller, kể cả read và idempotency replay, phải đi qua hai kiểm tra này. Shop chưa duyệt/bị đình chỉ và account không còn hợp lệ phải bị từ chối. Không đọc session/merchant tables từ Catalog. Product của shop khác trả 404, không trả snapshot replay hoặc dữ liệu riêng.

Đây là hai lời gọi đồng bộ trước transaction Catalog, không phải transaction khóa xuyên service. Nếu shop đổi trạng thái sau lúc xác minh, thao tác đang chạy có thể hoàn tất; request tiếp theo phải đọc trạng thái mới. Visibility cũng xác minh quyền tại request, không cấp quyền checkout. Storefront/checkout về sau phải xác minh lại shop/product hiện tại thay vì chỉ tin status đã lưu.

## Transport và lỗi

Local Identity gRPC bind 127.0.0.1:9091, Merchant gRPC bind 127.0.0.1:9092. Caller dùng shared INTERSERVICE_TOKEN URL-safe 32–256 ký tự từ env; không trả token/session/CSRF vào response hoặc log. Listener giới hạn request, client có deadline 2 giây mỗi call, không tự retry. Lỗi phụ thuộc phải fail closed, không principal/shop dự phòng.

Plaintext loopback chỉ phục vụ local. Triển khai nhiều container/máy cần TLS/mTLS, policy và quản lý secret; không mở rộng bind rồi coi token là đủ. HTTP private responses no-store; frontend origin config chỉ ở server, không dùng NEXT_PUBLIC_ cho secret.

## Dữ liệu và transaction

Catalog sở hữu database agora_catalog, schema catalog. Tiền VND số nguyên, khối lượng gram/kích thước cm có giới hạn rõ trong contract. Create/replace không nhận status, ảnh hoặc trường sở hữu do client khai báo. Visibility endpoint riêng chỉ nhận PUBLISHED/HIDDEN cùng expectedVersion. Chuỗi bắt buộc không chỉ khoảng trắng; response validation phải dùng độ dài raw theo contract.

Ràng buộc PostgreSQL bảo vệ code SKU duy nhất trong shop và điều kiện số học. Version guard bảo vệ hai ý định cạnh tranh. Product, thay đổi SKU, audit và idempotency result cùng transaction; lỗi một SKU không được để lại product/audit nửa chừng.

Idempotency key UUID thuộc actor/operation và payload/resource đã băm, giữ 7 ngày. Cùng key/payload replay snapshot gốc sau xác minh quyền hiện tại; đổi payload trả 422, in-flight trả 409. Client giữ key cho đến khi nhận response thành công có cấu trúc hợp lệ, không tự retry mutation.

PUT nội dung là thay toàn bộ thông tin DRAFT hoặc HIDDEN và danh sách SKU; sửa HIDDEN giữ HIDDEN. Code giữ nguyên thì giữ ID; bỏ/đổi code có thể xóa/tạo SKU ở lát cắt chưa có inventory. Trước khi thêm lô phải chặn xóa/đổi SKU đang được tham chiếu; không dùng quy tắc chỉnh sửa hiện tại để xóa tồn hoặc lịch sử đơn về sau.

## Kiểm chứng và credential

Unit/integration tests dùng PostgreSQL Docker riêng, không reset database native. Live phải gọi ba app thật, đăng ký owner, mở shop và được ADMIN duyệt trước khi tạo sản phẩm; fixture browser phải gắn nhãn rõ. Synthetic account/ADMIN chỉ nằm trong test database. Không lưu trace/video/ảnh form có credential hoặc dump cookie.

Cloudinary đã cấu hình trong ignored backend/.env mode600; không log/in giá trị. Test media mặc định tắt env import và dùng storage giả. Real-provider probe chỉ tạo/xóa ảnh tổng hợp mới có reserved namespace; không quét hoặc xóa tài nguyên cũ của tài khoản.

## Ảnh M2b1

Mọi thao tác ảnh/replay xác minh phiên/CSRF/shop ACTIVE và product DRAFT/HIDDEN thuộc shop hiện tại; PUBLISHED phải ẩn trước khi quản lý ảnh. Multipart chỉ một part file, JPEG/PNG có magic/header phù hợp, tối đa5MiB/20triệupixel. ImageIO bỏ qua metadata tùy chọn và chỉ đọc kích thước; không giải nén/giữ compressed-text chunks để xác thực. Provider phải xác minh ảnh cuối cùng. Server dành trước public ID `agora/products/{productUUID}/{uploadUUID}`, overwrite=false. Provider phải xác nhận đúng ID/type/format/bytes/dimensions; URL dựng từ cloud/metadata server, không tin secure_url từ response. BasicAuth chỉ gửi tới HTTPS api.cloudinary.com cố định, không redirect; body tối đa64KiB, deadline8giây gồm cả body trên JDK25. Lỗi trả503 chung, không gắn exception provider vào response/log.

Intent commit trước network. Retry PENDING chỉ đọc metadata cùng ID; chưa thấy resource thì503, không tạo duplicate upload. Quota20upload chưa gắn theo actor được khóa xuyên các product. Gắn/gỡ dùng row lock/version và key snapshot7ngày; audit/relation/version/key commit cùng transaction, không thay SKU. Tối đa5ảnh gắn. Cleanup claim không còn relation và hết hạn trước I/O, chỉ xóa reserved ID; DELETE_PENDING không được attach, lỗi provider giữ để retry. Không có HTTP endpoint gọi cleanup hoặc nhận ID bất kỳ để xóa.

Root verify22:57 ngày23/10 đạt114tests (13Identity44Merchant57Catalog), gồm19mediaAPI PostgreSQL,2cleanupunit,11provider. Probe Cloudinary thật upload/xác minh/xóa đúng ảnh PNG mới đã đạt. UI/live media có bằng chứng riêng sau nghiệm thu; không suy ra từ mock provider tests.

Root verify24/10 lúc10:05:50 đạt116tests sau fix metadata PNG, thêm hai regression validator. Tệp PNG nhỏ chứa32zTXt chunks đã gây cấp phát25,278,800bytes trước sửa; cùng tệp qua validator sau sửa nằm dưới2MiB. Giới hạn byte/pixel vẫn giữ; không dùng thời gian chạy làm assertion chống giải nén metadata.

## Visibility M2b2

Body visibility tối đa1024bytes, đọc sau SellerAccess; parser từ chối duplicate/trailing JSON. Key bind actor/operation/shop/product/status/version. Publish chỉ nhận DRAFT/HIDDEN có dữ liệu bắt buộc không trắng,1–10SKU và relation tới ít nhất1uploadUPLOADED đúng product/shop. Product row được khóa trước đọc ảnh; attach/detach cũng khóa product, nên công bố và gỡ ảnh cuối không đồng thời vượt điều kiện. PUBLISHED chỉ đọc; hide không sửa SKU/ảnh, chỉnh sửa HIDDEN không tự công bố lại.

Status/version/audit/key snapshot cùng transaction; lỗi rollback toàn bộ, không giữ key nửa chừng. Guard version tối đa9_007_199_254_740_991 tránh phản hồi số nguyên không an toàn trong JavaScript. Frontend cần lưu form trước khi Công bố để không công bố snapshot giá/nội dung cũ; bảo vệ này không thay validation/ownership/version của backend.

Root verify24/10 lúc10:57:21 đạt133tests (13Identity44Merchant76Catalog), gồm6domain/11PostgreSQL API mới. MigrationV2 có dữ liệu lênV3 giữSKU ID/image relation/completed DRAFT snapshot. Replay sau thu hồi account/shop bị401/403; key đổi payload/resource/version bị422; race publish/detach có một winner, audit rollback và incomplete saved data đã kiểm tra. Review backend độc lập không có blocker; UI/live còn bằng chứng riêng.

## Bằng chứng backend ngày 23/10/2026

Root Maven verify 22:16 đạt 82 tests (13 Identity, 44 Merchant, 25 Catalog), zero fail/error/skip. Catalog API integration dùng PostgreSQL thật với RPC fixture; client/provider RPC có test riêng. Vì vậy main chạy thêm probe qua ba app HTTP/gRPC thật trên ba database Docker biệt lập để đo ranh giới tích hợp.

Probe đã chứng minh: owner khác read/update trả 404; anonymous 401, shop chưa ACTIVE 403, CSRF sai 403; create/PUT replay đúng snapshot và không tăng số bản ghi; SKU id ổn định; stale version 409, payload đổi 422, code SKU trùng 409. Product version/SKU/audit/key counts giữ nguyên khi thao tác bị từ chối. Sau khi suspend đúng shop/account fixture, read và replay lần lượt bị chặn 403/401 ngay; khôi phục fixture và đọc lại thành công.

Đây là bằng chứng local, không dùng PostgreSQL native và không chứng minh TLS/ingress production, Cloudinary upload hoặc luồng mua hàng. Bộ browser fixture/live UI được ghi trong proof frontend, không gộp vào các test backend trên.
