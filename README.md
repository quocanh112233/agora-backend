# Agora Backend

Backend cho sàn B2B2C nông sản và thủy hải sản, xây dựng làm portfolio cá nhân. Dự án dùng microservices và DDD, hỗ trợ người mua, chủ cửa hàng và quản trị viên.

## Chức năng

- Đăng ký BUYER và SELLER riêng, email duy nhất toàn hệ thống; đăng nhập bằng session, CSRF và quyền quản trị ADMIN.
- Tạo cửa hàng, gửi duyệt, phê duyệt hoặc từ chối hồ sơ.
- Admin tìm kiếm/lọc/phân trang tài khoản, xem chi tiết và cửa hàng liên kết.
- Quản lý sản phẩm, SKU đóng gói, ảnh Cloudinary và công bố sản phẩm.
- Quản lý lô hàng, hạn dùng, giữ tồn FEFO và thời hạn giữ hàng.
- Giỏ hàng, checkout từng cửa hàng và lưu snapshot đơn hàng.
- Tạo payment attempt VNPAY sandbox, khôi phục yêu cầu và xác minh Return.
- Đánh giá dòng đơn PAID, phản hồi của cửa hàng và kiểm duyệt đánh giá.

## Tài khoản mua và bán

Một account có một loại cố định: BUYER mua hàng/đánh giá đơn, SELLER mở shop/quản lý hàng/phản hồi đánh giá. Muốn vừa mua vừa bán phải đăng ký hai account bằng hai email khác nhau. Quyền USER/ADMIN độc lập với loại tài khoản; ADMIN không tự vượt kiểm tra loại hoặc quyền sở hữu ở API kinh doanh.

Đăng ký người mua: `POST /api/v1/auth/registrations`; người bán: `POST /api/v1/auth/seller-registrations`. Backend ấn định loại theo endpoint, không nhận loại/quyền do client tự cấp. Đăng ký không tự đăng nhập hoặc tạo shop. SELLER đăng nhập rồi gửi hồ sơ shop; admin duyệt shop mới được bán. Cửa hàng chỉ đủ điều kiện công khai/nhận giao dịch mới khi shop ACTIVE và chủ shop là SELLER ACTIVE.

Migration V4 bảo toàn account cũ với `account_type` chưa phân loại; account này chưa được đăng nhập. Sau backup và kiểm kê ownership ở các service, operator dùng [classify-legacy-account.sql](scripts/classify-legacy-account.sql) trên đúng database Identity, cung cấp UUID/loại/version đã xác minh. Script chỉ phân loại account chưa có loại và thu hồi phiên; không chuyển đổi BUYER thành SELLER. Account cũ có cả lịch sử mua và shop cần kế hoạch tách dữ liệu riêng, không reset volume hoặc sao chép mật khẩu. Khi nâng cấp, triển khai đồng bộ các service/client và đăng nhập lại.

Không có tài khoản hoặc mật khẩu ADMIN mặc định. [grant-local-admin.sql](scripts/grant-local-admin.sql) chỉ cấp quyền thủ công cho account ACTIVE được chọn trên đúng môi trường. Admin có thể tìm/lọc tài khoản, xem chi tiết và shop liên kết, khóa/mở tài khoản với lý do và xem lịch sử. Đổi trạng thái dùng version CAS, ghi audit và xóa phiên trong cùng transaction; phiên cũ không dùng lại được sau mở khóa. Mọi tài khoản ADMIN và tự khóa đều được bảo vệ. Shop đã duyệt có thể đình chỉ/mở lại độc lập; mở lại yêu cầu chủ shop là SELLER ACTIVE. Không tự hủy đơn hoặc hoàn tiền khi khóa. Migration V5 thêm audit Identity, Merchant V3 bổ sung metadata audit cho status; các migration cũ giữ nguyên. Session thiếu version stamp sau rollout phải đăng nhập lại. RPC ResolveSession chỉ bổ sung display_name để ghi audit Merchant, không dùng tên để phân quyền.

## Công nghệ và service

Java 25 · Spring Boot 4.1.1 · Maven Wrapper 3.9.16 · PostgreSQL 16 · Flyway · gRPC · Docker · GitHub Actions.

| Service | Trách nhiệm | HTTP | gRPC | Database |
| --- | --- | --- | --- | --- |
| Identity | Tài khoản, session, xác thực | 8081 | 9091 | agora |
| Merchant | Cửa hàng và xét duyệt | 8082 | 9092 | agora_merchant |
| Catalog | Sản phẩm, ảnh, lô và tồn kho | 8083 | 9093 | agora_catalog |
| Commerce | Giỏ hàng, checkout, đơn hàng | 8084 | 9094 | agora_commerce |
| Payment | VNPAY sandbox và payment attempts | 8085 | — | agora_payment |
| Feedback | Đánh giá, phản hồi, kiểm duyệt | 8086 | — | agora_feedback |

Mỗi service có database/schema riêng. Giao tiếp nội bộ dùng gRPC và token; trình duyệt gọi REST qua frontend. Toàn bộ dữ liệu ứng dụng dùng PostgreSQL, ảnh lưu tại Cloudinary.

## Chạy demo bằng Docker

Cần Docker Engine, Docker Compose và Python 3. Đặt hai repository cạnh nhau:

```text
agora/
├── backend/
└── frontend/
```

Từ thư mục `backend/`:

```bash
python3 scripts/init-demo-env.py
docker compose --env-file .env.docker up --build -d --wait --wait-timeout 180
```

Mở **http://127.0.0.1:3000**. Compose build cả frontend và sáu backend; không cần cài Java/Node trên máy để chạy demo. Chỉ cổng 3000 được publish vào loopback. Backend, gRPC và PostgreSQL không publish ra laptop.

Helper tạo `.env.docker` với password/token ngẫu nhiên, quyền `600`; chạy lại không ghi đè. PostgreSQL Docker dùng named volume `agora-demo_postgres-data`, độc lập với database native. Giữ file credential này để dùng lại volume.

Dừng hoặc tạo lại container mà vẫn giữ dữ liệu:

```bash
docker compose --env-file .env.docker stop
docker compose --env-file .env.docker up -d --wait --wait-timeout 180
# Xóa container/network, vẫn giữ named volume:
docker compose --env-file .env.docker down
```

Không dùng `down -v` hoặc prune volumes nếu muốn giữ dữ liệu. Các app dùng chung network namespace để giữ gRPC loopback hiện có; cấu hình này dành cho demo một máy.

## Cloudinary và VNPAY

Tham khảo [.env.example](.env.example), điền giá trị thực vào `.env` bị Git ignore:

| Tích hợp | Biến môi trường |
| --- | --- |
| Cloudinary | CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET |
| VNPAY sandbox | VNPAY_ENABLED, VNPAY_TMN_CODE, VNPAY_HASH_SECRET, VNPAY_RETURN_URL |

Nạp cấu hình provider vào runtime, vẫn giữ credential database demo riêng:

```bash
docker compose --env-file .env --env-file .env.docker up --build -d --wait --wait-timeout 180
```

Mặc định VNPAY chưa bật. Demo giữ gateway sandbox và Return có kiểm chữ ký; **IPN/querydr bị tắt và nằm ngoài phạm vi**. Return không tự xác nhận PAID, nên hiện chưa có luồng tự động chuyển đơn sandbox thật sang PAID. Review chỉ nhận dòng đơn PAID do Commerce xác nhận. Không dùng thanh toán hoặc PAID giả.

Không có giao hàng, GHN hoặc nhận hàng tại cửa hàng trong phạm vi hiện tại.

## Tài khoản quản trị

Đăng ký account qua giao diện trước. Để cấp ADMIN cho đúng account demo đã chọn, từ `backend/` chạy thủ công:

```bash
docker compose --env-file .env.docker exec -T postgres \
  psql -U postgres -d agora -v admin_email='admin@example.test' \
  < scripts/grant-local-admin.sql
```

Thay email bằng account của mình. Script chỉ cấp quyền cho account ACTIVE đã tồn tại; không có account/password ADMIN mặc định.

## Kiểm thử và CI

Chạy tests cần Java 25 và Docker; integration tests dùng PostgreSQL Testcontainers riêng:

```bash
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify
```

Smoke qua stack demo mới; fixture tổng hợp được giữ trong `target/` để kiểm tra persistence:

```bash
python3 scripts/smoke-containers.py
# Sau down/up cùng volume:
python3 scripts/smoke-containers.py --reuse
```

CI chạy Maven verify rồi build image của cả sáu service, không publish hoặc deploy. Tests và smoke không được trỏ vào database native đang dùng thật.

## Cấu trúc

```text
services/     # Sáu ứng dụng Spring Boot
libraries/    # Các module gRPC contract dùng chung
contracts/    # OpenAPI và Protocol Buffers
docker/       # Khởi tạo PostgreSQL demo
scripts/      # Credential demo, smoke và cấp ADMIN thủ công
```

`docs/` và `tasks/` được giữ local, không theo dõi trong Git. README và các contract chứa thông tin cần thiết cho người clone repository.
