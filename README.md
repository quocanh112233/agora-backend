# Agora Backend

Backend marketplace B2B2C nông sản và thủy hải sản cho portfolio cá nhân. Định hướng microservice/DDD, Java Spring Boot, PostgreSQL, MongoDB, gRPC và các tích hợp sandbox.

## Hiện trạng

Identity chạy Java 25, Spring Boot 4.1.1, Maven Wrapper 3.9.16, JDBC/Flyway. Đã có đăng ký USER, session login/logout, `/me`, CSRF, Argon2id và gRPC xác thực nội bộ. Merchant là ứng dụng/database riêng: tạo/sửa shop nháp, gửi duyệt, admin liệt kê/phê duyệt/từ chối và sửa hồ sơ bị từ chối để gửi lại. Cả 11 thao tác trong OpenAPI đã có implementation; Catalog/Payment/Feedback chưa triển khai.

Máy local dùng PostgreSQL 16 tại 127.0.0.1:5432: `agora` cho Identity, `agora_merchant` cho Merchant. Không cần thêm container cho dữ liệu local. Integration tests dùng PostgreSQL Docker biệt lập, không reset/truncate hai database local. Maven đã cài trong thư mục người dùng; ưu tiên wrapper trong repository.

## Chạy local

Chạy các lệnh từ thư mục backend. .env local đã có và bị Git ignore; khi clone mới chỉ copy .env.example nếu chưa có .env, rồi đặt credential của mình. Không commit .env.

```bash
# Chỉ cần cho lần clone mới; không ghi đè .env hiện có
test -f .env || cp .env.example .env
chmod 600 .env

# Kiểm chứng toàn bộ reactor bằng PostgreSQL Testcontainers
# Image postgres:16 đã có sẵn trên máy này (PostgreSQL 16.15).
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify

# Cài contract vào Maven local trước khi chạy service độc lập
./mvnw -B -ntp -pl libraries/identity-contract -am install

# Chạy Identity với PostgreSQL local và .env
./mvnw -pl services/identity-service spring-boot:run

# Terminal khác, cũng từ backend; Identity phải bật gRPC
./mvnw -pl services/merchant-service spring-boot:run
```

Service đọc .env tự động từ cwd backend; không cần source password vào shell. Health: Identity http://127.0.0.1:8081/actuator/health, Merchant http://127.0.0.1:8082/actuator/health. Ctrl+C để dừng service. Identity dùng `SERVER_PORT`, Merchant dùng `MERCHANT_SERVER_PORT` để tránh hai app cùng cổng. Khi đổi thư mục chạy jar, bảo đảm .env được đọc từ đúng cwd.

V1–V3 đã tạo schema Identity, account/role và Spring Session JDBC. Merchant có V1 shop/audit/idempotency và V2 review; Flyway áp dụng vào `agora_merchant` khi app chạy. Migration đã áp dụng không được sửa; thay đổi tiếp bằng migration mới. Credential `postgres` hiện chỉ dành cho local theo yêu cầu, không dùng cho deployment.

Xem [hướng dẫn demo mở shop](docs/demo-onboarding.md) để chuẩn bị ADMIN có kiểm soát và chạy hai luồng nghiệm thu.

## Session và gRPC local

Browser gọi HTTP cùng origin qua Next rewrites. Cookie `AGORA_SESSION` có HttpOnly/SameSite=Lax/Path=/, idle 30 phút. Lấy `/api/v1/auth/csrf` trước mutation và lấy lại sau login/logout. Đăng ký không tự đăng nhập và không nhận role do client khai báo. Không lưu phiên vào localStorage/Zustand persist.

`IDENTITY_GRPC_ENABLED=true` bật listener `127.0.0.1:9091`; Merchant kiểm tra phiên qua [SessionIdentity v1](contracts/proto/agora/identity/v1/session_identity.proto). Cả hai app dùng cùng `INTERSERVICE_TOKEN` ngẫu nhiên 32–256 ký tự URL-safe từ .env. File local hiện đã có token ngẫu nhiên; `.env.example` để trống, không có credential mặc định. Không in hoặc commit token.

gRPC plaintext chỉ dành cho loopback local. Trước khi triển khai các app qua network/container riêng phải có TLS/mTLS và policy nội bộ; không chỉ mở rộng bind. HTTPS phải bật `SESSION_COOKIE_SECURE=true`. Rate limit bộ nhớ chỉ phù hợp một instance; Next proxy local có thể khiến nhiều người dùng dùng chung quota IP. Xem [ghi chú bảo mật](docs/architecture/identity-security.md).

## Kiểm chứng ngày 23/10/2026

Root `./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify` đạt 46 tests (13 Identity, 33 Merchant), không fail/error/skip. Có HTTP auth, email unique khi cạnh tranh, CSRF, logout, account bị khóa; gRPC kiểm tra caller, quyền mới nhất, anonymous/expired/suspended và idle timeout. Regression test chứng minh request gRPC cũ không đảo ngược lần login đổi session ID.

Merchant kiểm tra ownership, version, idempotency/replay/rollback, hai admin cạnh tranh và nâng schema V1 có dữ liệu lên V2. Probe HTTP qua hai service thật với PostgreSQL Docker riêng xác nhận cấp/thu hồi ADMIN có hiệu lực ngay; ADMIN đã bị thu hồi không thể đọc danh sách hoặc replay review đã thành công. Probe không cấp quyền cho tài khoản trong PostgreSQL native.

Kết quả giao diện và browser fixture/live nằm ở README repository frontend. Workflow [Backend CI](.github/workflows/ci.yml) dùng cùng lệnh verify đã chạy local, action ghim SHA và quyền chỉ đọc. Chưa có GitHub Actions run thực tế; chưa deploy hoặc tích hợp nhà cung cấp. Hướng dẫn demo ghi rõ cách ghép revision hai repo và phần live CI còn thiếu thông tin.

## Bắt đầu đọc

1. [Bản đồ nghiệp vụ](docs/product/capability-map.md)
2. [Use case và tiêu chí nghiệm thu](docs/product/use-cases.md)
3. [Trạng thái nghiệp vụ](docs/product/state-machines.md)
4. [Kiến trúc hệ thống](docs/architecture/architecture-overview.md)
5. [Cấu trúc thư mục](docs/architecture/project-structure.md)
6. [Mô hình dữ liệu](docs/architecture/data-model.md)
7. [Quy ước API](docs/architecture/api-conventions.md) và [OpenAPI](contracts/openapi/identity-merchant.v1.openapi.json)
8. [ADR nền tảng](docs/decisions/0001-foundation.md)
   và [ADR tách Identity/Merchant](docs/decisions/0002-separate-identity-merchant.md)
9. [Kế hoạch triển khai](tasks/plan.md)

Luồng đầu tiên: đăng ký, session login/logout, tạo/gửi duyệt shop và admin review. Danh sách task canonical ở claude-mem list `slice-1-onboarding`; file plan là chỉ mục, không phải checklist thứ hai.

Repo frontend là repository riêng; contract backend là nguồn canonical. Chưa có deployment hoặc credential nhà cung cấp trong repo.
