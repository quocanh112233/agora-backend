# ADR-0001 — Nền tảng và luồng mở shop đầu tiên

## Trạng thái

Accepted cho thiết kế MVP theo quyền tự quyết người dùng giao ngày 2026-10-21. Đây không phải xác nhận đã cài dependency hoặc chạy ứng dụng. Khi thay đổi lớn, thêm ADR thay thế và giữ lịch sử.

Cập nhật: phần gộp Identity/Merchant và PostgreSQL local được thay thế bởi [ADR-0002](0002-separate-identity-merchant.md). Nội dung dưới đây giữ lại như lịch sử quyết định ban đầu.

Cập nhật 23/10/2026: lựa chọn MongoDB cho Feedback được thay thế bởi [ADR-0003 — chỉ dùng PostgreSQL](0003-postgresql-only.md). Nội dung quyết định ban đầu bên dưới không phải cấu hình persistence hiện hành.

## Bối cảnh

Solo portfolio, hai repo backend/frontend đã có, nghiệp vụ marketplace nông sản/thủy hải sản. Cần thể hiện microservice/DDD nhưng vẫn hoàn thành được luồng giao dịch. Mục tiêu gần nhất là đăng ký, login, tạo/gửi duyệt shop và admin duyệt.

## Quyết định

1. Giữ hai repo; backend Maven multi-module, mỗi ứng dụng là module deploy độc lập. Không import service JAR qua nhau.
2. Java 25, Spring Boot 4.1.1 làm mốc thiết kế đã kiểm tra tương thích; dùng dependency BOM. Maven Wrapper 3.9.x, patch/checksum ghim khi bootstrap; không kéo snapshot.
3. Frontend Next.js 16 App Router, TypeScript, npm và package-lock; chọn patch ổn định phù hợp khi scaffold, React theo Next. Zustand chỉ giữ state UI.
4. Domain thuần Java, application điều phối, adapter tích hợp; package-by-context. Không thêm CQRS framework/event sourcing hoặc common-domain library.
5. PostgreSQL 18 cho transactional data, database/user theo app. MongoDB cho Feedback và Cloudinary giữ như yêu cầu; đưa vào khi tính năng cần.
6. Session JDBC + HttpOnly cookie, CSRF bật cho browser, Argon2id; không tự xây OAuth/JWT/refresh-token cho bước đầu. Cần RPC xác thực tập trung khi thêm service khác, chấp nhận chi phí/khả dụng tương ứng.
7. HTTP/JSON cho web; gRPC cho interservice đồng bộ. Chọn RabbitMQ + outbox/dedup cho phần bất đồng bộ sau này, không cần bật broker trong luồng mở shop.
8. 5 service theo bản đồ hiện có, nhưng bootstrap một service đầu. GHN/VNPAY chỉ sandbox, chi trả seller mô phỏng.
9. Mặc định giữ hàng 15 phút, khiếu nại 24 giờ được dùng cho thiết kế/demo; tách thành cấu hình nghiệp vụ khi triển khai. Giữ SKU đóng gói cố định, một shop/checkout, một kiện/đơn.
10. Danh sách task canonical ở claude-mem list slice-1-onboarding. tasks/plan.md chỉ là chỉ mục/đánh đổi; không tạo checklist todo thứ hai.

## Phương án đã cân nhắc

| Phương án | Lý do không chọn ở bước đầu |
|---|---|
| Gộp toàn bộ thành modular monolith | Dễ vận hành hơn nhưng không đáp ứng mục tiêu học microservice đã xác nhận |
| Tạo 8 service theo 8 context ngay | Tăng deployment và debugging trước khi có tính năng |
| JWT tự cấp + refresh rotation | Thêm vòng đời token/revocation trước khi có nhu cầu client ngoài web |
| Redis bắt buộc cho session | PostgreSQL đang có, đủ cho mục tiêu demo; không cần thêm dependency vận hành |
| Kafka hoặc nhiều broker | Chưa có yêu cầu stream/replay đủ để gánh thêm hệ thống |
| Schema và API đầy đủ mọi service ngay | Chốt quá nhiều trước khi trải nghiệm luồng thật; thiết kế chi tiết theo lát cắt |

## Hệ quả

- Identity trở thành dependency của xác thực liên service; lỗi phải được kiểm soát, không bỏ auth để giữ availability.
- Hai repo cần ghim revision của contract; không copy sửa hai nguồn hoặc build bằng đường dẫn máy cá nhân.
- Database per service tạo nhu cầu eventual consistency/saga/outbox ở checkout; không có transaction toàn sàn.
- Bước này tạo tài liệu và hợp đồng, chưa tạo skeleton code. [Kế hoạch](../../tasks/plan.md) xác định bước bootstrap tiếp theo dưới quyền tự quyết đã có.

## Tài liệu

[Kiến trúc](../architecture/architecture-overview.md), [cấu trúc](../architecture/project-structure.md), [API](../architecture/api-conventions.md).

Nguồn đối chiếu: [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), [Maven](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [PasswordEncoder](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html). Phiên bản chính xác của tool/plugin còn lại được ghi lúc bootstrap, chưa suy ra đã cài.
