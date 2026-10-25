# M2c2 — Khám phá và xem sản phẩm công khai

Ngày24/10/2026, tiếp nối M2c1 đã commit backendb8d49fc/frontendec18369. Người dùng giao quyền quyết định và tiếp tục; không chờ duyệt lại lựa chọn thông thường. Canonical tracker `slice-2-storefront`, không tạo todo song song. Không stage/commit/push/deploy, không thay PostgreSQL native.

## Phạm vi và lựa chọn

Theo UC-05: khách chưa đăng nhập tìm tên, lọc category/minPriceVnd/maxPriceVnd/origin/shopId và xem chi tiết sản phẩm, ảnh, quy cách/giá theo gói, nguồn gốc/bảo quản và tên cửa hàng. Dùng Catalog hiện có, Merchant cung cấp public shop projection qua gRPC nội bộ. Chưa có review/Q&A, profile shop đầy đủ, giỏ, giữ hàng/FEFO allocation, checkout hoặc đảm bảo vận chuyển lạnh. UI không có nút mua giả.

Public chỉ trả Product PUBLISHED có ít nhất một ảnh attached tới uploadUPLOADED và shop hiện ACTIVE. Sản phẩm không có tồn vẫn xem được với nhãn hết tồn; mỗi SKU và card có inStock bool tính từ lô ACTIVE, expires_on>hôm nay Asia/Ho_Chi_Minh và on_hand-held-allocated>0. Không trả lượng tồn, lot IDs/ngày lô, version, audit, uploadintent, owner account, contactPhone/pickupAddress/reviewreason. Tên shop là dữ liệu public duy nhất ngoàiID; trạng thái account owner không tự đổi trạng thái shop, checkout sau này cần policy riêng. Không kiểm provider từng request hoặc suy ra chất lượng thực phẩm từ ngày.

Trang `/products`, detail `/products/{id}`. Home `/` chuyển tới `/products`; giữ `/account` sau login. Header thêm link **Khám phá**, tránh trùng link private **Sản phẩm**. Link tên shop mở browse lọc shopId, chưa thêm profile API riêng. Giữ house UI, không thêm dependency.

## Public HTTP contract

Contract riêng `catalog-storefront.v1.openapi.json`1.0.0, chỉ GET `/api/v1/products` và `/api/v1/products/{productId}`. Public read không cần cookie/CSRF; cookie sai không biến public read thành private authorization. Response no-store qua filter hiện có; lỗi safeApiError422badquery/UUID/cursor,404unavailabledetail,503dependency/malformedRPC. Chỉ exact rewrites/allowlist, không mở wildcard seller/admin.

Danh sách: q0..100codepoints tên, origin0..120 literal case-insensitive contains; categoryPRODUCE/SEAFOOD; min/max giá1..1e9 số nguyên, min<=max; shopIdUUID. Giá lọc là **giá từ** min của tất cả SKU. Chỉ một sort ổn định created_atDESC,idDESC. Tất cảSQLbindparams, escape `%`/`_`/backslash để tìm literal. Duplicate/unknown query bị422. Cursor nullable base64url typed(timestamp,UUID,filterhash), tối đa256chars, bind normalizedfilters; malformed/crossfilter422, không dùng cursor làm bằng chứng quyền.

Không count toàn bộ hoặc tải toàn bộ shop. Mỗi request quét tối đa20candidate Catalog, đọc21để xác định còn trang; Merchant xác minh tối đa4batch×5shop hiện tại. Trả `{items,max20,nextCursor:null|string}`; cursor là candidate cuối đã quét, không phải item cuối còn ACTIVE. Shop bị ẩn có thể tạo trang ngắn/rỗng nhưng còn nextCursor; UI phải cho **Xem tiếp**, không báo hết kết quả khi cursor còn. Đây là tradeoff giới hạn tải federation của MVP, không phải totalcount chính xác. Cùng filters và cursor không giữ snapshot xuyên request; update/hide có thể thay kết quả, createdAt/id không đổi.

Card gồm id,name,category,condition,origin,minPriceVnd,inStock,shop{id,name},thumbnail{url,width,height}. Detail thêm description/producerName nullable, storageInstructions, skus{id,label,priceVnd,netWeightGrams,saleUnit:PACKAGE,inStock}, images tối đa5. Ảnh lấy theo position từ verified attachment, không nhận URL client hoặc metadata private.

## Merchant boundary và consistency

Thêm `MerchantAccess.FindPublicShops` giữ listener127.0.0.1/internal token/4KiB: request1..5UUID khác nhau, response chỉ{id,name} cho ACTIVE shop; missing/inactive omitted. Reuse store.find cho bounded5IDs, không join Catalog/Identity DB, không trả Shop record qua wire. Shop name theo contract1..120codepoints không chỉ whitespace; Merchant bỏ qua tên legacy chỉ whitespace. Tối đa5names×480UTF8bytes vẫn dưới4KiB. Catalog validates response IDs là subset requested, unique, shape/name bounded; unknown/duplicate/malformed/down/timeout fail503 toàn request. Deadline2s mỗi call, no retry. Detail batch1, list tối đa4call; không cache shop status.

Read projection Catalog snapshot trong transaction ngắn, đóng trước RPC; lấy SKU/images/stock nhất quán cho detail. Khác service không có transaction chung: status có thể đổi sau lúc kiểm, request tiếp theo phải thấy mới. Không giả storefront snapshot là quyền checkout. V5 chỉ thêm index partial PUBLISHED created/id nếu cần query, không sửaV1..V4.

## Nhịp thực hiện và file

1. Chốt plan/OpenAPI/proto, review cursor/ẩn shop/privateprojection, install contract modules tuần tự. Merchant worker chỉ Merchant service/tests; main Catalog; frontend worker chỉ frontend. Mọi Maven chia quyền lượt, không chạy đồng thời ghi target.
2. Merchant TDD: valid token,1..5UUID/duplicate/empty/oversize, onlyACTIVEcurrentname, noPII, DB outage503mapped. Add typed public record in ShopService, reuse bounded find; RPC additive không đổi ResolveActiveShop.
3. Catalog TDD: port PublicShops/clientRPCvalidation; Storefront DTO/query/controller/service/cursor. Test anonymous/filterliteral/minprice/pagination20+cursor/emptywindow, published/photo/shop restrictions, current hide/suspend/expiry/block/noexactcounts, RPC outage/malformed/subset, safeerrors/no-store; ensure no private field reaches JSON. Indexmigration additive nếu dùng.
4. UI reader/filters/list/detail/no fake buy, null/shortpagecursor, accessible/responsive/mobile, fresh focus/pageshow/visible revalidation hides unavailable detail; abort stale filter/cursor reads, error/offline recovery andURL validation. Pin/sinh types, exactallowlist/rewrite và homepage/header updates with baseline regression.
5. SerialrootMaven, frontendstatic/unit/build/fixture, independent source review. Live newcontrolled3DBfixture: owner/admin approve, realnewCloudinaryPNG upload+attach, publish+lot, anonymousbrowse/detail/filter, hide/revoke/expiredchecks. Tạo đúng reservedasset mới, detach sau HIDDEN và verify DB/providercleanup; không list/delete tài khoản media cũ. Không dùng ảnh giả làm proof live.
6. Docs/contractpin/proof/HANDOFF/tracker/diff/secret/link checks; stopownedapps/fixture sauproof. Chỉ đóng M2c2 sau tất cả gate/review/livecleanup. Không tuyên bố hoàn tất checkout hay toànUC05reviewQ&A.

## Review focus

- Filtering Merchant không phá continuation khi cả20candidatesinactive; cursor phải advance và không rò count/PII.
- Expiry/blockedlot thiếu stock chỉfalse, không công bố DRAFT/HIDDEN hoặc shopinactive; GET mới phản ánh thay đổi.
- Mỗi public projection có schema allowlist, không serialize Product/Shop private trực tiếp.
- RPC token/inputbound/subset/UTF8/timeouts/failclosed, không dùng anonymous account credential để gọisellerRPC.
- UI đổi filters/cursor và late404/503 không giữ snapshot sai như còn hàng; exactpaths không mở privateAPI.

## Nghiệm thu local ngày 25/10/2026

M2c2 đã nghiệm thu, canonical tracker `slice-2-storefront`. Không coi kết quả này là hoàn tất review/Q&A của UC-05 hoặc checkout. Source ứng dụng không đổi sau production gates/review cuối; những thay đổi sau đó chỉ chạm selector test và tài liệu.

| Gate | Kết quả |
| --- | --- |
| Backend root verify | BUILD SUCCESS20:50:38 +07:00;185tests=13Identity+59Merchant+113Catalog,0fail/error/skip. |
| Frontend | Build/lint/typecheckPASS;130unit,199productionfixture/50.6s; native audit0vulnerabilities. |
| Normal live |5/5PASS12.0s, ba backend/PostgreSQL biệt lập. |
| Storefront provider live |1/1PASS5.1s: anonymous filters/detail, verified PNG, stock block/unblock/focus, hide404/listempty, finallydetach. |
| Review | Backend và frontend production source không có finding cần sửa trong phạm vi M2c2. |
| Cleanup |3intent ảnh mới DELETED/0attached; independent exact Cloudinary lookup3resources all_missing=true,exit0. |

DB connectivity regression2RED trước fix; public StorefrontService map resource/transaction-open503safe, giữ SQL syntax500. Frontend sửa enum widening, public401/403 và uppercaseUUID sau2unitRED; fixture cuối có22storefront cases. Normal live đầu vướng selector Sản phẩm và ngưỡng10attempts của fixture; chỉ override Identity test thành1000, không đổi production. Provider live trước có một selector alert trùng route announcer và một upload503; giữ các lượt lỗi riêng, không tính là PASS. Failed intent được cho hết hạn đúng UUID trong fixture để worker dọn, không sửa database native.

Next/ba backend đã dừng, đúng container test `--rm` đã xóa sau kiểm ID/label; `.env.live-test` ignored600 nay STALE. Không stage/commit/push/deploy hoặc sửa native PostgreSQL/backend.env. [Demo](../docs/demo-storefront.md), [proof frontend](../../frontend/docs/storefront-proof.md). Reservation/FEFO, giỏ/checkout và các tích hợp sandbox là công việc tiếp theo, chưa khởi động trong nghiệm thu này.
