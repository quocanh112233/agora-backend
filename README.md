# Agora Backend

Backend của sàn B2B2C nông sản và thủy hải sản cho portfolio cá nhân. Repository này chứa bảy microservice Java, các contract và cấu hình PostgreSQL. Có thể build, chạy và kiểm tra API mà không cần repository frontend.

## Công nghệ

Java25 · Spring Boot4.1.1 · Maven Wrapper3.9.16 · PostgreSQL16 · Flyway · gRPC · Docker Compose · GitHub Actions. Mỗi service sở hữu database/schema riêng; không truy vấn bảng xuyên service. Ảnh sản phẩm dùng Cloudinary.

## Service và cổng

| Service | HTTP local | gRPC server local | Database | Schema |
| --- | --- | --- | --- | --- |
| Identity | 127.0.0.1:8081 | 127.0.0.1:9091 | agora | identity |
| Merchant | 127.0.0.1:8082 | 127.0.0.1:9092 | agora_merchant | merchant |
| Catalog | 127.0.0.1:8083 | 127.0.0.1:9093 | agora_catalog | catalog |
| Commerce | 127.0.0.1:8084 | 127.0.0.1:9094 | agora_commerce | commerce |
| Payment | 127.0.0.1:8085 | Không mở gRPC server | agora_payment | payment |
| Feedback | 127.0.0.1:8086 | Không mở gRPC server | agora_feedback | feedback |
| Email | 127.0.0.1:8087 | 127.0.0.1:9095 | agora_email | email |

Payment và Feedback là gRPC client khi gọi service khác; không có listener gRPC nên không cần cấp port server. PostgreSQL native mặc định127.0.0.1:5432. Mailpit thử nghiệm dùng SMTP127.0.0.1:1025 trong network Docker và hộp thư HTTP127.0.0.1:8025 trên laptop. SMTP provider thật dùng cổng do nhà cung cấp quy định, thường587 hoặc465.

Các cổng8081–8087/9091–9095 ở bảng là listener của service khi chạy native hoặc bên trong network namespace Docker. Compose hiện **không publish** PostgreSQL/backend/gRPC ra host. Health endpoint của mỗi service là `GET /actuator/health` trên HTTP port tương ứng.

## Chức năng

- Đăng ký BUYER/SELLER riêng, email duy nhất toàn hệ thống; xác nhận email bắt buộc trước đăng nhập.
- Session cookie HttpOnly, CSRF, Argon2id; quên/đổi mật khẩu bằng link dùng một lần, thu hồi phiên sau thay đổi.
- Tạo shop, gửi duyệt, phê duyệt/từ chối; admin khóa/mở account và đình chỉ/mở shop với version CAS, lý do và audit.
- Sản phẩm/SKU đóng gói, ảnh Cloudinary, công bố sản phẩm, lô/hạn dùng, giữ tồn FEFO.
- Giỏ hàng, checkout từng shop, snapshot đơn hàng, payment attempt VNPAY sandbox.
- Review dòng đơn PAID, phản hồi seller và kiểm duyệt admin.

Một account có loại BUYER hoặc SELLER cố định. Muốn vừa mua vừa bán cần hai tài khoản với hai email riêng. USER/ADMIN là quyền độc lập; ADMIN vẫn phải qua kiểm tra loại/ownership tại API kinh doanh. Không có account/password ADMIN mặc định.

## Cấu hình local

Từ thư mục repository backend:

```bash
cp .env.example .env
chmod 600 .env
```

Chỉ copy nếu chưa có `.env`; giữ giá trị đang dùng. Điền DB_HOST, DB_PORT, DB_USERNAME, DB_PASSWORD; INTERSERVICE_TOKEN dùng chung giữa các service. EMAIL_TOKEN_ENCRYPTION_KEY là32byte random mã hóa Base64url, giữ bền vững cùng outbox. Không commit `.env`, `.env.docker`, backup hoặc secret provider.

| Nhóm | Biến |
| --- | --- |
| Database | DB_NAME, MERCHANT_DB_NAME, CATALOG_DB_NAME, COMMERCE_DB_NAME, PAYMENT_DB_NAME, FEEDBACK_DB_NAME, EMAIL_DB_NAME |
| SMTP | SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, SMTP_FROM, SMTP_AUTH, SMTP_STARTTLS, SMTP_SSL |
| Link email | PUBLIC_WEB_ORIGIN, EMAIL_TOKEN_ENCRYPTION_KEY |
| Cloudinary | CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET |
| VNPAY sandbox | VNPAY_ENABLED, VNPAY_TMN_CODE, VNPAY_HASH_SECRET, VNPAY_RETURN_URL |

SMTP587 thường dùng AUTH=true/STARTTLS=true/SSL=false; SMTP465 thường dùng AUTH=true/STARTTLS=false/SSL=true. Remote SMTP cần TLS. PUBLIC_WEB_ORIGIN là origin website để người nhận mở link, HTTPS ngoài loopback, không có path/query/fragment. SMTP secrets chỉ nằm ở backend.

## Chạy native

Cần JDK25, PostgreSQL16 và Maven Wrapper trong repo. Tạo đủ database ở bảng trước khi chạy. Với cluster **mới hoàn toàn**, [docker/init.sql](docker/init.sql) chứa các lệnh CREATE DATABASE; không chạy nguyên file vào cluster đã có database.

```bash
./mvnw -B -ntp -DskipTests package
```

Mở terminal riêng cho từng service, cùng working directory là repository backend:

```bash
IDENTITY_GRPC_ENABLED=true EMAIL_DELIVERY_ENABLED=true java -jar services/identity-service/target/identity-service-*.jar
EMAIL_GRPC_ENABLED=true java -jar services/email-service/target/email-service-*.jar
MERCHANT_GRPC_ENABLED=true java -jar services/merchant-service/target/merchant-service-*.jar
CATALOG_GRPC_ENABLED=true java -jar services/catalog-service/target/catalog-service-*.jar
COMMERCE_GRPC_ENABLED=true java -jar services/commerce-service/target/commerce-service-*.jar
java -jar services/payment-service/target/payment-service-*.jar
java -jar services/feedback-service/target/feedback-service-*.jar
```

Hoàn tất cấu hình SMTP trước khi chạy Email; các service đọc `.env` từ working directory. Flyway validate và áp dụng migration còn thiếu khi service khởi động. Không dùng Flyway clean hoặc tự sửa checksum migration cũ.

Kiểm tra HTTP trực tiếp, ví dụ:

```bash
curl --fail http://127.0.0.1:8081/actuator/health
curl --fail http://127.0.0.1:8087/actuator/health
```

## Chạy backend bằng Docker

Cần Docker Engine, Compose và Python3. Lệnh sau chỉ build/chạy backend cùng Mailpit local, không build hoặc chạy frontend:

```bash
python3 scripts/init-demo-env.py
docker compose --env-file .env.docker -f compose.yaml -f compose.email-test.yaml \
  up --build -d --wait --wait-timeout 180 identity merchant catalog commerce payment feedback email mailpit
```

Helper chỉ thêm credential/key còn thiếu vào `.env.docker` mode600. PostgreSQL dùng named volume `agora-demo_postgres-data`; giữ credential/key để dùng lại volume. Mailpit giữ email thử tại **http://127.0.0.1:8025**, không gửi thư ra Internet. Override thử tăng auth budget lên1000attempt/15phút cho fixture; cấu hình thường10attempt/15phút.

Do HTTP backend không publish ra host, kiểm API bên trong container:

```bash
docker compose --env-file .env.docker exec -T identity curl --fail --silent http://127.0.0.1:8081/actuator/health
```

Compose base có khai báo tùy chọn tích hợp frontend và port3000; các lệnh chọn service ở đây không khởi động frontend, nên không có giao diện ở port3000. Các app dùng chung network namespace để giữ gRPC loopback; đây là cấu hình demo một máy.

Để dùng SMTP/provider đã cấu hình thật, nạp `.env` trước `.env.docker`, bỏ override Mailpit:

```bash
docker compose --env-file .env --env-file .env.docker \
  up --build -d --wait --wait-timeout 180 identity merchant catalog commerce payment feedback email
```

Dừng stack thử và giữ dữ liệu:

```bash
docker compose --env-file .env.docker -f compose.yaml -f compose.email-test.yaml down
```

Không thêm `-v` hoặc prune volumes. Volume mới init đủ bảy database; volume cũ không chạy lại init.sql, cần tạo riêng database còn thiếu trước khi service chạy Flyway.

## Migration và dữ liệu cũ

Backup trước khi nâng schema; xác nhận đúng database/volume rồi để Flyway validate và migrate. Bản hiện tại: IdentityV6, MerchantV3, CatalogV8, CommerceV3, PaymentV2, FeedbackV2, EmailV1. Migration không tự tạo database PostgreSQL.

V4 giữ account cũ có account_type NULL để operator phân loại sau kiểm kê ownership; dùng [classify-legacy-account.sql](scripts/classify-legacy-account.sql) với UUID/loại/version đã xác minh. Không suy loại từ role hoặc tự tách dữ liệu mixed BUYER/SELLER. V6 giữ email_verified_at NULL; cần gửi lại link và xác nhận, không backfill verified. Account chưa phân loại/chưa xác nhận chưa đăng nhập được. Session cũ thiếu version stamp phải đăng nhập lại.

[grant-local-admin.sql](scripts/grant-local-admin.sql) chỉ cấp ADMIN thủ công cho account ACTIVE đã phân loại và xác nhận email trên đúng database; không tạo password hoặc account mặc định. Account/shop moderation ghi audit và CAS; khóa account thu hồi phiên, mở lại không phục hồi phiên hoặc tự mở shop bị đình chỉ. Không tự hủy đơn/hoàn tiền khi khóa.

## Hợp đồng API và email

[contracts/openapi/](contracts/openapi/) chứa REST contract; [contracts/proto/](contracts/proto/) chứa gRPC contract. Browser/client cần lấy CSRF tại `GET /api/v1/auth/csrf`, giữ cookie và gửi header CSRF cho mutation. Backend quyết định quyền, giá và trạng thái giao dịch.

Sáu POST email cần CSRF:

| Endpoint dưới /api/v1 | Mục đích |
| --- | --- |
| /auth/email-verifications | Gửi lại link xác nhận |
| /auth/email-verifications/confirm | Xác nhận email |
| /auth/password-resets | Yêu cầu khôi phục mật khẩu |
| /auth/password-resets/confirm | Đặt mật khẩu mới bằng link |
| /me/password-changes | Yêu cầu đổi với phiên và mật khẩu hiện tại |
| /auth/password-changes/confirm | Áp dụng mật khẩu đã yêu cầu |

Verify link24giờ; reset/change30phút; cooldown60giây. Public resend/reset trả202 chung, không lộ email tồn tại. Mật khẩu12–128 Unicode code points, không toàn khoảng trắng. Mở link không xác nhận bằng GET; cần POST. Reset/change thành công thu hồi mọi phiên, không tự login.

Identity giữ hash token và outbox mã hóa; Email gửi template qua gRPC nội bộ. SMTP lỗi được retry. SMTP đã nhận nhưng ACK/commit mất có thể gửi lặp; link vẫn dùng một lần. Ciphertext/pending hash được clear khi gửi/consume/expiry; expired metadata được purge sau7ngày.

## Kiểm thử và CI

```bash
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify
```

Integration tests dùng PostgreSQL Testcontainers riêng, Email tests dùng SMTP sink. Không trỏ tests vào database đang dùng. CI verify và build image của bảy backend, không publish/deploy.

`scripts/smoke-containers.py` là kiểm thử tích hợp qua web proxy cho stack đầy đủ, không phải yêu cầu chạy backend độc lập. Nó đọc link từ Mailpit local, giữ fixture credential trong target; nếu fixture cũ đã có dùng --reuse, không ghi đè. Không SQL bypass email verification.

## Giới hạn và cấu trúc

VNPAY dùng sandbox/credential thật và verified Return; **IPN/querydr ngoài phạm vi**. Return không tự xác nhận PAID, chưa có luồng tự động chuyển đơn sandbox sang PAID. Review chỉ mở cho dòng đơn PAID do Commerce xác nhận. Không fakePAID, giao hàng, GHN, nhận tại shop hoặc MongoDB.

```text
services/     # Bảy Spring Boot service
libraries/    # Java gRPC contract modules
contracts/    # OpenAPI và protobuf
scripts/      # Env demo, smoke, operator SQL
docker/       # Init database cho volume mới
```

`docs/` và `tasks/` là tài liệu local bị Git ignore. README và contract trong Git đủ để người clone chạy backend; không chứa hướng dẫn cài đặt/build frontend.
