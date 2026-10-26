# M3a — Giỏ hàng bền vững theo tài khoản

Lúc bắt đầu ngày 26/10/2026, base backend `81d1d6c`, frontend `ad9af7c`, hai repo sạch. Sau nghiệm thu, diff M3a chưa commit. Người dùng đã cho tiếp tục và tự quyết lựa chọn thông thường. Canonical tracker `slice-3-cart`; không stage/commit/push/deploy hoặc đổi native PostgreSQL. Caveman/Ponytail full. Thiết kế trước contract/code, thực hiện đến nghiệm thu lát cắt này.

## Phạm vi và thứ tự tiếp theo

Người mua đã đăng nhập thêm SKU từ detail public, xem giỏ theo shop, đặt lại số lượng tuyệt đối và xóa dòng; giỏ lưu PostgreSQL trong **commerce-service** đã có trong kiến trúc đích, không thêm service thứ bảy. Giỏ cho phép nhiều shop, tối đa20SKU và1..99gói mỗi SKU. Giỏ không giữ tồn; checkout chỉ nhận một shop. Không có nút checkout/thanh toán giả. Không guest cart, merge, promotion, phí giao hàng hoặc xóa toàn giỏ trong M3a.

Sau M3a: M3b Catalog reservation FEFO/15phút/expiry-release/idempotency, M3c checkout một shop với snapshot và GHN quote thực, M3d Payment/VNPAY. Reservation thuộc Catalog, cart/order thuộc Commerce. Không dùng trạng thái giỏ hoặc inStock làm quyền mua/giữ hàng. Chưa tạo đơn chờ tiền khi chưa có địa chỉ/quote/giữ hàng hợp lệ.

## Contract HTTP và kiểu dữ liệu

Canonical `contracts/openapi/commerce-cart.v1.openapi.json`1.0.0. Đúng3operation:

- GET `/api/v1/me/cart` =>200 `Cart`: `{cartId,version,items}`; item `{productId,skuId,quantity,offer}`; offer nullable khi sản phẩm/SKU/ảnh/shop không còn public. Emptycartversion0 nếu chưa có row, GET không tạo row. Max20items, sort SKU UUID ascending ổn định.
- PUT `/api/v1/me/cart/items/{skuId}` body `{cartId,productId,quantity,expectedVersion}` =>200 `{version}`. Số lượng tuyệt đối, không delta; frontend cộng với giá trị đã đọc để thêm. Product/SKU hiện public và inStock true mới PUT; hết tồn/mất public409. Không cam kết đủ chính xác quantity khi chưa reservation. Tối đa20SKU, crossshop hợp lệ.
- DELETE cùng path body `{cartId,expectedVersion}` =>200 `{version}`. Không gọi Catalog, vẫn xóa được dòng đã mất public hoặc Catalog down. Dòng đã thiếu là no-op nếu version đúng; replay trả snapshot version gốc, UI yêu cầu freshGET.

cartId là UUID opaque ổn định suy ra từ account đã xác minh, không phải credential hay ownerId public; GET rỗng không cần ghi DB. Mutation so cartId với account hiện tại trước replay/write; khác409CART_CHANGED. Chặn intent từ tab/tài khoản cũ áp vào giỏ account mới có cùng version; frontend reset intent khi cartId đổi.

`CartOffer` exact `{productId,skuId,shopId,shopName,productName,skuLabel,priceVnd,netWeightGrams,inStock,thumbnail}`; thumbnail `{url,width,height}` theo Cloudinary namespace product đã verified, pixelproduct<=20m. Shopname1..120codepoints nonblank, productName3..160,skuLabel1..100,priceinteger1..1e9,net1..50000. Không stock counts, lot/version/audit, owner/contact/pickup, SKU code/shipping fields hoặc uploadintent.

Tất cả HTTP xác minh Identity current account mỗi request. Mutation CSRF trước replay, cookie ambiguous bị từ chối, strict UUID/key/body/duplicateJSON/trailing/unknownfields/unknownquery. version0..9007199254740991; Idempotency-Key UUID cho PUT/DELETE, key+hash bind operation/cartId/sku/product/quantity/expectedVersion. Retention7ngày. Cùng key/body replay `{version}` cũ; khác payload422. Auth hiện tại luôn kiểm trước replay. Staleversion/linecap/outofstock409; JSONsyntax400,fieldvalidation422,session401,CSRF403,upstream503,SQLdefect500 safe. No-store và trace/securityheaders giữ house style.

UI không tự phát lại một intent chưa rõ kết quả bằng key mới. Sau mutation200 phải yêu cầu GETcart mới; PUT bị khóa đến refresh thành công. Nếu Catalog503, có thể giữ refs không kèm offer/giá/tên cũ để xóa tiếp với cartId và version vừa trả về; stale replayversion409 an toàn. Form/cart phải xóa dữ liệu riêng khi401/403, abort/ignore response cũ, revalidate focus/pageshow/visible; lỗi503 không hiển thị giá/tồn cũ như hiện tại. Link login khi anonymous; không persist session/token trong Zustand/localStorage. Detail chọn SKU+số gói, nút Thêm vào giỏ disabled khi SKU hết tồn; link Giỏ hàng vào `/cart`.

## Catalog RPC và consistency

Thêm `catalog-contract` từ proto `agora/catalog/v1/catalog_offers.proto`, service `CatalogOffers.ResolveOffers`: request1..20distinct strict UUIDsku_ids; response tối đa20CartOffer current, omit missing/ineligible. Mỗi response phải subset/unique IDs và product mapping đúng. Catalog đọc một SQL snapshot: SKU/product PUBLISHED + firstattachedUPLOADEDphoto + eligiblelotEXISTS. Batchshop qua PublicShops tối đa4×5IDs sau DB transaction; no Identity/không đọc Commerce DB. Không gọi public detail20lần hoặc Merchant20lần.

Catalog listener optionalenabled,127.0.0.1:9093,internalconstant-time token32..256URLsafe,request4KiB/maxcalls32. Commerce client inbound64KiB, deadline10giây, không retry; Merchant mỗi call2giây. Worst20names Unicode+labels+shopnames+URL512 vẫn dưới64KiB; test size. Identity clientdeadline2giây. Mọi response RPC validation thất bại503, không hạ validation hoặc trả partial success nếu batch/dependency lỗi.

Commerce GET đọc cart snapshot transaction ngắn REPEATABLE_READ, đóng rồi RPC. PUT kiểm authenticated+CSRF+cartId rồi tra replay trước Catalog; còn mới thì gọi offers ngoài transaction rồi JDBC mutate atomic (recheck replay dưới rowlock). Nếu Catalog thất bại/409, tra replay lần nữa trước trả lỗi để bắt concurrent firstcall đã commit; không có distributed snapshot, current request sau kiểm lại. DELETE không phụ thuộc Catalog. Cart không cache giá/title để tránh coi snapshot cũ là hiện tại. Khi Catalog down GET503 toàncart; không trả partialgiả. Dòng mất public trả offer:null, UI vẫn cho xóa bằng skuId của chính giỏ.

## Dữ liệu và interfaces để triển khai

Commerce HTTP8084, database`agora_commerce`/schema`commerce`; chỉ tạo DB test trong Docker. V1 tables carts(account_idPK,version), cart_items(account_idFK,sku_id,product_id,quantity, PKowner/sku), cart_requests(actor_id,key,operation,request_hash,response_version,created_at, PKactor/key). Không FK xuyên service. GET snapshot không tạo cart. Mutation insertcartONCONFLICT rồi rowFORUPDATE, claimkey/comparehash/replay, version/20cap check và thayitems/version/complete trong cùng transaction; rollback lỗi không để cart/request nửa chừng. Sameversion concurrentwriters chỉ1thànhcông; samekey chỉ1hiệu lực. Giữ key7ngày, scheduler cleanup requests.

Main sở hữu rootpom/contracts/proto/library/install và commerce-service. Catalog worker chỉ Catalog main/tests/config, không sửa contract/root. Frontend worker chỉ frontend, trừ mainreserved`tests/media-live/cart.spec.ts`. Mọi Maven tuần tự, không ghi target đồng thời. Next/frontendgates có một owner. Runtimefixture4DB/4app chỉ khởi động sau JARverified; không dùng ADMIN cũ hoặc native5432. Cloudinary config đã có; live dùng một ảnh PNG mới và cleanup đúng asset sau HIDDEN/detach/DBDELETED+exactproviderfindmissing.

Các service/interface giữ house layers: Commerce `CartStore` với snapshot+atomicPUT/DELETE, `CartService` điều phối, `IdentityAccess` và `CatalogOffers` ports, inboundCartController/ApiErrors/metadata, outboundJDBC/gRPC. Cart DTO explicit immutable records. Catalog thêm batchofferprojection/portmethod/service và optionalgrpcserver, giữ publicREST hiện có. Không library tiện ích dùng chung mới.

## Nhịp kiểm chứng và điểm dừng

1. Plan/OpenAPI/proto và independentdesignreview, modulesinstall. Bootstrap Commerce+V1 và TDD domain/API qua PostgreSQL, auth+RPC fixtures. Catalog TDDcurrenteligibility/stock/bounds/token/deadline/noPII+shape; contract đã có trước worker.
2. Commerce tests: anonymous/CSRF/ownership/sessionrevocation, strictinput, quantity/20cap/crossshop, freshprices/offer:null/currenthide/suspend/outofstock, replay/hash/version/transactionrollback/concurrentkey+version, DB/upstream errors safe, DELETECatalogdown. Cart không giữ hoặc thay stock.
3. Frontend unit/prodfixtures/static/build: detailadd/cartgroup/update/remove/unavailablelines, unknownmutationkey retry, freshGET, dirtyinputs/versionconflict/authfocus+lateresponses, mobile/keyboard/axe/console. Không fakebutton checkout.
4. Rootverify serial, independentreview, freshisolated4service live: ownerACTIVE/publishedrealphoto+lot, buyeradd/reload/absolutequantity/replay/remove, crossbuyerempty, hide->unavailable/removable, authrevocation. Baseline normal5live và newcartproviderlive. Exactassetcleanup, runtimecleanup.
5. Đồng bộ docs/proof/HANDOFF/contracts pins, knownsecretscan/diff/linkchecks, canonicaltracker done. Dừng khi M3a đủ bằng chứng, không tự mở payment/reservation implementation trong cùng acceptance.


## Kết quả nghiệm thu ngày 26/10 sau resume

M3a hoàn tất local: backend 226 tests/review CLEAR; frontend 136 unit, 243 production fixture (47.1s, không skip/flaky), 5 normal live (10.6s), 1 cart/provider live (6.5s), lint/typecheck/build/audit0 đều đạt. Build cuối `DLU8w9nCv4HmJUggCkzWP`. Backend source/config/contract manifest150file không đổi sau root verify10:55:16; không rerun Maven khi chỉ sửa frontend.

Hai finding frontend P1 late GET sau mutation401/403 và P2 replay version cũ đều được tái hiện bằng5regression RED rồi sửa trong `use-cart.ts`: abort/fence read theo access generation, không ghi đè snapshot đã biết bằng version bằng/thấp hơn. Năm regression GREEN2.2s và nằm trong243fixture; independent re-review CLEAR. Lỗi provider attempt đầu chỉ ở bài test đọc body sau navigation, đã cache body trước đổi trang; giữ output FAIL riêng.

Main đã xem fixture/live screenshots. Hai ảnh mới được xác minh DBDELETED/0attached và exactCloudinarymissing; lô cuối12/0/0v2, cart thành côngv3/0items/3requests. Ứng dụng và fixtureDocker sở hữu đã dọn; nativeDB và actualbackend.env không đổi, ADMINenvtest hiệnSTALE. [Demo/proof](../docs/demo-cart.md#nghiệm-thu-local-ngày-26102026) ghi giới hạn và artifact. Không stage/commit/push/deploy. Chưa triển khai reservationFEFO, checkout, GHN/VNPAY; bước tiếp là thiết kế M3b theo thứ tự đã chốt.
