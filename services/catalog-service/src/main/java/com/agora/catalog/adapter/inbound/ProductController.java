package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.ProductService;
import com.agora.catalog.application.ProductStore.*;
import com.agora.catalog.application.SellerAccess;
import com.agora.catalog.application.SellerAccess.Seller;
import com.agora.catalog.domain.Product;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import com.agora.catalog.domain.CatalogFailure;

@RestController
@RequestMapping("/api/v1/seller/products")
public class ProductController {
    private final SellerAccess access;
    private final ProductService products;
    private final JsonMapper json;
    public ProductController(SellerAccess access, ProductService products, JsonMapper json) {
        this.access = access; this.products = products;
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }
    private Seller seller(HttpServletRequest request, boolean unsafe) {
        String session = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) {
            if ("AGORA_SESSION".equals(cookie.getName())) {
                if (session != null) throw ProductInput.invalid("Ambiguous session cookie.");
                session = cookie.getValue();
            }
        }
        return access.resolve(session, request.getHeader("X-CSRF-TOKEN"), unsafe);
    }
    @GetMapping
    Page list(@RequestParam(required = false) String page, @RequestParam(required = false) String pageSize, HttpServletRequest request) {
        Seller seller = seller(request, false);
        return products.list(seller.shopId(), ProductInput.page(page == null ? "0" : page, 0, 1000000),
                ProductInput.page(pageSize == null ? "20" : pageSize, 1, 100));
    }
    @GetMapping("/{productId}")
    Product get(@PathVariable String productId, HttpServletRequest request) {
        Seller seller = seller(request, false);
        return products.get(seller.shopId(), ProductInput.uuid(productId));
    }
    @PostMapping(consumes = "application/json")
    ResponseEntity<Product> create(@RequestBody JsonNode body, HttpServletRequest request) {
        Seller seller = seller(request, true);
        var input = ProductInput.product(body, false);
        Result result = products.create(key(seller, "createProduct", null, body, request), input, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).header("Location", "/api/v1/seller/products/" + result.product().id()).body(result.product());
    }
    @PutMapping(value = "/{productId}", consumes = "application/json")
    ResponseEntity<Product> replace(@PathVariable String productId, @RequestBody JsonNode body, HttpServletRequest request) {
        Seller seller = seller(request, true);
        UUID id = ProductInput.uuid(productId);
        var input = ProductInput.product(body, true);
        Result result = products.replace(key(seller, "updateProduct", id, body, request), id, ProductInput.version(body), input, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.product());
    }
    private Key key(Seller seller, String operation, UUID id, JsonNode body, HttpServletRequest request) {
        return new Key(seller.accountId(), seller.shopId(), operation, ProductInput.uuid(request.getHeader("Idempotency-Key")),
                ProductInput.hash(json, body, seller.shopId(), id));
    }
    @PutMapping(value = "/{productId}/visibility", consumes = "application/json")
    ResponseEntity<Product> changeVisibility(@PathVariable String productId, HttpServletRequest request) throws IOException {
        Seller seller = seller(request, true);
        UUID id = ProductInput.uuid(productId);
        byte[] data = request.getInputStream().readNBytes(1025);
        if (data.length > 1024) throw badVisibilityJson();
        JsonNode body;
        try { body = json.readTree(data); }
        catch (RuntimeException e) { throw badVisibilityJson(); }
        if (body == null || body.isMissingNode()) throw badVisibilityJson();
        String status = ProductInput.visibility(body);
        Result result = products.changeVisibility(key(seller, "changeProductVisibility", id, body, request),
                id, ProductInput.version(body), status, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.product());
    }
    private static CatalogFailure badVisibilityJson() {
        return new CatalogFailure(400, "BAD_REQUEST", "Request must contain a single valid JSON object within 1024 bytes.");
    }
}
