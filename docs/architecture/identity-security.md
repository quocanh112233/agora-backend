# Identity: ranh giới và kiểm chứng bảo mật

## Phạm vi

Lát cắt đầu gồm đăng ký, đăng nhập, đọc tài khoản hiện tại và đăng xuất.
Không có đăng nhập mạng xã hội, khôi phục mật khẩu hoặc email xác minh ở lát cắt này.
Không tự tạo tài khoản ADMIN từ dữ liệu gửi lên API.

## Ranh giới tin cậy

| Ranh giới | Tài sản / tình huống lạm dụng | Kiểm soát |
|---|---|---|
| Trình duyệt → HTTP API | Giả role, JSON thừa field, mật khẩu quá dài | DTO allowlist, validation, lỗi không kèm giá trị nhạy cảm |
| Trình duyệt → session | CSRF, session fixation, replay sau logout | CSRF trên cả đăng ký/login; đổi session ID khi login; invalidate khi logout |
| API → PostgreSQL | SQL injection, email trùng khi chạy đồng thời | Tham số JDBC; unique email chuẩn hóa trong database |
| Đọc account / session | Rò hash, tiếp tục dùng account bị khóa | Response allowlist; kiểm tra trạng thái hiện tại, không chỉ tin snapshot trong session |
| Endpoint hash mật khẩu | Brute force / cạn RAM | Argon2id; giới hạn số lần và số khóa theo dõi trong bộ nhớ |

Không tin `X-Forwarded-For` do client gửi. Không nhận danh tính qua `X-User-Id`.
Rate limit bộ nhớ chỉ phù hợp một instance local; trước nhiều replica phải thay bằng bộ đếm dùng chung.
Proxy local khiến nhiều client có thể chung địa chỉ IP ở backend; đây chưa phải cấu hình ingress production.

## Dữ liệu

Email và tên hiển thị là PII, phục vụ đăng nhập và hiển thị tài khoản.
Password chỉ tồn tại trong request và quá trình hash; database chỉ giữ Argon2id hash.
Không ghi password, hash, cookie, CSRF hoặc toàn bộ request vào log.
Session có idle timeout 30 phút; Spring Session dọn session hết hạn định kỳ.
Tài khoản không có TTL tự động; chức năng xuất/xóa tài khoản cùng chính sách lưu giữ phải được bổ sung trước triển khai thực tế.

## Session và trình duyệt

Cookie `AGORA_SESSION` có Path=/, HttpOnly, SameSite=Lax.
Chạy HTTPS phải bật Secure; HTTP loopback local là ngoại lệ có cấu hình rõ.
Spring Session JDBC lưu session trong schema Identity; Flyway quản lý cấu trúc bảng.
Trình duyệt lấy token CSRF từ `/api/v1/auth/csrf` trước mutation và lấy lại sau login/logout.
Phản hồi API có `Cache-Control: no-store`; không lưu session/token/password vào Zustand persist hoặc localStorage.

## Xác thực nội bộ qua gRPC

Listener chỉ bật khi `IDENTITY_GRPC_ENABLED=true`; token rỗng/ngắn/sai định dạng làm startup thất bại khi bật RPC. Bind cố định loopback, giới hạn message 4 KiB; không expose listener cho trình duyệt. Secret nội bộ khác session người dùng, được kiểm tra trước khi xử lý request.

Identity đọc session của chính mình, kiểm tra account/quyền hiện tại và token CSRF masked khi cần. Phiên anonymous, expired, logged-out hoặc account SUSPENDED không được xác thực. Lỗi phụ thuộc trả UNAVAILABLE, không trả principal dự phòng. Không log cookie, token hoặc nội dung request.

RPC gia hạn idle timeout bằng update có điều kiện theo `session_id` hiện hành và expiry. Không dùng `JdbcIndexedSessionRepository.save` để touch snapshot cũ: save ghi lại SESSION_ID theo primary key và có thể đảo ngược lần login rotation đang chạy đồng thời. Test dùng latch tái hiện race trước sửa, sau sửa request cũ bị từ chối và phiên mới vẫn hợp lệ.

`INTERSERVICE_TOKEN` + loopback là cấu hình local, không phải biện pháp thay TLS/mTLS cho deployment nhiều máy. Rate limit phân tán, giới hạn tải production và quản lý/luân chuyển secret còn thuộc bước triển khai thực tế.

## Nguồn đối chiếu

- [Spring Security: lưu và xoay session](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security: CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Security: password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Spring Session JDBC và schema PostgreSQL](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
- [Spring Boot: Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [gRPC: authentication](https://grpc.io/docs/guides/auth/)
- [gRPC: deadlines](https://grpc.io/docs/guides/deadlines/)

Test tích hợp dùng PostgreSQL Docker riêng, không reset/truncate database `agora` local.
Kết quả và lệnh kiểm thử cụ thể được cập nhật trong README sau khi chạy thực tế.
