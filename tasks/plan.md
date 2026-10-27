# Agora — Kế hoạch triển khai

## M3b ngày27/10/2026 — reservation Catalog

Canonical tracker `slice-3-reservations`; [thiết kế](slice-3-reservations.md), [proto](../contracts/proto/agora/catalog/v1/catalog_reservations.proto), [guide/proof](../docs/demo-reservations.md). M3b đã nghiệm thu local27/10: root274tests đạt lúc10:14:28+07:00 (13Identity/59Merchant/178Catalog/24Commerce), review actual core/RPC CLEAR. Ba finding core và một lỗi phân loại RPC đều đã sửa với RED/GREEN; chi tiết trong guide. Chỉ hoàn thiện M3b, chưa mở M3c checkout/GHN hoặc M3dVNPAY. Mọi trạng thái chưa reservation trong mốc M2/M3a dưới đây là lịch sử.

## Cập nhật M3a ngày 26/10 — giỏ hàng đã có implementation

Canonical tracker hiện tại slice-3-cart; [plan M3a](slice-3-cart.md), [OpenAPI giỏ](../contracts/openapi/commerce-cart.v1.openapi.json), [CatalogOffers proto](../contracts/proto/agora/catalog/v1/catalog_offers.proto), [demo giỏ](../docs/demo-cart.md). Commerce là service thứ tư: HTTP 8084, PostgreSQL agora_commerce/schema commerce/V1; Catalog thêm optionalgRPC9093 và library catalog-contract. Giỏ đăng nhập nhiều shop/tối đa 20 SKU/1–99 gói mỗi dòng; GET current offers, PUT tuyệt đối, DELETE độc lập Catalog. cartId ràng buộc account, auth/CSRF trước replay, version/key7 ngày và preflight+row-lock replay; refresh lỗi chỉ giữ refs tối thiểu để xóa. Không giữ tồn/reservation/checkout.

Main xác minh root verify ngày **26/10/2026 lúc 10:55:16 +07:00: BUILD SUCCESS, 226 tests = 13 Identity + 59 Merchant + 130 Catalog + 24 Commerce; 0 fail/error/skip**. Commerce 24 = 6 domain + 15 API + 3 service; Catalog thêm 17 = 12 gRPC + 4 SQL + 1 configuration. Đây là bằng chứng backend do main cung cấp; **M3a đã nghiệm thu local ngày 26/10: frontend 136 unit, 243 fixture production, 5 normal live và 1 cart/provider live đạt; hai lỗi review đã sửa và re-review CLEAR**.

M3b Catalog reservation FEFO, M3c Commerce checkout một shop/GHN quote, M3d Payment/VNPAY vẫn là bước sau theo plan M3a. Chưa mở implementation các phần đó; không tạo checklist/tracker song song. Các mốc M1/M2 bên dưới là lịch sử theo ngày.

Trạng thái ngày 24/10/2026 sau resume 15:10: M1 tài khoản/mở shop/admin review, Catalog M2a và ảnh M2b1 đã nghiệm thu local. M2b2 công bố/ẩn đã nghiệm thu local ngày 24/10/2026 khoảng 15:40 +07:00; P2 đã sửa/review chấp nhận, normal live 4/4 và media/publication live 2/2 PASS; hai asset mới đã xác minh cleanup DB/provider; frontend build/lint/typecheck, 86 unit và 144 fixture sau sửa đã PASS, cleanup hai asset trước pause đã xác minh. Người dùng đã commit M2b2 backend `ff27aaa` và frontend `875863b`; không suy ra đã push hoặc CI đã chạy. [ADR-0002](../docs/decisions/0002-separate-identity-merchant.md) thay lựa chọn gộp service; [ADR-0003](../docs/decisions/0003-postgresql-only.md) chốt PostgreSQL cho mọi service. Không yêu cầu duyệt lại lựa chọn thông thường; cần hỏi khi phát sinh tài khoản ngoài/chi phí/publish ngoài phạm vi.

## Cập nhật M2c1 sau resume20:23 ngày24/10

M2c1 quản lý lô hàng đã nghiệm thu local ngày24/10/2026 sau resume20:23: backend150 tests (16:03:17, source không đổi), frontend98 unit,177 browser fixture production và5 live qua ba service đều đạt; build/lint/typecheck đạt, review cuối không còn blocker trong phạm vi sửa. Proof này là M2c1; chưa có reservation/FEFO allocation hoặc checkout.

Canonical tracker `slice-2-inventory`: thiết kế/contract, backend, UI/live, docs và final checks đã hoàn thành local. [Plan inventory](slice-2-inventory.md), [demo](../docs/demo-inventory.md), [proof](../../frontend/docs/catalog-inventory-proof.md). Không coi M2 hoặc UC-04 toàn bộ hoàn tất khi chưa có reservation/FEFO/checkout. Các bằng chứng M2b2 và fixture cũ bên dưới là lịch sử.

## Cập nhật M2c2 sau resume25/10 — implementation, đã nghiệm thu local

Canonical tracker `slice-2-storefront`, [plan](slice-2-storefront.md). Đã triển khai đúng hai GET public `/api/v1/products` và `/api/v1/products/{productId}` theo [contract riêng](../contracts/openapi/catalog-storefront.v1.openapi.json). Chỉ PUBLISHED+attached UPLOADED photo+shop hiện ACTIVE; public shop chỉ `{id,name}` tối đa120codepoints, omit legacy blank. Không PII/version/audit/upload intent, mã/ngày lô hoặc số lượng tồn; [allowlist/trường private](../docs/architecture/catalog-security.md#storefront-m2c2--public-projection).

Browse đọc21candidate theo created_at DESC,id DESC, chỉ xét20với Merchant tối đa4batch×5ID. Cursor theo candidate cuối đã xét; items có thể rỗng nhưng nextCursor vẫn còn, UI phải cho **Xem tiếp**. Stock chỉ boolean từ lô ACTIVE, expires_on>hôm nay Asia/Ho_Chi_Minh và available>0, không hứa mua/giao. Không reservation/FEFO allocation, checkout, GHN/VNPAY, review/Q&A hoặc profile shop đầy đủ.

Main báo root verify **25/10/2026 20:50:38 +07:00 BUILD SUCCESS,185tests=13Identity+59Merchant+113Catalog,0fail/error/skip**. Catalog mới20=4query+13API+3service, gồm HTTP DB outage503safe/SQL syntax500. Frontend/live/review của M2c2 đã hoàn tất; UC-05 còn review/Q&A chưa triển khai. Bằng chứng M2c1/M2b2 được giữ theo ngày. Docs worker chỉ cập nhật tài liệu; main cung cấp final proof theo [plan storefront](slice-2-storefront.md), không thêm gate hoặc checklist song song. [Demo storefront](../docs/demo-storefront.md).

## 1. Nguồn và tracker

[Use case](../docs/product/use-cases.md), [state machines](../docs/product/state-machines.md), [kiến trúc](../docs/architecture/architecture-overview.md), [cấu trúc](../docs/architecture/project-structure.md), [dữ liệu](../docs/architecture/data-model.md), [OpenAPI](../contracts/openapi/identity-merchant.v1.openapi.json).

Task canonical: claude-mem work_state; list hiện tại `slice-3-cart`, các list trước gồm `slice-2-storefront`, `slice-1-onboarding`, `slice-2-catalog` và `slice-2-inventory`. Mỗi task có acceptance, verify, dependencies và files trong tracker; không tạo tasks/todo.md trùng lặp. Bảng dưới là chỉ mục có thể đọc khi không có plugin, không phải trạng thái task thay thế tracker.

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

## 3. Lệnh backend và frontend hiện có

Backend dùng PostgreSQL 16 native local; .env được Spring import từ cwd backend. Test tích hợp dùng PostgreSQL Docker biệt lập. Frontend đã có npm lockfile và các script dưới đây; xem [hướng dẫn demo](../docs/demo-onboarding.md) để chạy hai service và cấu hình ADMIN test.

```bash
# Từ repo backend
./mvnw -B -ntp -Dagora.test.postgres-image=postgres:16 verify
pg_isready -h 127.0.0.1 -p 5432
./mvnw -pl services/identity-service spring-boot:run

# Từ repo frontend
npm ci --ignore-scripts
npm run lint
npm run typecheck
npm run build
npm run test:e2e
```

M1 đã kiểm chứng 46 backend tests, 31 frontend unit, 45 browser fixture và 3 live qua hai backend thật; có Testcontainers và Playwright. Đây là bằng chứng local ngày 23/10, không phải GitHub Actions run. Đây là lịch sử M1; ngoại lệ audit đã gỡ ngày 23/10 lúc 22:35 +07:00 sau khi advisory được rút lại, native audit lúc đó exit 0/zero vulnerabilities. Main đã báo audit native mới ngày 24/10 exit 0/zero vulnerabilities; xem [tài liệu bảo mật frontend](../../frontend/docs/security/dependency-audit.md), giữ riêng lịch sử hai ngày.

## 4. Catalog hiện tại và các mốc sau lát cắt 1

M2 đã có [sản phẩm/SKU M2a](slice-2-catalog.md), [ảnh M2b1](slice-2-media.md) và implementation [công bố/ẩn M2b2](slice-2-publication.md). M2b2 đã có 19 regression revalidation trong full 144 fixture PASS sau resume, P2 đã sửa/review chấp nhận và normal/media live mới đã PASS; cleanup hai asset mới cũng đã đạt DB DELETED/0 relation và exact provider lookup all_missing=true/exit 0; M2b2 đã nghiệm thu local khoảng 15:40 +07:00 ngày 24/10. [Demo Catalog](../docs/demo-catalog.md) và [proof publication frontend](../../frontend/docs/catalog-publication-proof.md) ghi hành vi và giới hạn.

Root verify lịch sử 24/10 lúc 10:57:21 +07:00 đạt **133 tests (13 Identity + 44 Merchant + 76 Catalog)**. Frontend trước pause đạt **86 unit, 125 fixture production, 4 normal live, 2 media/publication live**. Sau resume, production build/lint/typecheck PASS, **86 unit và 144 fixture production PASS (23.8s)**, gồm **19 regression revalidation mới**; focused trước đó 18 GREEN sau 14 expected RED trên build cũ. Audit native mới ngày 24/10 exit 0/zero vulnerabilities. Main exact lookup hai asset publication/media tương thích trước pause khoảng 15h: all_missing=true, exit 0. Scan 216 Git-visible files/0 actual-secret matches; env mode 600 và contract digest/57–51 refs hợp lệ. Final static review Heisenberg clear, không còn code blocker; P2 fixed/review accepted. Normal live **4/4 PASS (6.7s)**, output `publication-resume-normal-live-green-20261024/`, gồm shared-session logout/re-login/editor-list clearing/recovery và giữ giá dirty khi focus. Test-only fix chờ DELETE logout 204 thay vì nav link luôn hiện, production code không đổi. Media/publication live **2/2 PASS (8.2s)**, output `publication-resume-media-live-20261024/`. M2b2 đã nghiệm thu local sau main xác minh hai asset mới DELETED/0 relation, final DRAFT/version 2 và HIDDEN/version 7; independent exact provider lookup 2 asset all_missing=true/exit 0. Runtime cleanup đã xác nhận: Next và ba backend đã dừng, backend graceful exit 130; container `--rm` đã bị xóa sau kiểm ID/label và docker stop exit 0, các cổng 3000/8081/8082/8083/9091/9092 đã đóng. `.env.live-test` ignored mode 600 hiện STALE; không giữ demo/named volume, chạy lại cần fixture/ADMIN mới. Native database giữ nguyên. Main xác nhận final secret scan sau cập nhật docs: 216 Git-visible files/0 actual-secret matches, cả hai env mode 600; 34 Markdown files/207 local links/0 broken, code fences cân bằng. Không có production hoặc GitHub Actions run claim.

M2c1 nhập lô/khóa/mở đã triển khai và qua gate local; M2c2 storefront/public browse/detail đã triển khai, đã nghiệm thu local ngày 25/10/2026. Reservation/FEFO allocation và checkout chưa triển khai. PUBLISHED/inStock không chứng minh mua/giao được; bảng mốc bên dưới vẫn là đích, không phải nghiệm thu toàn M2.

| Mốc | Luồng hoàn chỉnh | Điều kiện chú ý |
|---|---|---|
| M2 | Shop hoạt động đăng sản phẩm, ảnh Cloudinary và lô; khách tìm/xem | Thêm Catalog, gRPC auth/merchant, FE catalog, kiểm tra quyền ảnh |
| M3 | Giỏ nhiều shop; checkout một shop, GHN báo giá, giữ tồn, tạo đơn | Kiểm tra GHN staging/địa chỉ sớm; tồn cuối và hết hạn |
| M4 | VNPAY sandbox, GHN tạo/đồng bộ, nhận hàng | Tiền muộn, timeout, webhook, RabbitMQ/outbox, refund |
| M5 | Review/Q&A/reply PostgreSQL, claim/refund, đối soát mô phỏng | Feedback sở hữu agora_feedback/Flyway; unique dòng đơn, rating 1–5, quyền đã mua, hạn mức hoàn, hàng hỏng không nhập tự động |
| M6 | Hoàn thiện Identity/quản trị, ảnh avatar, demo/CI/README portfolio | Khôi phục mật khẩu có token dùng một lần; bổ sung tính năng còn lại từ UC |

Đây là mốc định hướng, chưa phải task đã chia nhỏ hay chức năng đã triển khai. Chỉ lập task chi tiết khi mốc gần nhất đã rõ hợp đồng.

Điều chỉnh persistence theo ADR-0003 không đổi thứ tự M2–M6 hoặc sáu service. Bỏ công việc cài MongoDB/driver/test container MongoDB; M5 dùng JDBC/Flyway/PostgreSQL Testcontainers. Chưa có dữ liệu Feedback nên không có bước chuyển dữ liệu. Người dùng đã có tài khoản Cloudinary; chỉ cần cấu hình secret local khi triển khai ảnh, không xin lại tài khoản.

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
