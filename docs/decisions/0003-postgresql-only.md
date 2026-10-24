# ADR-0003 — Chỉ dùng PostgreSQL cho dữ liệu ứng dụng

## Trạng thái

Accepted ngày 2026-10-23 theo yêu cầu trực tiếp của người dùng: bỏ MongoDB, chỉ dùng PostgreSQL. Thay thế lựa chọn MongoDB cho Feedback trong [ADR-0001](0001-foundation.md). Giữ ranh giới sáu service và database per service của [ADR-0002](0002-separate-identity-merchant.md).

## Bối cảnh

Identity và Merchant đã triển khai trên PostgreSQL. Feedback mới có yêu cầu và thiết kế, chưa có service, dependency, database hoặc dữ liệu MongoDB trong dự án. Review, câu hỏi, câu trả lời và phản hồi shop có quan hệ rõ ràng; dự án do một người phát triển nên một hệ quản trị database giảm công việc vận hành và kiểm thử.

## Quyết định

- Tất cả sáu service dùng PostgreSQL. Feedback dự kiến sở hữu database `agora_feedback`, schema `feedback`, migration Flyway và adapter JDBC theo cách đang dùng ở Identity/Merchant. Chỉ tạo database/migration khi triển khai Feedback.
- Lưu review, question, answer và seller reply thành các bảng quan hệ riêng; không gom mọi nội dung thành một cột JSONB hoặc một cây comment không giới hạn. Nội dung dùng `text`; rating, author, product/shop/order-line ID, trạng thái và thời gian có cột rõ ràng.
- Bảo vệ một review mỗi dòng đơn bằng unique constraint trên `order_line_id`; rating có CHECK từ 1 đến 5. FK chỉ giữa các bảng Feedback. Tham chiếu Commerce/Catalog/Merchant/Identity là ID, được kiểm tra qua hợp đồng service; không join hoặc tạo FK xuyên database.
- Review/reply, thay đổi kiểm duyệt, audit và thống kê sao cục bộ phải có transaction nhất quán. Khi bắt đầu triển khai, ưu tiên thống kê trực tiếp từ review đang hiển thị; nếu cần projection thì thiết kế cơ chế cập nhật và tính lại rõ ràng.
- Trong Feedback, JSONB chỉ dùng cho metadata tùy chọn có cấu trúc thay đổi thực sự; các khóa, rating, trạng thái, nội dung và ràng buộc nghiệp vụ không giấu trong JSONB. Địa chỉ và idempotency snapshot JSONB hiện có của Merchant không cần đổi.
- Cloudinary tiếp tục giữ file ảnh. PostgreSQL lưu `public_id`, metadata và quan hệ với tài nguyên; không lưu binary ảnh hoặc API secret trong bảng nội dung công khai.

## Phương án đã cân nhắc

| Phương án | Đánh đổi |
|---|---|
| PostgreSQL với bảng quan hệ cho Feedback | Tái sử dụng công cụ hiện có; unique/FK/transaction rõ ràng. Cần thiết kế bảng và index theo truy vấn thực tế. Chọn phương án này. |
| Giữ MongoDB riêng cho Feedback | Có mô hình document linh hoạt nhưng thêm database, backup, driver và môi trường test; không còn phù hợp lựa chọn của người dùng. |
| PostgreSQL nhưng nhúng toàn bộ thread trong JSONB | Ít bảng hơn nhưng khó bảo vệ quyền từng reply, phân trang, cạnh tranh và thống kê. Không chọn làm mô hình chính. |

## Hệ quả

- Giữ microservices + DDD và sáu database độc lập theo quyền sở hữu. Cùng dùng PostgreSQL không có nghĩa chia sẻ bảng, role production hoặc transaction xuyên service.
- Không cần chuyển dữ liệu, sửa SQL migration đã áp dụng, gỡ package hoặc đổi API hiện tại vì chưa triển khai MongoDB. M1 tài khoản/mở shop không đổi hành vi.
- M5 review/Q&A sẽ dùng PostgreSQL Testcontainers, JDBC và Flyway; không cài MongoDB hoặc thêm container MongoDB.
- Việc xác minh đã mua/đã nhận hàng vẫn thuộc hợp đồng Commerce. Dùng chung công nghệ database không thay thế xác minh quyền này hoặc tạo transaction phân tán toàn sàn.
- Người dùng xác nhận đã có tài khoản Cloudinary; cấu hình secret sẽ được thực hiện ở file local bị ignore khi đến lát cắt ảnh, không qua chat hoặc commit.

## Tài liệu

[Mô hình dữ liệu](../architecture/data-model.md), [kiến trúc](../architecture/architecture-overview.md), [bản đồ nghiệp vụ](../product/capability-map.md), [kế hoạch](../../tasks/plan.md).

Nguồn: [PostgreSQL 16 JSON/JSONB](https://www.postgresql.org/docs/16/datatype-json.html), [PostgreSQL 16 constraints](https://www.postgresql.org/docs/16/ddl-constraints.html).
