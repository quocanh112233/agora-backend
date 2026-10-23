# ADR-0002 — Tách Identity/Merchant và dùng PostgreSQL local

## Trạng thái

Accepted ngày 2026-10-21. Thay thế phần gộp Identity/Merchant và lựa chọn PostgreSQL local trong [ADR-0001](0001-foundation.md); các quyết định khác vẫn giữ.

## Bối cảnh

Người dùng ưu tiên portfolio microservice/DDD, không giới hạn thời gian, hỏi về ranh giới Identity/Merchant và yêu cầu bootstrap service đầu. Máy đã có Java 25, Docker/Compose và PostgreSQL 16.15 local; chưa có Maven. Database agora chưa tồn tại và thông tin PostgreSQL local người dùng cung cấp đã xác thực được.

## Quyết định

- Tách identity-service và merchant-service thành hai ứng dụng, nguồn dữ liệu độc lập. Đích có 6 service; chỉ bootstrap Identity trước.
- Identity sở hữu account, credential, session và quyền toàn sàn. Merchant sở hữu shop, owner, điểm lấy hàng và review, xác minh danh tính qua hợp đồng Identity thay vì đọc database Identity.
- Cài Maven 3.9.16 trong ~/.local/share/agora-tools, lệnh mvn ở ~/.local/bin. Gói từ Apache được kiểm tra SHA-512; Wrapper 3.9.16 ghim SHA-256.
- Tái sử dụng PostgreSQL 16 trên 127.0.0.1:5432, tạo agora owner postgres theo yêu cầu. Không đổi password role postgres, sửa database khác hoặc chạy thêm Postgres container.
- Agora dành cho Identity local; schema identity và lịch sử Flyway nằm trong đó. Merchant dùng database riêng sau này, dự kiến agora_merchant.
- Credential chỉ lưu backend/.env, quyền 600, bị Git ignore. .env.example không chứa password thực. Application không hardcode password.
- Quyền postgres superuser chỉ dùng local theo yêu cầu; deployment cần role tối thiểu và secret khác.

## Hệ quả

- Thêm một đơn vị deploy và hợp đồng xác thực liên service, đổi lại hai vòng đời nghiệp vụ phát triển độc lập.
- OpenAPI identity-merchant.v1.openapi.json vẫn là bundle onboarding; x-service xác định provider của mỗi operation, không có nghĩa một process.
- Bootstrap chỉ có application, package DDD, datasource/Flyway và health. Chưa triển khai đăng ký/login/session/gRPC hoặc Merchant.
- Integration smoke test hiện dùng database local trong .env, chưa dùng Testcontainers; CI cần môi trường test database riêng ở bước tiếp theo.

## Kiểm chứng

Maven/Wrapper chạy Java 25; verify đạt; test kết nối/migration; health HTTP UP; .env bị Git ignore và credential local không xuất hiện trong file có thể commit.
