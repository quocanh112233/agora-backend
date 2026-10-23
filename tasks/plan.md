# Agora — Kế hoạch triển khai

Trạng thái: đã bootstrap identity-service riêng, Maven/Wrapper và database agora local theo yêu cầu mới. S1-01/S1-02 được kiểm chứng ở tracker; các endpoint nghiệp vụ còn todo. [ADR-0002](../docs/decisions/0002-separate-identity-merchant.md) thay lựa chọn gộp service. Không yêu cầu duyệt lại lựa chọn thông thường; cần hỏi khi phát sinh tài khoản ngoài/chi phí/publish ngoài phạm vi.

## 1. Nguồn và tracker

[Use case](../docs/product/use-cases.md), [state machines](../docs/product/state-machines.md), [kiến trúc](../docs/architecture/architecture-overview.md), [cấu trúc](../docs/architecture/project-structure.md), [dữ liệu](../docs/architecture/data-model.md), [OpenAPI](../contracts/openapi/identity-merchant.v1.openapi.json).

Task canonical: claude-mem work_state, list slice-1-onboarding. Mỗi task có acceptance, verify, dependencies và files trong tracker; không tạo tasks/todo.md trùng lặp. Bảng dưới là chỉ mục có thể đọc khi không có plugin, không phải trạng thái task thay thế tracker.

## 2. Lát cắt 1

Kết quả: khách đăng ký/login, tạo DRAFT shop, gửi duyệt; admin đăng nhập và duyệt/từ chối; chủ shop xem trạng thái/lý do, sửa và gửi lại. Backend chặn truy cập chéo, stale version và thay đổi không đúng trạng thái. Trace tới UC-01/UC-02; ảnh/avatar và khôi phục mật khẩu thuộc phần hoàn thiện Identity sau luồng cơ bản, không bị xóa khỏi MVP.

| ID | Kết quả nhỏ cần làm | Phụ thuộc |
|---|---|---|
| S1-01 | Bootstrap Maven Wrapper, root/service module và ứng dụng health tối thiểu | Không |
| S1-02 | PostgreSQL local, Flyway, cấu hình profile/test sạch | S1-01 |
| S1-03 | Đăng ký account USER, password hash và unique email | S1-02 |
| S1-04 | Session login/logout, /me, CSRF và giới hạn thử đăng nhập | S1-03 |
| S1-05 | Bootstrap Next.js, TypeScript, lint/build và HTTP cùng origin | Không; contract đã có |
| S1-06 | UI đăng ký/login/logout và trạng thái phiên | S1-04, S1-05 |
| S1-M01 | Bootstrap Merchant riêng và hợp đồng xác thực Identity; chia nhịp chi tiết khi thực hiện | S1-04 |
| S1-07 | Backend tạo/xem/sửa Shop DRAFT, ownership và idempotency | S1-M01 |
| S1-08 | Backend gửi duyệt và sửa hồ sơ bị từ chối | S1-07 |
| S1-09 | UI onboarding shop và trạng thái chờ/từ chối | S1-06, S1-08 |
| S1-10 | Backend admin list/review với version guard/audit | S1-08 |
| S1-11 | UI admin duyệt/từ chối và hiển thị conflict | S1-09, S1-10 |
| S1-12 | E2E luồng mở shop và các ca quyền/cạnh tranh quan trọng | S1-11 |
| S1-13 | GitHub Actions kiểm tra hai repo và hướng dẫn chạy demo | S1-12 |

Task nghiệp vụ nhắm khoảng 3–5 file sửa tay mỗi nhịp; tách tiếp nếu vượt. Bootstrap công cụ có thể sinh nhiều file cơ học, không trộn thêm tính năng vào cùng bước.

Checkpoint A sau S1-04: registration/session/CSRF test đúng trên PostgreSQL, không lộ secret. Checkpoint B sau S1-06 và S1-08: một người dùng tạo/gửi shop từ web, replay không tạo shop trùng. Checkpoint C sau S1-12: admin duyệt/từ chối, owner khác bị chặn, version conflict có thể tái hiện. Checkpoint cuối sau S1-13: build/test và hướng dẫn demo tái hiện được.

Checkpoint là kiểm tra kỹ thuật; theo quyền tự quyết hiện có, báo kết quả rồi tiếp tục công việc đã được giao, không yêu cầu một vòng duyệt thủ công cho từng bước.

## 3. Lệnh backend hiện có và frontend dự kiến

Backend đã có các lệnh dưới và PostgreSQL 16 native local; .env được Spring import từ cwd backend. Chưa có Docker Compose hoặc script frontend, các lệnh npm chỉ là mục tiêu sau scaffold:

```bash
# Từ repo backend
./mvnw verify
pg_isready -h 127.0.0.1 -p 5432
./mvnw -pl services/identity-service spring-boot:run

# Từ repo frontend, sau khi có package-lock và scripts
npm ci
npm run lint
npm run typecheck
npm run build
npm run test:e2e
```

Hiện có JUnit smoke test trên PostgreSQL local, không H2, không Testcontainers. CI sẽ dùng database test riêng; Testcontainers/ArchUnit/Playwright được bổ sung theo tính năng. Không gọi bộ test đăng nhập/mở shop là pass trước khi có implementation.

## 4. Các mốc sau lát cắt 1

| Mốc | Luồng hoàn chỉnh | Điều kiện chú ý |
|---|---|---|
| M2 | Shop hoạt động đăng sản phẩm, ảnh Cloudinary và lô; khách tìm/xem | Thêm Catalog, gRPC auth/merchant, FE catalog, kiểm tra quyền ảnh |
| M3 | Giỏ một shop, GHN báo giá, giữ tồn, tạo đơn | Kiểm tra GHN staging/địa chỉ sớm; tồn cuối và hết hạn |
| M4 | VNPAY sandbox, GHN tạo/đồng bộ, nhận hàng | Tiền muộn, timeout, webhook, RabbitMQ/outbox, refund |
| M5 | Review/Q&A MongoDB, claim/refund, đối soát mô phỏng | Quyền đã mua, hạn mức hoàn, hàng hỏng không nhập tự động |
| M6 | Hoàn thiện Identity/quản trị, ảnh avatar, demo/CI/README portfolio | Khôi phục mật khẩu có token dùng một lần; bổ sung tính năng còn lại từ UC |

Đây là mốc định hướng, chưa phải task đã chia nhỏ hay chức năng đã triển khai. Chỉ lập task chi tiết khi mốc gần nhất đã rõ hợp đồng.

## 5. Rủi ro và hướng xử lý

| Rủi ro | Xử lý |
|---|---|
| Phiên bản Java/Boot/tooling không khớp plugin | Bootstrap kiểm tra tương thích, ghim wrapper/dependencies/lockfile, lưu bản thực tế |
| Hai repo lệch API | Một OpenAPI canonical, frontend ghim revision; test contract khi đổi |
| Session cookie/CSRF bị mất qua routing | E2E kiểm tra Set-Cookie, token mới sau login/logout, no-store và quyền ở backend |
| GHN/VNPAY chưa cấp quyền callback/refund | Probe staging trước luồng hoàn chỉnh; fixture gắn nhãn, không giả vờ provider thành công |
| Chi phí/credential Cloudinary và hosting | Chỉ cấu hình khi được cung cấp; không xin secret qua log hoặc commit |
| Scope kéo dài vì không có deadline | Hoàn thành từng luồng dọc; không mở hết service đồng thời |

Không commit/push/deploy tự động trong bước chuẩn bị này.
