# Demo lát cắt tài khoản và mở shop

Ngày cập nhật: 23/10/2026. Backend và frontend là hai Git repository riêng trong cùng workspace. Hướng dẫn này dành cho local HTTP loopback; chưa phải hướng dẫn triển khai production.

## 1. Chuẩn bị

- Java 25, Docker cho integration tests, PostgreSQL native 16, Node 22.23.3 và npm 12.2.0.
- Database `agora` và `agora_merchant` đã tạo trên máy hiện tại. Khi clone sang máy khác, tạo hai database riêng trước khi chạy; không dùng database chứa dữ liệu của dự án khác.
- `backend/.env` hiện có, bị ignore và quyền 600. Khi clone mới, chỉ copy `.env.example` nếu file local chưa tồn tại. Đặt credential PostgreSQL của mình, `DB_NAME=agora`, `MERCHANT_DB_NAME=agora_merchant`, `SERVER_PORT=8081`, `MERCHANT_SERVER_PORT=8082`.
- Bật `IDENTITY_GRPC_ENABLED=true`, cổng 9091; cùng `INTERSERVICE_TOKEN` ngẫu nhiên 32–256 ký tự URL-safe cho hai service. Có thể tạo token bằng `openssl rand -hex 32`, rồi đặt vào `.env`; không đưa token vào chat, Git hoặc biến `NEXT_PUBLIC_*`.
- Frontend dùng `IDENTITY_API_ORIGIN=http://127.0.0.1:8081`, `MERCHANT_API_ORIGIN=http://127.0.0.1:8082` theo `.env.example`. Chỉ dùng credential giả cho demo.

Không ghi đè `.env` hiện có. Không cần source file secret vào shell: hai app Spring đọc file từ cwd `backend/`.

## 2. Chạy local

Terminal đầu, từ `backend/`:

```bash
./mvnw -B -ntp -pl libraries/identity-contract -am install
./mvnw -pl services/identity-service spring-boot:run
```

Terminal thứ hai, cũng từ `backend/`:

```bash
./mvnw -pl services/merchant-service spring-boot:run
```

Terminal thứ ba, từ `frontend/`:

```bash
npm ci --ignore-scripts
npm run dev
```

Kiểm tra `/actuator/health` của hai backend trên cổng 8081/8082, sau đó mở `http://127.0.0.1:3000`. Spring áp dụng migration vào database tương ứng khi startup. Không sửa migration đã áp dụng. Dừng từng app bằng Ctrl+C.

## 3. Chuẩn bị tài khoản ADMIN

Đăng ký một tài khoản riêng cho người duyệt tại `/register`. Đăng ký chỉ tạo USER. Chọn đúng database Identity rồi chạy script dưới đây từ `backend/`; thay email bằng email tài khoản vừa tạo. `psql` hỏi mật khẩu PostgreSQL, không đặt mật khẩu vào command line.

```bash
psql -h 127.0.0.1 -p 5432 -U postgres -d agora -W \
  -v admin_email='admin-ban-chon@example.test' \
  -f scripts/grant-local-admin.sql
```

Script chỉ thêm role ADMIN cho một account ACTIVE đã tồn tại; không tạo account/mật khẩu, không xóa role USER. Chạy lại không thêm role trùng. Không tìm thấy account thì dừng với lỗi. Chỉ chạy sau khi đã chọn đúng database và email; đây là thao tác cấp quyền có chủ ý của người vận hành.

Không cấp ADMIN cho mọi account hoặc sửa role qua trình duyệt. Hai backend đọc quyền hiện tại từ Identity qua gRPC; sau khi cấp quyền, tải lại `/account` để giao diện thấy role mới. Chưa tự cấp ADMIN vào database native trong các bài kiểm chứng ngày 23/10; fixture ADMIN chỉ nằm trong database Docker thử nghiệm.

## 4. Hai luồng demo

Dùng hai browser profile/cửa sổ riêng tư độc lập để tách phiên người bán và ADMIN.

1. Người bán đăng ký, đăng nhập, mở `/seller/onboarding`, điền form và tạo bản nháp. Một người bán chỉ có một shop.
2. Gửi duyệt; shop thành `PENDING_REVIEW`. Reload vẫn thấy trạng thái chờ duyệt và không sửa được hồ sơ.
3. ADMIN mở `/admin/shops`, xem hồ sơ chờ duyệt và phê duyệt. Người bán reload thấy `ACTIVE`.
4. Với người bán thứ hai, tạo/gửi shop khác. ADMIN từ chối với lý do đủ 10–1000 ký tự.
5. Người bán reload thấy `REJECTED` và lý do; sửa địa chỉ, lưu để trở về `DRAFT`, rồi gửi lại. ADMIN thấy hồ sơ chờ duyệt lần nữa.

Kiểm tra thêm bằng hai tab cùng hồ sơ: sau khi tab thứ nhất thay đổi version, thao tác từ tab cũ phải bị chặn 409 và yêu cầu tải lại. USER không được xem trang quản trị. Địa chỉ nhập ở lát cắt này mới kiểm tra hình dạng, chưa đối chiếu địa giới/GHN; trạng thái ACTIVE không khẳng định GHN đã xác minh địa chỉ.

## 5. Kiểm thử tự động

Backend, từ `backend/`:

```bash
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify
```

Ngày 23/10 đạt 46 tests: 13 Identity và 33 Merchant, không fail/error/skip. Testcontainers tự tạo PostgreSQL riêng; không reset/truncate dữ liệu native. Bao gồm schema V1 có dữ liệu nâng lên V2, ownership, CSRF, version, idempotency, rollback và review cạnh tranh.

Frontend, từ `frontend/`:

```bash
npm run audit
npm run lint
npm run typecheck
npm test
npm run build
npm run test:e2e -- --workers=2
```

`test:e2e` dùng API fixture, không chứng minh hai backend thật. `test:live` gọi Next rewrites tới hai backend thật, tạo tài khoản/shop tổng hợp và cần ADMIN fixture:

```bash
npm run test:live -- --workers=1
```

Trước khi chạy live, trỏ hai backend tới **hai database thử nghiệm biệt lập**, áp dụng migration, tạo một account ADMIN fixture bằng luồng đăng ký và script đã nêu. Tạo `frontend/.env.live-test` bị ignore, quyền 600, chứa `TEST_ADMIN_EMAIL`/`TEST_ADMIN_PASSWORD` của fixture. Script npm nạp file này; không copy credential vào báo cáo. Thiếu ADMIN fixture làm suite báo lỗi, không skip. Suite không tự reset database và sẽ để lại dữ liệu tổng hợp; không chạy vào database đang dùng thật. Không bật trace/video/screenshot chứa credential.

Gate audit hiện có ngoại lệ tạm cho đúng chuỗi dependency dev chứa braces chưa được vá, hết hạn 30/10/2026 UTC; gate đạt không có nghĩa native npm audit sạch. Xem [phạm vi và bằng chứng](../../frontend/docs/security/dependency-audit.md).

## 6. CI và ghép revision hai repository

Mỗi repo có `.github/workflows/ci.yml`: backend chạy reactor verify; frontend chạy audit/lint/types/unit/build/browser fixture. Action được ghim SHA, token chỉ có quyền đọc. Chưa có GitHub run thực tế và chưa có live job tự checkout backend từ frontend.

Hai origin hiện có là `git@github.com:quocanh112233/agora-backend.git` và `git@github.com:quocanh112233/agora-frontend.git`. Để báo cáo live có thể tái lập, mã nguồn cần được commit trước. Chọn một full commit SHA backend và một full commit SHA frontend tương thích; ghi cả hai trong kết quả chạy/PR. Checkout detached từng SHA vào hai thư mục riêng, dùng `npm ci --ignore-scripts` và Maven Wrapper của các revision đó; khởi động fixture database/service như mục 5 rồi chạy live từ frontend. Chỉ pin SHA có chứa code đã kiểm chứng; HEAD bootstrap hiện tại không đại diện cho worktree chưa commit.

Chưa tạo job điều phối xuyên repo vì chưa có cặp commit chứa implementation được chốt. Không tự commit/push hoặc đặt secret GitHub để hoàn tất bước này. Cần người dùng cho phép bước xuất bản code và chốt revision trước khi kích hoạt CI thật; không cần hỏi lại repository URL đã có ở origin.

## 7. Phần tiếp theo

Sau nghiệm thu mở shop: Catalog với product/SKU đóng gói cố định và ảnh Cloudinary. MongoDB dành cho Feedback về sau; VNPAY/GHN sandbox đến lát cắt checkout/giao hàng. Chưa cần credential các nhà cung cấp để chạy demo tài khoản/mở shop.
