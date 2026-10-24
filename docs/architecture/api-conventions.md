# Agora — Hợp đồng API lát cắt 1

Nguồn machine-readable: [OpenAPI 3.1 JSON](../../contracts/openapi/identity-merchant.v1.openapi.json). Bundle của hai service tách riêng; x-service xác định provider. Ngày 23/10/2026, cả 11 thao tác dưới đây đã triển khai, gồm account/session/CSRF và shop draft/submit/admin review/resubmit. OpenAPI vẫn giữ nguyên revision dùng chung với frontend; mô tả lịch sử trong snapshot không phải báo cáo tiến độ.

Catalog M2a có artifact riêng [catalog.v1.openapi.json](../../contracts/openapi/catalog.v1.openapi.json) cho bốn thao tác seller product list/create/read/update. Không sửa artifact onboarding để thêm Catalog; phạm vi và quy tắc ở [kế hoạch M2a](../../tasks/slice-2-catalog.md) và [ranh giới Catalog](catalog-security.md).

## 1. Phạm vi và tuyến

Base path /api/v1, JSON camelCase, enum UPPER_SNAKE_CASE, UUID và timestamp RFC3339. Web gọi cùng origin qua allowlisted rewrites; backend vẫn xác thực mọi request.

| Method/path dưới /api/v1 | Quyền | Kết quả chính |
|---|---|---|
| GET /auth/csrf | Công khai | Token CSRF; có thể tạo anonymous session |
| POST /auth/registrations | Công khai + CSRF | Tạo USER, 201; không tự đăng nhập |
| POST /auth/sessions | Công khai + CSRF | Đăng nhập, 200 + HttpOnly cookie; sai thông tin 401 |
| DELETE /auth/sessions/current | Session + CSRF | Vô hiệu hóa, 204, xóa cookie |
| GET /me | Session | Hồ sơ/roles hiện tại, 200; mất phiên 401 |
| GET /me/shop | Session | Shop của mình, 200; chưa có 404 |
| POST /shops | Session + CSRF + Idempotency-Key | Tạo DRAFT, 201; đã có shop 409 |
| PATCH /shops/{shopId} | Owner + CSRF + Idempotency-Key | Sửa DRAFT/REJECTED, 200; cần expectedVersion |
| POST /shops/{shopId}/submissions | Owner + CSRF + Idempotency-Key | DRAFT thành PENDING_REVIEW, 200 |
| GET /admin/shops | ADMIN | Danh sách có phân trang/lọc status |
| POST /admin/shops/{shopId}/reviews | ADMIN + CSRF + Idempotency-Key | Duyệt/từ chối đúng expectedVersion, 200 |

Không có role trong input đăng ký; JSON field lạ bị từ chối ở request schema. Địa chỉ/tài khoản không công khai trong response của API người dùng khác.

## 2. Session, CSRF và cache

Cookie tên AGORA_SESSION, Path=/, HttpOnly, SameSite=Lax; Secure trên HTTPS. Local HTTP có profile riêng. Token CSRF lấy qua /auth/csrf, gửi header X-CSRF-TOKEN trên request thay đổi dữ liệu. Header này không thay session cookie.

Sau login/logout lấy CSRF token mới. Login handler phải dùng lifecycle Spring Security để đổi session ID, gọi session strategy và lưu SecurityContext; không chỉ tự gắn một attribute rồi coi đã login.

Mọi response chứa session/CSRF/profile/owner/admin data có Cache-Control: no-store. Không public-cache dữ liệu xác thực ở Next hoặc proxy. Server Component gọi dữ liệu riêng phải chuyển cookie phù hợp và không dùng cache dùng chung.

Chỉ chấp nhận các định dạng và kích thước body đã cấu hình. Đăng ký/login cần rate limit; MVP một instance dùng limiter bộ nhớ có giới hạn, ghi rõ không phải rate limit phân tán. Không ghi log thông tin xác thực.

Bootstrap ADMIN bằng [script SQL chạy thủ công](../../scripts/grant-local-admin.sql) cho một tài khoản ACTIVE đã tồn tại trong môi trường local/test; không có API public cấp ADMIN hoặc mật khẩu mặc định commit trong repo. Xem [demo](../demo-onboarding.md) trước khi dùng script; không chạy vào database khác ngoài database Identity đã chọn.

## 3. Lỗi và phân trang

Error body thống nhất: code (machine-readable), message, traceId, details là danh sách field/message khi phù hợp. HTTP 400 JSON sai cú pháp, 401 thiếu/sai session, 403 không có quyền hoặc CSRF sai, 404 không tồn tại/che tài nguyên chéo owner, 409 xung đột trạng thái/version/đang xử lý, 422 input không hợp lệ hoặc key đổi payload, 429 rate limit, 500 lỗi đã che chi tiết.

CSRF filter có thể trả 403 trước bước xác thực; không hứa mọi request anonymous thay đổi dữ liệu đều trả 401. Khi kiểm thử phân quyền, dùng CSRF hợp lệ để đo đúng điều kiện.

List admin: page bắt đầu 0, pageSize mặc định 20 tối đa 100; sort cố định createdAt giảm dần rồi id giảm dần. Response gồm items, page, pageSize, totalItems. Không nhận tên cột sort tùy ý.

## 4. Idempotency và cạnh tranh

- Client sinh UUID một lần cho một ý định, giữ nguyên qua retry; thao tác khác có key mới.
- Key scope theo actor + operation + key; payload hash gồm body và resource ID, không gồm cookie/traceId.
- Claim bằng unique constraint trong transaction; không SELECT rồi INSERT không có bảo vệ.
- Key cũ + payload khác trả 422 IDEMPOTENCY_PAYLOAD_MISMATCH; request đang xử lý trả 409 IDEMPOTENCY_IN_PROGRESS; đã xong replay status/body gốc sau khi kiểm tra quyền hiện tại.
- Kết quả lỗi validation/auth không cần giữ; kết quả business và thay đổi dữ liệu cần chiến lược transaction nhất quán. Không đánh dấu thành công trước commit.
- expectedVersion bảo vệ hai ý định khác nhau cùng tác động Shop; key không thay version lock.
- Form không tự retry đăng nhập/đăng ký; mất response đăng ký có thể login hoặc dùng khôi phục, unique email ngăn account trùng.

## 5. gRPC và sự kiện

Identity/Merchant tách ứng dụng theo ADR-0002. Trước khi Merchant gọi xác thực, định nghĩa .proto theo provider/version và contract test; không dùng interface in-process hoặc database chung để vượt ranh giới.

Hợp đồng [SessionIdentity v1](../../contracts/proto/agora/identity/v1/session_identity.proto) nhận `session_id`, `csrf_token`, `require_csrf`, trả `account_id` và `roles`. Merchant suy ra `require_csrf` từ HTTP method, không nhận quyết định này từ body của trình duyệt. Nguồn role/trạng thái account là Identity, ownership là Merchant; không trả email, password hoặc cookie trong response RPC.

Caller gửi metadata `x-agora-internal-token`, cấu hình bằng `INTERSERVICE_TOKEN` ngẫu nhiên 32–256 ký tự URL-safe, không hardcode. Local listener bind `127.0.0.1:9091`; plaintext chỉ dành cho loopback. Trước khi chạy qua mạng/container riêng phải thiết kế TLS/mTLS, không chỉ mở rộng bind. Không dùng bảng session chung hoặc header X-User-Id tự khai từ browser. Client đặt deadline 2 giây cho từng call, không tự retry; UNAUTHENTICATED/PERMISSION_DENIED/UNAVAILABLE tương ứng HTTP 401/403/503.

Catalog gọi Identity rồi [MerchantAccess.ResolveActiveShop](../../contracts/proto/agora/merchant/v1/merchant_access.proto) ở loopback 9092. Merchant chỉ trả shop_id đang ACTIVE thuộc account_id đã được caller xác minh qua Identity. Cùng metadata nội bộ, deadline 2 giây/call, không cache quyền hoặc bỏ kiểm tra khi replay. INVALID_ARGUMENT cho account ID sai; chưa có shop/shop không ACTIVE trả PERMISSION_DENIED; lỗi DB trả UNAVAILABLE. Chi tiết ranh giới và race giữa các service ở [catalog-security](catalog-security.md).

Nguồn: [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html).
