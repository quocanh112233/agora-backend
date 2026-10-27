# ADR-0004 — Chỉ đặt hàng và thanh toán, bỏ vận chuyển

Ngày: 27/10/2026. Trạng thái: Accepted theo quyết định trực tiếp của người dùng trong phiên này. Thay thế các phần GHN/Fulfillment/giao nhận trong thiết kế cũ; không thay đổi bằng chứng nghiệm thu lịch sử.

## Quyết định

Người dùng bỏ phần giao hàng và chọn rõ **chỉ đặt hàng và thanh toán**, không nhận trực tiếp tại shop. Phạm vi hiện tại không có GHN, phí vận chuyển, địa chỉ người nhận, kiện hàng, nhận tại shop, vận đơn, tracking, callback giao nhận hoặc trạng thái đóng gói/giao/nhận. Tổng đơn bằng tổng tiền hàng. Không yêu cầu GHN_TOKEN/GHN_SHOP_ID hoặc gọi GHN nữa.

Sáu service đích giữ Identity/Merchant/Catalog/Commerce/Payment/Feedback. Fulfillment không còn là bounded context đang triển khai trong Commerce. M3c là checkout/đơn một shop; M3d tích hợp VNPAY sandbox. Không tạo service mới.

Order đích: AWAITING_PAYMENT → PAID hoặc EXPIRED/CANCELLED. PAID là trạng thái cuối của luồng mua, chỉ sau khi tiền hợp lệ và trừ tồn được xác nhận. Không tự coi Return URL là đã trả tiền. Không có mốc DELIVERED/COMPLETED để kích hoạt đánh giá; Feedback tương lai xác minh dòng đơn PAID. Tranh chấp chất lượng sau giao và return shipment ngoài phạm vi; hoàn tiền cho khoản thu trễ/trùng vẫn cần khi làm Payment.

Catalog M3b đã có HELD/RELEASED15phút. M3c dùng cơ chế đó, thêm xác minh giá/version nguyên tử cùng giữ tồn. Khi M3d làm thanh toán, bổ sung HELD → CONSUMED, giảm held và on_hand cùng transaction; không dùng SHIPPED giả. ALLOCATED/SHIPPED trong sơ đồ cũ không phải trạng thái sẽ thêm cho luồng mới. Các cột allocated hiện có giữ để không phá schema/lịch sử; hiện bằng0 trong dữ liệu test đã nghiệm thu.

## Dữ liệu và tương thích

Không xóa dữ liệu PostgreSQL hoặc rewrite migration cũ. Hồ sơ Merchant hiện có contact/pickup_address và metadata quy cách SKU đã lưu vẫn được giữ; đó không phải tích hợp vận chuyển và không được dùng để tính phí hay buộc người mua nhập địa chỉ. Việc đổi form/API sản phẩm đã nghiệm thu không nằm trong thay đổi checkout này. Không thêm shipping fields vào hợp đồng mới. Các tài liệu cũ có logistics chỉ còn giá trị lịch sử ở phần đã ghi ngày; yêu cầu mới phải theo ADR này.

## Hệ quả

Checkout đơn giản hơn: chọn một shop, xem snapshot giá/số lượng/tổng, xác nhận rồi tạo đơn chờ thanh toán. Giữ tồn và recovery vẫn bắt buộc. GHN sandbox credential không còn là blocker. Chỉ cần hỏi thông tin VNPAY khi đến tích hợp thật. Chưa làm thanh toán trong M3c, không có nút thanh toán giả.
