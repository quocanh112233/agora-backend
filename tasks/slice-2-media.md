# M2b1 — Ảnh sản phẩm Cloudinary

Ngày 23/10/2026, tiếp sau M2a đã nghiệm thu 82 backend tests/55 frontend unit/76 fixture/4 live. Người dùng đã cấu hình ba biến Cloudinary trong ignored backend/.env, mode 600; không đọc/in giá trị ra chat/log. Chỉ làm ảnh cho product DRAFT của shop ACTIVE. Publish/ẩn sản phẩm và storefront là nhịp kế tiếp; không sửa contract Catalog M2a hoặc migration đã áp dụng.

## Luồng và giao diện

Trang riêng `/seller/products/{productId}/images`, link từ editor đã có product ID. Trang riêng giúp thao tác ảnh không làm mất nội dung form sản phẩm chưa lưu. Tối đa 5 ảnh gắn, JPEG/PNG tối đa 5 MiB, kích thước tối đa 20 triệu pixel. Không nhận SVG/GIF/WebP hoặc URL/public_id do client khai báo. Không thêm SDK frontend hoặc dependency image.

Upload và gắn là hai thao tác rõ ràng: chọn tệp → **Tải ảnh lên** → báo ảnh đã upload/chưa gắn → **Gắn ảnh**. Backend proxy tệp tới Cloudinary; secret/signature không ra trình duyệt. Gắn/gỡ tăng version product, không sửa SKU. Trang lấy productVersion mới từ response và yêu cầu tải lại khi 409. No automatic mutation retries; giữ key qua lỗi mạng/response sai. Xóa khỏi sản phẩm là detach; provider cleanup có retry, không xóa ảnh còn tham chiếu.

## Contract riêng

Canonical `contracts/openapi/catalog-media.v1.openapi.json`, không đổi `catalog.v1.openapi.json`.

- GET `/api/v1/seller/products/{productId}/images` → 200 ProductImages `{productId, productVersion, items:[Image]}`.
- POST `.../{productId}/image-uploads` multipart **chỉ một part file**, Idempotency-Key UUID → 201 Upload `{uploadId,productId,expiresAt}`. Không tự gắn vào product.
- POST `.../{productId}/images` JSON `{uploadId,expectedVersion}`, UUID key → 200 ProductImages.
- DELETE `.../{productId}/images/{imageId}` JSON `{expectedVersion}`, UUID key → 200 ProductImages.
- Image `{id,url,width,height,bytes,format}`: id là upload UUID, format jpg/png, url HTTPS res.cloudinary.com được dựng từ metadata server xác minh. Không trả secret, filename hoặc nội dung request.
- Mọi request phải gọi SellerAccess hiện tại (Identity+Merchant) trước đọc/write/replay, ownership 404, DRAFT guard; CSRF cho mutation. Private responses no-store; lỗi cùng dạng ApiError M2a, thêm413filequálớn/410uploadhếthiệulực/503providerkhôngxácđịnh.

## Persistence và external I/O

V2 Flyway thêm `catalog.image_uploads`, `catalog.product_images` và `catalog.media_requests`. Không sửa Product record, controller/persistence M2a hoặc V1. Dùng port `ImageStorage` với `upload(byte[] data,String mime,String publicId)`, `find(String publicId)` và `delete(String publicId)`; implementation là `CloudinaryImageStorage`. Exact return record thống nhất trước code: `ImageStorage.Stored(String publicId,long version,String format,long bytes,int width,int height,String url)`; find trả Optional<Stored>.

Upload intent lưu owner/shop/product/key/hash SHA-256 của tệp + MIME/resource trước network, reserved public_id `agora/products/{productUUID}/{uploadUUID}`, overwrite=false. Unique actor/key; cùng key khác file/product 422. PENDING/UPLOADED/DELETE_PENDING/DELETED; không lưu binary hoặc filename trong DB. Chỉ record mới được gọi upload; retry PENDING chỉ tra provider theo reserved ID, không upload lần hai mù khi timeout. Chưa tìm thấy →503giữýđịnh; UPLOADEDreplayUpload201, DELETED/expired410. Metadata đọc/xác minh từ provider bằng ID server cấp, đúng image/upload, jpg/png, bounds, URL cloud riêng. Không giữ DB transaction qua network.

Reserve giới hạn 20 upload chưa gắn của một actor để tránh orphan vô hạn; product row lock bảo vệ quota/concurrency. Sau 24 giờ không gắn thì hết hạn. Khi attach: lock product và upload, check current ownership/version, UPLOADED/chưa hết hạn/cùng product/shop, max5; INSERT relation và tăng product.version/audit/media key snapshot trong một transaction. Replay kiểm tra shop hiện tại/payload trước trả snapshot. Detach xóa relation + tăng version/audit/snapshot, chuyển expires_at sang now để worker xóa provider; không delete ngay trong transaction. Media key giữ ít nhất7ngày.

Cleanup scheduled mỗi60giây, initial60giây, claim tối đa20upload expired không còn relation bằng transaction/FOR UPDATE SKIP LOCKED và set DELETE_PENDING; network delete ngoài transaction; ok/not_found →DELETED, lỗi giữ DELETE_PENDING để retry. Attach không chấp nhận DELETE_PENDING/DELETED. Prefix validation trước mọi provider delete và mọi truy vấn đều scope resource; không có public endpoint trigger cleanup. Không delete bất kỳ asset Cloudinary cũ nào ngoài ID do task sinh.

## Provider và wiring

Main sở hữu `application/ImageStorage.java`, `adapter/outbound/CloudinaryImageStorage.java`, `configuration/ImageStorageConfiguration.java` và provider unit test. Dùng Java HttpClient/Jackson3 đã có, không thêm dependency. Fixed host HTTPS api.cloudinary.com, redirect NEVER, connect3s/request8s, bounded JSON64KiB. Cloudinary hiện hỗ trợ BasicAuth cho server-side Upload API; chọn cách này cho upload/destroy/Admin resource thay vì tự viết signature/timestamp không cần thiết. BasicAuth chỉ tới host HTTPS cố định, không trả header cho browser. Không log URI credential, key, secret, header/body hoặc exception message provider. Các biến trống →storage unavailable503 khi gọi, không fake thành công; app M2a/CI vẫn khởi động. Nguồn: https://cloudinary.com/documentation/authentication_signatures.

Backend agent sở hữu media domain/usecase/store/controller/input-validator/cleanup/V2 và tests, không sửa ba file provider của main. Frontend agent sở hữu media artifact/types/client/widget/route/rewrite/tests; không chạm package scripts/audit đang được agent riêng dọn. Backend ứng dụng cấu hình multipart 5MiB/6MiB bằng media configuration riêng; không thêm ảnh vào Product schema M2a.

## Kiểm chứng

TDD từng nhịp. Media API integration dùng real PostgreSQL + fake ImageStorage (không credential/network), caller RPC fixture cùng mẫu Catalog. Test upload chưa attach; JPEG/PNG/magic/bounds/sai multipart/quá5MiB; ownership/CSRF/nonACTIVE; key hash/replay/unknown retry không duplicate; attach5/quota/expired/version/SKU unchanged; rollback và cleanup không xóa attached/retry delete. Provider test transport giả: fixedhost/BasicAuth/overwrite=false/bodylimit/metadata/url/timeout/404/not_found và không lộ secret.

Sau module/root verify, restart chỉ Catalog từ JAR mới với datasource Docker, env Cloudinary thật đọc từ file ignored. Frontend full gates và live opt-in image suite với PNG tổng hợp nhỏ: upload thật → attach → reload → detach → kiểm chứng provider cleanup. Chỉ upload/delete asset namespace/ID mới của bài test, không đụng media cũ của tài khoản. Native PostgreSQL và dữ liệu thật giữ nguyên. Không commit/push/deploy.

## Kiểm chứng bổ sung ngày24/10

Review PNG metadata P1 đã được tái hiện: PNG nhỏ chứa32zTXt chunks làm validator cấp phát25,278,800bytes. Thay đổi chỉ bật ignoreMetadata và bỏ getImageMetadata không dùng; regression giữ mức cấp phát dưới2MiB, kèm JPEG dimensions. Focused hai tests đạt10:04:15; root verify116tests đạt10:05:50. Không suy ra provider đã xác minh ảnh chỉ từ header hợp lệ: bước Cloudinary metadata verification vẫn bắt buộc trước UPLOADED/attach.

Fixture23/10 không còn sau lần dừng;24/10 tạo Docker biệt lập mới, không dùng native DB. Tra đúng một ID cleanup artifact browser hôm trước qua Admin resource API đã xác nhận404; không quét media tài khoản. Frontend review phát hiện cần focus/pageshow/visibility revalidation sau logout tab khác; fix và gate/live mới đang thực hiện. Chỉ đóng lát cắt sau khi có đầy đủ proof ngày24/10.
