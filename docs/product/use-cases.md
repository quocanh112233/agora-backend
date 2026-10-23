# Agora — Use case MVP v0.1

Trạng thái: bản nháp để review cùng [bản đồ context](capability-map.md). Các mã UC/AC phục vụ truy vết tới API và kiểm thử sau; chưa là kế hoạch hay danh sách task triển khai.

## UC-01 — Tài khoản và hồ sơ

- Tác nhân: khách, người mua, chủ shop, admin.
- Luồng: đăng ký, đăng nhập/đăng xuất, khôi phục mật khẩu, sửa hồ sơ/avatar và sổ địa chỉ.
- AC-01.1: định danh trùng không tạo account mới; mật khẩu không xuất hiện trong response/log.
- AC-01.2: không truy cập được hồ sơ/địa chỉ/đơn người khác bằng đổi ID; phiên hết hiệu lực bị backend từ chối.
- AC-01.3: sửa avatar không cho phép gắn hoặc xóa ảnh thuộc người khác.

## UC-02 — Mở và quản lý shop

- Tác nhân: người dùng đăng nhập, admin.
- Luồng: khai báo shop/điểm lấy hàng, gửi duyệt; admin duyệt hoặc từ chối có lý do; shop hoạt động được đăng bán.
- AC-02.1: shop chưa duyệt/bị khóa không nhận checkout mới. Đơn cũ vẫn được xử lý hoặc hoàn tiền theo quyền thích hợp.
- AC-02.2: chỉ chủ shop/admin phù hợp được sửa thông tin shop; token GHN không xuất hiện trên frontend.
- AC-02.3: liên kết `ShopId` GHN staging phù hợp. Nếu demo nhiều shop trên tài khoản staging chung, phải ghi rõ giới hạn mô phỏng.
- AC-02.4: đổi điểm lấy hàng không sửa snapshot đơn/vận đơn đã tạo.

## UC-03 — Danh mục, sản phẩm và SKU

- Tác nhân: chủ shop; admin quản lý danh mục/kiểm duyệt.
- Luồng: tạo nháp, khai báo thông tin, upload ảnh, tạo SKU/giá, công bố hoặc ẩn.
- Dữ liệu ngành hàng: xuất xứ khai báo, cơ sở sản xuất, quy cách, nhóm sản phẩm, tình trạng tươi/đông lạnh/khô/chế biến khi phù hợp, bảo quản và chứng nhận nếu có.
- AC-03.1: SKU có mã, giá hợp lệ, đơn vị bán, khối lượng gói; thông số phục vụ tính kiện có đơn vị đo rõ. Không ép nhập trường không phù hợp.
- AC-03.2: chứng nhận shop upload không tự được gắn nhãn đã xác minh; quyết định xác minh có người duyệt/audit.
- AC-03.3: hàng ẩn/khóa không checkout mới; snapshot đơn cũ vẫn đọc được.
- AC-03.4: chỉ chủ shop quản lý hàng của mình; tiền theo số nguyên VND trong quy ước MVP, không số thực nhị phân.

## UC-04 — Lô hàng và tồn kho

- Tác nhân: chủ shop, checkout, tác vụ hệ thống.
- Luồng: nhập lô theo SKU, khai báo số lượng và ngày tháng phù hợp; giữ hàng từ lô đủ điều kiện, ưu tiên hết hạn trước.
- AC-04.1: request đồng thời không làm tổng giữ/phân bổ vượt tồn vật lý.
- AC-04.2: thiếu một SKU không để lại giữ hàng các SKU khác trong cùng thao tác thất bại.
- AC-04.3: không phân bổ lô hết hạn/bị khóa/không đủ thời gian sử dụng còn lại theo chính sách.
- AC-04.4: hết hạn hoặc hủy chỉ giải phóng một lần; request lặp cùng khóa nghiệp vụ trả cùng kết quả.
- AC-04.5: khóa/thu hồi lô chặn phân bổ/xuất mới. Đơn đã trả tiền nhưng chưa xuất từ lô đó vào xử lý ngoại lệ.
- AC-04.6: tồn vật lý giảm khi bàn giao; giải phóng giữ hàng không cộng tồn vật lý lần nữa.

## UC-05 — Tìm và xem sản phẩm

- Tác nhân: mọi người dùng.
- Luồng: tìm tên, lọc danh mục/giá/xuất xứ/shop; xem sản phẩm, gian hàng, review và hỏi đáp.
- AC-05.1: phân trang/sắp xếp có quy tắc ổn định; hàng ẩn/khóa không hiển thị như có thể mua.
- AC-05.2: giá theo gói, khối lượng, bảo quản, thông tin nguồn gốc và shop hiển thị rõ.
- AC-05.3: tìm kiếm/tổng sao có thể trễ; checkout xác minh lại giá và tồn kho.

## UC-06 — Giỏ hàng và checkout một shop

- Tác nhân: người mua đăng nhập.
- Tiền điều kiện: shop hoạt động; SKU cùng shop; địa chỉ nhận hợp lệ cho tích hợp.
- Luồng: chọn hàng của một shop, địa chỉ nhận, tính kiện/báo giá GHN, xác nhận tổng tiền, giữ hàng và tạo đơn chờ thanh toán.
- AC-06.1: giỏ chứa nhiều shop được phép; backend từ chối một request checkout trộn shop.
- AC-06.2: backend tính giá/cước; thay đổi trước xác nhận phải cho khách xem và xác nhận lại.
- AC-06.3: không lấy được báo giá hoặc kiện không hỗ trợ thì chưa thu tiền, có thông báo thử lại.
- AC-06.4: cùng idempotency key tạo một đơn/reservation; payload khác với cùng key bị từ chối.
- AC-06.5: đơn lưu snapshot tên/SKU/quy cách/giá, địa chỉ nhận/lấy, cước chấp nhận, chính sách và tham chiếu các lô phân bổ.
- AC-06.6: lỗi sau giữ hàng trước lưu đơn phải được phục hồi hoặc hết hạn, không giữ hàng vĩnh viễn.

## UC-07 — Thanh toán VNPAY sandbox

- Tác nhân: người mua, VNPAY, hệ thống.
- Luồng: tạo payment attempt hợp lệ, chuyển khách tới cổng, nhận IPN, xác minh và lưu kết quả, thông báo Commerce xử lý tiếp.
- AC-07.1: đối chiếu chữ ký, tham chiếu, merchant, số tiền và kết quả trước khi ghi nhận tiền.
- AC-07.2: Return URL chỉ hiển thị/truy vấn backend; đóng tab hoặc sửa URL không làm đơn thành đã trả tiền.
- AC-07.3: IPN lặp không ghi tiền hoặc tạo hiệu lực nghiệp vụ hai lần; chỉ phản hồi sau khi lưu kết quả bền vững.
- AC-07.4: tiền tới muộn vẫn ghi nhận; đơn hết hạn không tự mở lại/giao hàng. Yêu cầu hoàn tiền được theo dõi đến kết quả.
- AC-07.5: hai attempt cùng thành công chỉ có một khoản áp vào đơn; khoản thu thừa có yêu cầu hoàn riêng, có audit.
- AC-07.6: timeout không chứng minh thất bại; có tra cứu/đối chiếu theo tham chiếu, hiển thị đang xác minh khi chưa rõ.

## UC-08 — Xác nhận và đóng gói

- Tác nhân: chủ shop.
- Luồng: xem đơn đã trả tiền/đủ phân bổ, xác nhận, chuẩn bị hàng, khai báo kiện và yêu cầu tạo vận đơn.
- AC-08.1: không giao khi tiền chưa xác nhận hoặc còn ngoại lệ tồn kho/lô.
- AC-08.2: shop từ chối trước bàn giao phải có lý do; hủy hợp lệ, giải phóng hàng và yêu cầu hoàn tiền. Yêu cầu hoàn không đồng nghĩa đã hoàn.
- AC-08.3: đổi khối lượng kiện không tự thu thêm tiền khách. Chênh lệch cước được đối soát; mặc định MVP sàn chịu phần chênh.

## UC-09 — Vận chuyển GHN staging

- Tác nhân: chủ shop, Fulfillment, GHN.
- Luồng: tạo vận đơn từ snapshot, lưu mã, nhận callback/tra cứu và cập nhật tiến trình.
- AC-09.1: dùng thông tin staging; không COD với đơn đã trả trước; không thu lại cước từ khách.
- AC-09.2: timeout tạo đơn chuyển chưa rõ; tra theo tham chiếu được hỗ trợ trước khi gửi lại. Không đủ bằng chứng thì cần xử lý thủ công, không tạo kiện trùng.
- AC-09.3: kiểm tra callback theo khả năng GHN hỗ trợ và đối chiếu mã vận đơn/tài khoản/tham chiếu. Nguồn chưa đủ tin cậy thì tra cứu API trước quyết định ảnh hưởng hàng/tiền.
- AC-09.4: callback lặp/cũ/xung đột không tự lùi trạng thái đã giao; hiệu chỉnh thật cần đối chiếu/audit.
- AC-09.5: có tra cứu bù khi mất webhook; lỗi GHN không xóa tiền hoặc trạng thái đơn.
- AC-09.6: raw status chưa biết được lưu và đánh dấu chưa ánh xạ, không suy ra giao thành công.
- AC-09.7: callback thật cần HTTPS công khai và cấu hình staging. Fixture mô phỏng phải ghi rõ nguồn trên demo, không coi là callback thật.

## UC-10 — Theo dõi, hủy và hết hạn

- Tác nhân: người mua, chủ shop, hệ thống.
- Luồng: xem lịch sử, yêu cầu hủy, xác minh chưa bàn giao, hủy vận đơn nếu có, chốt hủy và nghĩa vụ giải phóng/hoàn tiền.
- AC-10.1: hết hạn chờ thanh toán đóng đơn/giải phóng; IPN muộn xử lý theo UC-07.
- AC-10.2: hủy vận đơn chưa xác nhận thì yêu cầu còn đang xử lý, không hiển thị đã hủy.
- AC-10.3: sau bàn giao không hủy tức thì; đi qua xử lý ngoại lệ giao hàng/khiếu nại.
- AC-10.4: chi tiết đơn hiển thị riêng trạng thái Order, Payment, Shipment và Refund.

## UC-11 — Khiếu nại và hoàn tiền

- Tác nhân: người mua, chủ shop, admin.
- Luồng: khách mở khiếu nại trong hạn, chọn dòng/số lượng/lý do/ảnh; shop phản hồi; admin duyệt/từ chối có lý do.
- AC-11.1: chỉ người mua được mở; kiểm tra cửa sổ từ giao thành công đã xác nhận. Tự hoàn tất không được bỏ qua claim đã mở hợp lệ.
- AC-11.2: hoàn toàn phần/một phần được backend kiểm tra, không quá phần tiền còn được hoàn.
- AC-11.3: hạn mức tính cả refund đang xử lý, không để request đồng thời hoàn vượt tiền thu.
- AC-11.4: refund timeout được tra theo tham chiếu trước gửi lại; chỉ đánh dấu đã hoàn khi có xác nhận phù hợp.
- AC-11.5: quyết định duyệt nhưng refund còn chờ phải hiển thị riêng; chưa đóng nghĩa vụ tiền.
- AC-11.6: hàng hư không tự nhập kho bán được; thu hồi vật lý nếu có ghi riêng.

## UC-12 — Hoàn tất và đối soát mô phỏng

- Tác nhân: người mua, hệ thống, admin.
- Luồng: khách xác nhận nhận hàng; hết cửa sổ khiếu nại; kiểm tra tranh chấp/nghĩa vụ; hoàn tất và tính khoản phải trả shop.
- AC-12.1: giao thành công/xác nhận nhận không đóng sớm quyền khiếu nại đã đề xuất; chỉ hoàn tất khi hết cửa sổ và không còn nghĩa vụ mở.
- AC-12.2: tiền hàng, cước khách trả, cước GHN, hoa hồng và tiền hoàn có dòng riêng; mức hoa hồng có snapshot.
- AC-12.3: đề xuất hoa hồng tính trên tiền hàng giữ lại sau hoàn; shop nhận tiền hàng giữ lại trừ hoa hồng; sàn đối soát cước khách trả/cước GHN.
- AC-12.4: đối soát lặp không ghi tiền lần hai; nhãn mô phỏng rõ ràng, không tuyên bố chuyển khoản thật.

## UC-13 — Review sau mua

- Tác nhân: người mua, chủ shop, admin.
- Luồng: khách đã nhận hàng chấm sao/viết nội dung/thêm ảnh, được sửa theo chính sách; shop phản hồi; admin ẩn có lý do.
- AC-13.1: Feedback xác minh qua Commerce; chưa mua/chưa giao thành công không được review.
- AC-13.2: một review/dòng đơn bằng ràng buộc duy nhất; request đồng thời không tạo trùng.
- AC-13.3: refund không tự xóa review hợp lệ; review bị ẩn không tính sao công khai.
- AC-13.4: MongoDB lưu nội dung/metadata; thống kê sao có thể tính lại sau sửa/ẩn hoặc sự kiện lặp.

## UC-14 — Hỏi đáp sản phẩm

- Tác nhân: người dùng đăng nhập, chủ shop, admin.
- Luồng: hỏi sản phẩm, shop trả lời, kiểm duyệt khi cần.
- AC-14.1: không cần đã mua; chỉ chủ shop tương ứng trả lời với danh nghĩa shop.
- AC-14.2: hỏi đáp và phản hồi review là kiểu nội dung riêng, không được tính nhầm vào điểm sao.
- AC-14.3: giới hạn câu hỏi và câu trả lời, chưa có cây bình luận vô hạn.

## UC-15 — Ảnh Cloudinary

- Tác nhân: chủ tài nguyên và backend.
- Luồng: cấp quyền upload, upload, xác minh metadata rồi gắn avatar/sản phẩm/review/khiếu nại.
- AC-15.1: kiểm tra loại/kích thước/số ảnh/chủ sở hữu; không tin `public_id` hoặc metadata từ client mà chưa xác minh.
- AC-15.2: upload thành công không tự gắn ảnh vào đối tượng; ảnh mồ côi được dọn sau.
- AC-15.3: thay/xóa có retry, không xóa ảnh còn được tham chiếu; secret chỉ ở backend.

## UC-16 — Quản trị và thông báo

- Tác nhân: admin, tài khoản nhận thông báo.
- Luồng: duyệt shop, kiểm duyệt, xem tiền/đơn, xử lý claim, xem audit; người dùng xem/đánh dấu đọc thông báo.
- AC-16.1: admin không sửa trạng thái đã trả/đã giao để bỏ qua xác minh provider.
- AC-16.2: thao tác quan trọng có tác nhân/thời gian/lý do/correlation ID.
- AC-16.3: lỗi notification không rollback đơn/tiền; retry không tạo thông báo trùng cùng sự kiện/người nhận.

## Kịch bản nghiệm thu portfolio

| Mã | Kịch bản | Kết quả |
|---|---|---|
| DEMO-01 | Shop, sản phẩm/lô, checkout, VNPAY, GHN, nhận hàng, review | Truy vết ID, snapshot và lịch sử xuyên luồng |
| DEMO-02 | Hai khách tranh tồn cuối | Một reservation thành công, tồn không âm |
| DEMO-03 | Checkout/IPN lặp | Một đơn hiệu lực, một lần ghi tiền |
| DEMO-04 | IPN sau hết hạn giữ | Ghi tiền, không tự giao, có refund cần xử lý |
| DEMO-05 | Timeout tạo GHN | Không kiện trùng; có đối chiếu kết quả |
| DEMO-06 | Callback lặp/sai thứ tự | Không tự lùi trạng thái, còn lịch sử |
| DEMO-07 | Hàng hỏng, hoàn một phần | Đúng hạn mức, không tự tăng kho, đối soát điều chỉnh |
| DEMO-08 | Truy cập chéo shop/review trái quyền | Backend từ chối, không lộ dữ liệu riêng |
| DEMO-09 | Dừng service sau commit trước gửi sự kiện | Outbox gửi lại, consumer chống trùng, luồng tiếp tục |

Khung Identity có ./mvnw verify từ repo backend, cần PostgreSQL local và .env. Test kết nối/migration chưa phải nghiệm thu DEMO nghiệp vụ; từng DEMO sẽ có kiểm thử khi triển khai. Sandbox thật và fixture phải phân biệt rõ.
