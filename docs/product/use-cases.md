# Agora — Use case MVP v0.1

## M3b — nền tảng giữ tồn cho UC-06

Catalog đã có core và RPC Hold/Get/Release cho checkout một shop, FEFO/TTL15phút, giữ đủ toàn bộ hoặc rollback, permanent checkout identity và chống release lặp. Tại nghiệm thu M3b 27/10 lúc10:14:28+07:00 đây là nền tảng nội bộ, chưa có preview/recovery/order snapshot hoặc nút checkout. M3c preview/typed accepted hold/durable attempt/orders đã nghiệm thu local ngày27/10/2026 trong phạm vi checkout một shop; [demo/evidence](../demo-checkout.md). [Plan](../../tasks/slice-3-reservations.md), [guide và trạng thái nghiệm thu](../demo-reservations.md).

Phạm vi hiện hành ngày 27/10/2026 theo [ADR-0004](../decisions/0004-order-and-payment-only.md): chỉ đặt hàng/thanh toán, không vận chuyển hoặc nhận tại shop. Tổng chỉ tiền hàng. [M3c](../../tasks/slice-3-checkout.md) có implementation preview/typed accepted hold/durable attempt/orders đã nghiệm thu local ngày27/10/2026; M3d mới có VNPAY/PAID. Giữ nguyên sáu service và dữ liệu legacy Merchant/contact/pickup_address/quy cách SKU, không rewrite migration hoặc phá form/API hiện có.

Trạng thái: bản nháp để review cùng [bản đồ context](capability-map.md). Các mã UC/AC phục vụ truy vết tới API và kiểm thử sau; chưa là kế hoạch hay danh sách task triển khai.

## UC-01 — Tài khoản và hồ sơ

- Tác nhân: khách, người mua, chủ shop, admin.
- Luồng: đăng ký, đăng nhập/đăng xuất, khôi phục mật khẩu, sửa hồ sơ/avatar.
- AC-01.1: định danh trùng không tạo account mới; mật khẩu không xuất hiện trong response/log.
- AC-01.2: không truy cập được hồ sơ/đơn người khác bằng đổi ID; phiên hết hiệu lực bị backend từ chối.
- AC-01.3: sửa avatar không cho phép gắn hoặc xóa ảnh thuộc người khác.

## UC-02 — Mở và quản lý shop

- Tác nhân: người dùng đăng nhập, admin.
- Luồng: khai báo hồ sơ shop, gửi duyệt; admin duyệt hoặc từ chối có lý do; shop hoạt động được đăng bán.
- AC-02.1: shop chưa duyệt/bị khóa không nhận checkout mới. Đơn cũ vẫn được xử lý hoặc hoàn tiền theo quyền thích hợp.
- AC-02.2: chỉ chủ shop/admin phù hợp được sửa thông tin shop; hồ sơ/contact/pickup_address đã có giữ như legacy, không dùng cho checkout.
- AC-02.3: không yêu cầu tài khoản/liên kết GHN hoặc tích hợp địa chỉ cho shop mới.
- AC-02.4: sửa hồ sơ không thay snapshot đơn cũ.

## UC-03 — Danh mục, sản phẩm và SKU

- Tác nhân: chủ shop; admin quản lý danh mục/kiểm duyệt.
- Luồng: tạo nháp, khai báo thông tin, upload ảnh, tạo SKU/giá, công bố hoặc ẩn.
- Dữ liệu ngành hàng: xuất xứ khai báo, cơ sở sản xuất, quy cách, nhóm sản phẩm, tình trạng tươi/đông lạnh/khô/chế biến khi phù hợp, bảo quản và chứng nhận nếu có.
- AC-03.1: SKU có mã, giá hợp lệ, đơn vị bán, khối lượng gói; metadata khối lượng/kích thước hiện có giữ nguyên như legacy với đơn vị rõ, không dùng để tính kiện/phí trong checkout mới.
- AC-03.2: chứng nhận shop upload không tự được gắn nhãn đã xác minh; quyết định xác minh có người duyệt/audit.
- AC-03.3: hàng ẩn/khóa không checkout mới; snapshot đơn cũ vẫn đọc được.
- AC-03.4: chỉ chủ shop quản lý hàng của mình; tiền theo số nguyên VND trong quy ước MVP, không số thực nhị phân.

## UC-04 — Lô hàng và tồn kho

- Tác nhân: chủ shop, checkout, tác vụ hệ thống.
- Luồng: nhập lô theo SKU, khai báo số lượng và ngày tháng phù hợp; giữ hàng từ lô đủ điều kiện, ưu tiên hết hạn trước.
- AC-04.1: request đồng thời không làm tổng giữ vượt tồn khả dụng hoặc consume hai lần.
- AC-04.2: thiếu một SKU không để lại giữ hàng các SKU khác trong cùng thao tác thất bại.
- AC-04.3: không giữ hoặc consume lô hết hạn/bị khóa/không đủ điều kiện theo chính sách.
- AC-04.4: hết hạn hoặc hủy chỉ giải phóng một lần; request lặp cùng khóa nghiệp vụ trả cùng kết quả.
- AC-04.5: khóa lô chặn giữ/consume mới. Nếu tiền đã nhận mà consume chưa xác nhận, giữ đang xử lý để đối chiếu hoặc refund tài chính.
- AC-04.6: M3d HELD → CONSUMED giảm held và on_hand cùng transaction, chống lặp; release không đổi on_hand. Không tạo SHIPPED giả; allocated legacy giữ nguyên.

## UC-05 — Tìm và xem sản phẩm

Implementation M2c2 ngày25/10/2026: đã có hai GET public `/api/v1/products` và `/api/v1/products/{productId}`, đã nghiệm thu local ngày 25/10/2026. Chỉ PUBLISHED có attached UPLOADED photo và shop hiện ACTIVE; hết tồn vẫn xem được. Merchant chỉ trả `{id,name}`, tên tối đa120codepoints và omit legacy blank. Browse đọc21candidate, chỉ xét20qua tối đa4batch×5ID; cursor theo candidate cuối đã xét nên trang rỗng vẫn có thể còn `nextCursor`, cần **Xem tiếp**. Stock product/SKU chỉ boolean từ lô ACTIVE, expires_on>hôm nay Asia/Ho_Chi_Minh và available>0, không cam kết mua/giao. Không trả PII, version/audit/upload intent, mã/ngày lô hoặc số lượng tồn; [allowlist và trường private](../architecture/catalog-security.md#storefront-m2c2--public-projection). [Demo/proof backend](../demo-storefront.md); frontend/live/review đã đạt, xem bằng chứng cuối trong demo storefront. Tại nghiệm thu M2c2 ngày25/10 review/Q&A, profile shop đầy đủ, reservation và checkout chưa triển khai; M3b reservation đã nghiệm thu27/10, UC-05 toàn bộ chưa nghiệm thu.

- Tác nhân: mọi người dùng.
- Luồng: tìm tên, lọc danh mục/giá/xuất xứ/shop; xem sản phẩm, gian hàng, review và hỏi đáp.
- AC-05.1: phân trang/sắp xếp có quy tắc ổn định; hàng ẩn/khóa không hiển thị như có thể mua.
- AC-05.2: giá theo gói, khối lượng, bảo quản, thông tin nguồn gốc và shop hiển thị rõ.
- AC-05.3: tìm kiếm/tổng sao có thể trễ; checkout xác minh lại giá và tồn kho.

## UC-06 — Giỏ hàng và checkout một shop

Implementation M3a ngày 26/10/2026: Commerce đã có GET giỏ/PUT quantity tuyệt đối/DELETE dòng theo [contract riêng](../../contracts/openapi/commerce-cart.v1.openapi.json). Giỏ theo account lưu PostgreSQL, nhiều shop/tối đa 20 SKU/1–99 gói mỗi dòng. GET current offers; mất public giữ refs với offer:null, hết tồn inStock=false và PUT mới 409. cartId ràng buộc account, mutation CSRF/version/key7 ngày, replay trả version gốc và cần GET mới. DELETE độc lập Catalog, vẫn cần Identity/CSRF/version; refresh 503 chỉ giữ refs tối thiểu để xóa, không dùng giá/tên cũ. Giỏ không giữ tồn hoặc checkout. Root 226 tests đạt theo main 10:55:16 +07:00; giỏ M3a đã nghiệm thu local ngày 26/10; checkout chưa triển khai nên chưa nghiệm thu toàn UC-06. [Demo giỏ](../demo-cart.md), [plan](../../tasks/slice-3-cart.md).

- Tác nhân: người mua đăng nhập.
- Tiền điều kiện checkout: buyer đăng nhập, shop ACTIVE, các SKU được chọn cùng shop và thuộc cart/version hiện tại. Giỏ M3a có thể chứa nhiều shop.
- Luồng: chọn hàng một shop, xem preview server tính, xác nhận; giữ tồn FEFO đúng giá/version đã chấp nhận rồi tạo đơn AWAITING_PAYMENT và xem snapshot.
- AC-06.1: backend từ chối checkout trộn shop; 1–20 SKU khác nhau/1–99 gói mỗi dòng.
- AC-06.2: backend tính giá/tổng tiền hàng. Preview hết hạn 5 phút hoặc sản phẩm thay đổi thì xem/xác nhận lại; không âm thầm đổi giá.
- AC-06.3: không đủ điều kiện/không đủ tồn thì không tạo đơn chờ thanh toán. Không form địa chỉ/kiện/phí/GHN/pickup, không shippingFee=0 giả.
- AC-06.4: cùng actor/key/body chỉ một ý định/đơn/reservation; payload khác bị từ chối. Preview dùng một lần; kết quả chưa rõ giữ key/preview để recover, không tự tạo key mới.
- AC-06.5: đơn lưu tên shop/sản phẩm, SKU/quy cách/giá/quantity/tổng, thời hạn giữ và checkout/reservation identity; không logistics snapshot.
- AC-06.6: commit attempt trước RPC, lease fencing/recovery sau crash; validated HELD từ Catalog tạo Order bất kể đồng hồ Commerce; FAILED chỉ từ typed closed/validated RELEASED, expiry khi đọc tính bằng Commerce DB. Không giữ hàng vĩnh viễn hoặc báo FAILED giả khi timeout chưa rõ.
- AC-06.7: auth/CSRF trước replay. Dọn dòng giỏ cùng commit Order chỉ nếu cart version chưa đổi; nếu đã đổi giữ nguyên giỏ và cartCleared=false. Snapshot đơn cũ vẫn đọc khi offer không còn public.
- AC-06.8: M3c chỉ tạo/xem AWAITING_PAYMENT/EXPIRED, không nút thanh toán hoặc dữ liệu PAID giả; Payment thuộc M3d.

## UC-07 — Thanh toán VNPAY sandbox

- Tác nhân: người mua, VNPAY, hệ thống.
- Luồng: tạo payment attempt hợp lệ, chuyển khách tới cổng, nhận IPN, xác minh và lưu kết quả, thông báo Commerce xử lý tiếp.
- AC-07.1: đối chiếu chữ ký, tham chiếu, merchant, số tiền và kết quả trước khi ghi nhận tiền.
- AC-07.2: Return URL chỉ hiển thị/truy vấn backend; đóng tab hoặc sửa URL không làm đơn thành đã trả tiền.
- AC-07.3: IPN lặp không ghi tiền hoặc tạo hiệu lực nghiệp vụ hai lần; chỉ phản hồi sau khi lưu kết quả bền vững.
- AC-07.4: tiền tới muộn vẫn ghi nhận; đơn EXPIRED/CANCELLED không tự mở lại hoặc hồi sinh reservation. Yêu cầu hoàn tiền được theo dõi đến kết quả.
- AC-07.5: hai attempt cùng thành công chỉ có một khoản áp vào đơn; khoản thu thừa có yêu cầu hoàn riêng, có audit.
- AC-07.6: timeout không chứng minh thất bại; có tra cứu/đối chiếu theo tham chiếu, hiển thị đang xác minh khi chưa rõ.

## UC-08 / UC-09 — Loại khỏi phạm vi hiện hành

Các use case cũ về xác nhận/đóng gói và vận chuyển GHN bị thay thế bởi ADR-0004 ngày 27/10/2026. Không còn acceptance về Fulfillment, shipment, pickup, giao nhận hoặc trạng thái DELIVERED/COMPLETED. Giữ mã để không tái sử dụng nhầm tham chiếu cũ; không phải công việc đang chờ làm.

## UC-10 — Theo dõi, hủy và hết hạn

- Tác nhân: buyer, hệ thống; mọi quyền quản trị phải được xác minh.
- Luồng đích: xem đơn của mình; AWAITING_PAYMENT chuyển PAID terminal khi tiền hợp lệ và consume xác nhận, hoặc EXPIRED/CANCELLED khi được chốt an toàn.
- AC-10.1: M3c đơn hết thời hạn reservation thành EXPIRED; snapshot vẫn giữ. M3d phải phối hợp payment/consume trước hết hạn, không hủy mù khoản thu chưa rõ.
- AC-10.2: chỉ hủy đơn còn AWAITING_PAYMENT theo điều kiện và quyền; giải phóng HELD một lần. CANCELLED thuộc phần mở rộng sau M3c, không tuyên bố đã có API hủy.
- AC-10.3: PAID không chuyển thêm trạng thái mua; refund tài chính có tiến trình riêng và không tự tăng tồn.
- AC-10.4: snapshot/current Order, Payment và Refund trình bày riêng; timeout thể hiện đang đối chiếu, không giả thất bại/thành công.

## UC-11 — Hoàn tiền tài chính

QualityClaim sau giao, cửa sổ chất lượng/ảnh claim và return shipment ngoài phạm vi theo ADR-0004. Refund cho khoản thu muộn/trùng hoặc không thể áp vào đơn vẫn bắt buộc ở M3d.

- Tác nhân: Payment, Commerce, admin có quyền đối chiếu.
- AC-11.1: tiền tới đơn EXPIRED/CANCELLED hoặc khoản thu thừa được ghi nhận và tạo nghĩa vụ refund bền vững; không hồi sinh đơn/giữ tồn.
- AC-11.2: mỗi refund gắn giao dịch thu cụ thể và tham chiếu provider duy nhất.
- AC-11.3: tổng refund thành công + hạn mức đang giữ cho pending không vượt captured, kể cả đồng thời.
- AC-11.4: timeout giữ UNKNOWN và tra cứu trước gửi lại; chỉ báo đã hoàn khi có xác nhận phù hợp.
- AC-11.5: tạo nghĩa vụ hoàn chưa có nghĩa đã hoàn; lưu audit/kết quả để retry/đối chiếu. Refund không tự phục hồi tồn.

## UC-12 — Đối soát mô phỏng

- Tác nhân: hệ thống, admin.
- Luồng: đối chiếu tiền thu, khoản áp vào đơn PAID và refund; tính khoản shop ở mức mô phỏng, không thêm mốc nhận hàng/hoàn tất.
- AC-12.1: PAID terminal của luồng mua không đóng nghĩa vụ refund đang chờ; khoản thu thừa không tính doanh thu shop.
- AC-12.2: tiền hàng, refund và hoa hồng mô phỏng có dòng riêng, snapshot chính sách; không dòng cước.
- AC-12.3: đề xuất hoa hồng trên tiền hàng giữ lại sau refund; không quyết toán khoản chưa đối chiếu xong.
- AC-12.4: chạy lặp không ghi tiền hai lần; nhãn mô phỏng rõ, không tuyên bố chuyển khoản thật.

## UC-13 — Review sau mua

- Tác nhân: người mua, chủ shop, admin.
- Luồng: buyer có dòng đơn PAID chấm sao/viết nội dung/thêm ảnh, được sửa theo chính sách; shop phản hồi; admin ẩn có lý do.
- AC-13.1: Feedback xác minh buyer sở hữu dòng đơn PAID qua Commerce; không đủ điều kiện thì từ chối. Không đợi DELIVERED/COMPLETED.
- AC-13.2: một review/dòng đơn bằng ràng buộc duy nhất; request đồng thời không tạo trùng.
- AC-13.3: refund không tự xóa review hợp lệ; review bị ẩn không tính sao công khai.
- AC-13.4: PostgreSQL của Feedback lưu nội dung và metadata, unique order_line_id và CHECK rating 1–5; thống kê sao có thể tính lại sau sửa/ẩn hoặc sự kiện lặp. JSONB chỉ dùng cho metadata tùy chọn, không thay constraint nghiệp vụ.

## UC-14 — Hỏi đáp sản phẩm

- Tác nhân: người dùng đăng nhập, chủ shop, admin.
- Luồng: hỏi sản phẩm, shop trả lời, kiểm duyệt khi cần.
- AC-14.1: không cần đã mua; chỉ chủ shop tương ứng trả lời với danh nghĩa shop.
- AC-14.2: hỏi đáp và phản hồi review là kiểu nội dung riêng, không được tính nhầm vào điểm sao.
- AC-14.3: giới hạn câu hỏi và câu trả lời, chưa có cây bình luận vô hạn.

## UC-15 — Ảnh Cloudinary

- Tác nhân: chủ tài nguyên và backend.
- Luồng: cấp quyền upload, upload, xác minh metadata rồi gắn avatar/sản phẩm/review.
- AC-15.1: kiểm tra loại/kích thước/số ảnh/chủ sở hữu; không tin `public_id` hoặc metadata từ client mà chưa xác minh.
- AC-15.2: upload thành công không tự gắn ảnh vào đối tượng; ảnh mồ côi được dọn sau.
- AC-15.3: thay/xóa có retry, không xóa ảnh còn được tham chiếu; secret chỉ ở backend.

## UC-16 — Quản trị và thông báo

- Tác nhân: admin, tài khoản nhận thông báo.
- Luồng: duyệt shop, kiểm duyệt, xem tiền/đơn, đối chiếu refund tài chính, xem audit; người dùng xem/đánh dấu đọc thông báo.
- AC-16.1: admin không sửa trạng thái đã trả để bỏ qua xác minh provider.
- AC-16.2: thao tác quan trọng có tác nhân/thời gian/lý do/correlation ID.
- AC-16.3: lỗi notification không rollback đơn/tiền; retry không tạo thông báo trùng cùng sự kiện/người nhận.

## Kịch bản nghiệm thu portfolio

| Mã | Kịch bản | Kết quả |
|---|---|---|
| DEMO-01 | Shop, sản phẩm/lô, checkout, VNPAY, PAID, review | Truy vết ID, snapshot và lịch sử xuyên luồng |
| DEMO-02 | Hai khách tranh tồn cuối | Một reservation thành công, tồn không âm |
| DEMO-03 | Checkout/IPN lặp | Một đơn hiệu lực, một lần ghi tiền |
| DEMO-04 | IPN sau hết hạn giữ | Ghi tiền, không hồi sinh đơn/hold, có refund cần xử lý |
| DEMO-05 | Timeout Hold/POST checkout | Cùng key/checkout identity; recover một đơn, không hold/đơn trùng |
| DEMO-06 | Callback lặp/sai thứ tự | Không tự lùi trạng thái, còn lịch sử |
| DEMO-07 | Hai attempt thu tiền, refund đồng thời/timeout | Một khoản áp vào đơn; hoàn không vượt captured, không tự tăng kho |
| DEMO-08 | Truy cập chéo shop/review trái quyền | Backend từ chối, không lộ dữ liệu riêng |
| DEMO-09 | Dừng service sau commit trước gửi sự kiện | Outbox gửi lại, consumer chống trùng, luồng tiếp tục |

Khung Identity có ./mvnw verify từ repo backend, cần PostgreSQL local và .env. Test kết nối/migration chưa phải nghiệm thu DEMO nghiệp vụ; từng DEMO sẽ có kiểm thử khi triển khai. Sandbox thật và fixture phải phân biệt rõ.

## Bằng chứng UC-06 M3c — đã nghiệm thu local ngày27/10/2026

[OpenAPI checkout](../../contracts/openapi/commerce-checkout.v1.openapi.json), [CatalogCheckout](../../contracts/proto/agora/catalog/v1/catalog_checkout.proto), [plan](../../tasks/slice-3-checkout.md), [demo/evidence](../demo-checkout.md). Source hiện có preview theo expiry5phút từ Catalog DB, confirm durable attempt/lease30giây/recovery10giây, typed reservation/closed, snapshot order và dọn giỏ có version guard. Main đã xác minh root303PASS (13Identity/59Merchant/191Catalog/40Commerce), actual Pauli/BanachCLEAR, frontend143unit/272productionfixture và real4app+Cloudinary1/1PASS cùng baseline5/5PASS, cleanup hoàn tất; exact evidence ở demo checkout. Nghiệm thu này chỉ cho phạm vi M3c. M3c không xác nhận thanh toán; các AC về tiền/consume vẫn thuộc bước sau.
