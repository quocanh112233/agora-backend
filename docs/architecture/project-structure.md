# Agora — Cấu trúc thư mục

Trạng thái ngày 24/10/2026: Identity, Merchant, Catalog M2a và ảnh M2b1 đã nghiệm thu. M2b2 công bố/ẩn có backend/UI và gates trước pause, sau resume frontend build/lint/typecheck, 86 unit và 144 fixture (19 regression revalidation) đã PASS; hai asset trước pause provider lookup all_missing=true/exit 0. P2 fixed/review accepted, final static review clear; normal live 4/4 (6.7s) và media/publication live 2/2 (8.2s) PASS. M2b2 đã nghiệm thu local ngày 24/10/2026 khoảng 15:40 +07:00 sau main xác minh hai asset mới DB DELETED/0 relation và independent exact provider lookup 2 asset all_missing=true/exit 0. Chưa có production/CI run hoặc inventory. Commerce/Payment/Feedback là đích cho lát cắt sau. backend/frontend là hai repo Git riêng. Không tạo repo thứ ba ở agora/ hoặc phụ thuộc filesystem của repo còn lại khi build. Tất cả service dùng PostgreSQL theo [ADR-0003](../decisions/0003-postgresql-only.md).

## 1. Repo backend

```text
backend/
├── pom.xml                         # Parent + reactor; Java/BOM/plugin thống nhất
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper được ghim
├── services/
│   ├── identity-service/           # Đã có
│   ├── merchant-service/           # Đã có, tách riêng
│   ├── catalog-service/            # Product/SKU, ảnh, visibility; chưa có inventory
│   ├── commerce-service/
│   ├── payment-service/
│   └── feedback-service/           # Dự kiến; JDBC/Flyway/PostgreSQL riêng
├── libraries/
│   ├── identity-contract/          # Sinh Java/protobuf từ contract Identity
│   └── merchant-contract/          # Sinh Java/protobuf từ contract Merchant
├── contracts/
│   ├── openapi/                    # Nguồn HTTP contract; JSON OpenAPI
│   ├── proto/                      # Nguồn .proto khi có service cần giao tiếp
│   └── events/                     # Schema integration event có version
├── infra/
│   ├── compose.local.yaml
│   └── postgres/                   # Tạo database/role cho môi trường local
├── docs/
│   ├── product/
│   ├── architecture/
│   └── decisions/
├── tasks/plan.md                   # Chỉ mục kế hoạch; tracker canonical: claude-mem
└── .github/workflows/
```

Reactor hiện có identity-contract, merchant-contract, identity-service, merchant-service và catalog-service. Maven Wrapper 3.9.16 đã ghim checksum. Backend/.env bị Git ignore và chỉ dùng local; .env.example được phép commit. Database agora/agora_merchant/agora_catalog chạy trên PostgreSQL native hiện có, chưa cần infra/compose.local.yaml. Service không phụ thuộc JAR của service khác; được dùng generated contract cần thiết. Các đường dẫn service/infra còn lại trong cây là thiết kế, không mặc định đã tạo file hoặc database.

## 2. Một service theo DDD

```text
services/identity-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/agora/identity/
    │   │   ├── IdentityApplication.java
    │   │   ├── domain/             # Account và quy tắc nghiệp vụ
    │   │   ├── application/        # Use case/port
    │   │   ├── adapter/
    │   │   │   ├── inbound/        # HTTP/gRPC khi có chức năng
    │   │   │   └── outbound/       # Persistence/provider adapter
    │   │   └── configuration/      # Wiring, security, serialization
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/       # Flyway, service sở hữu migration
    └── test/java/com/agora/identity/
        └── IdentityApiTests.java  # Cùng các unit test domain/security
```

- Domain chỉ dùng Java và abstraction nghiệp vụ; không import Spring/JPA/HTTP/SDK provider. Domain repository không extends JpaRepository.
- Application phụ thuộc domain và port; không gọi thẳng controller hoặc persistence adapter.
- Adapter inbound xác thực/parse request rồi gọi use case; adapter outbound hiện thực port, map domain với row PostgreSQL bằng JDBC. Feedback theo cùng cách, không có document repository/driver MongoDB.
- JpaRepository, DTO HTTP, protobuf generated type và persistence entity không được dùng như domain model xuyên các lớp.
- Main class ở package gốc; feature/module ở package bên dưới, không gom mọi controller/service/repository toàn ứng dụng.
- Context cùng process gọi interface ứng dụng của nhau, không truy cập persistence của nhau. Annotation/framework được đặt ngoài domain.
- Domain event là khái niệm nội bộ; integration event là hợp đồng đã map và có version, không serialize nguyên aggregate.
- Chưa tạo thư viện common/domain dùng chung. Chỉ tách phần kỹ thuật đã có nhu cầu lặp rõ ràng.

Identity và Merchant là hai process/database độc lập. Merchant có cây DDD tương tự, gọi gRPC Identity để xác minh principal/CSRF, không đọc database agora. Chưa có Dockerfile hoặc service Feedback; khi triển khai Feedback, migration nằm trong resources/db/migration của chính service, database dự kiến agora_feedback.

## 3. Contracts và generated code

- OpenAPI canonical ở contracts/openapi; implementation và test phải khớp cùng revision. Không duy trì một bản sửa tay thứ hai ở frontend.
- .proto đặt theo provider/context/version trong contracts/proto khi cần. Generated Java nằm target/generated-sources, không sửa tay/commit build output.
- Frontend nhận type/client HTTP từ artifact contract được ghim revision; không import filesystem ngoài repo để build.
- Mỗi integration schema ghi producer/consumer, ngữ nghĩa idempotency, thời gian, retry và compatibility.

## 4. Repo frontend

```text
frontend/
├── package.json, package-lock.json
├── next.config.ts, tsconfig.json
├── src/
│   ├── app/
│   │   ├── (storefront)/
│   │   ├── (auth)/login/, (auth)/register/
│   │   ├── seller/onboarding/
│   │   └── admin/shops/
│   ├── features/
│   │   ├── auth/
│   │   ├── merchant/
│   │   ├── catalog/
│   │   ├── cart/
│   │   ├── checkout/
│   │   └── feedback/
│   ├── components/ui/
│   ├── lib/api/                    # HTTP, error mapping, CSRF
│   └── generated/                  # Type/client từ contract ghim revision
├── public/
├── tests/e2e/
├── docs/project-structure.md
└── .github/workflows/
```

Trong feature chỉ thêm components/, api.ts, schemas.ts, store.ts hoặc test khi thực sự có mã. app/ ghép route/layout; nghiệp vụ UI nằm feature. Component chung ở components/ui chỉ khi được dùng lại.

Zustand dành cho trạng thái tương tác phía client, không giữ session ID hoặc coi giá/tồn kho frontend là sự thật. Server Component không đọc/ghi global store dùng chung người dùng. Dữ liệu /me và admin không cache công khai; UI bảo vệ route không thay phân quyền backend.

Next.js 16 App Router, TypeScript, npm; ghim patch cùng package-lock khi scaffold. Version React theo Next đã chọn. ESLint chạy bằng script riêng, không giả định next lint tồn tại.

## 5. Kiểm tra và cấu hình

Hiện có unit/integration tests, PostgreSQL Testcontainers và Playwright fixture/live cho M1; kết quả ở README hai repo. Feedback cũng dùng PostgreSQL Testcontainers khi triển khai; chưa có ArchUnit. Test fixture không chứa thông tin đăng nhập thật.

.env.example chỉ có tên biến/giá trị minh họa; .env.local và secret không commit. Migration append-only sau khi đã áp dụng; schema không để ORM tự cập nhật trong môi trường demo deploy.

Từ backend chạy ./mvnw verify để build/test, hoặc ./mvnw -pl services/identity-service spring-boot:run. Test và plugin run dùng cwd repo backend để Spring đọc .env tự động; chạy từ repo root, không từ thư mục service. Xem [README](../../README.md) và [plan](../../tasks/plan.md).

Nguồn: [Spring Boot structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html), [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [Next.js structure](https://nextjs.org/docs/app/getting-started/project-structure).
