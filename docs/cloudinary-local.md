# Cấu hình Cloudinary local

Người dùng đã cấu hình tài khoản Cloudinary trong ignored `backend/.env`. M2a sản phẩm nháp/SKU chạy độc lập với Cloudinary. M2b1 dùng Java HttpClient, không thêm SDK: server giữ API key/secret và xác thực Basic Auth tới HTTPS `api.cloudinary.com` cố định.

Mở trang API Keys trong Cloudinary Console, lấy cloud name, API key và API secret của môi trường dành cho dự án. Điền trực tiếp vào `backend/.env` đang bị Git ignore, giữ nguyên cấu hình database/token đã có:

```dotenv
CLOUDINARY_CLOUD_NAME=
CLOUDINARY_API_KEY=
CLOUDINARY_API_SECRET=
```

Đây là tên biến Agora ánh xạ vào adapter Java. Giữ `.env` quyền 600. Không gửi giá trị qua chat, ảnh chụp, log, `.env.example` hoặc `NEXT_PUBLIC_*`. Cả ba biến trống cho phép app khởi động nhưng thao tác ảnh trả 503; cấu hình thiếu một phần hoặc cloud name sai làm app từ chối khởi động.

Luồng ảnh kiểm tra phiên/shop/chủ sản phẩm, JPEG/PNG tối đa 5 MiB và 20 triệu pixel, tối đa năm ảnh gắn. Public ID do server cấp theo `agora/products/{productUUID}/{uploadUUID}`; không nhận URL hoặc public ID của client. Upload không ghi đè; adapter đọc lại metadata provider và dựng URL từ metadata đã xác minh. Phản hồi provider tối đa 64 KiB, deadline tám giây gồm cả body, không theo redirect. Dọn ảnh chỉ xử lý ID đã được ứng dụng dành riêng và không còn gắn vào sản phẩm.

Ngày 23/10/2026 khoảng 22:48 và 22:50, probe thật đã tạo PNG 2×3 pixel mới, upload/xác minh metadata, xóa đúng ID đó và xác nhận resource không còn tồn tại. Lần thứ hai dùng adapter đã có deadline toàn bộ body. Kết quả này xác nhận credential/adapter; nghiệm thu toàn bộ API/giao diện được ghi riêng sau khi chạy. Test mặc định dùng storage giả, không phụ thuộc tài khoản Cloudinary.

Ngày24/10 sau fix validator PNG và frontend revalidation, live browser đã tải ảnh thật, gắn/reload/gỡ/reload, giữ SKU và tăng product version2. Worker ghi đúng upload DELETED, relation0; main tra độc lập đúng reserved ID của artifact và xác nhận provider không còn resource. Một ID artifact browser23/10 cũng đã được tra lại và xác nhận không còn. Không dùng broad listing hoặc xóa tài nguyên ngoài bài test.

Nguồn: [Cloudinary server authentication](https://cloudinary.com/documentation/authentication_signatures), [API Keys trong Console](https://console.cloudinary.com/settings/api-keys), [kế hoạch ảnh M2b1](../tasks/slice-2-media.md).
