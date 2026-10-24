package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.MediaService;
import com.agora.catalog.application.MediaStore.Key;
import com.agora.catalog.application.SellerAccess;
import com.agora.catalog.application.SellerAccess.Seller;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.ProductImage.*;
import tools.jackson.core.StreamReadFeature;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/seller/products/{productId}")
public class MediaController {
    private final SellerAccess access;
    private final MediaService media;
    private final JsonMapper json;
    public MediaController(SellerAccess access, MediaService media, JsonMapper json) {
        this.access = access; this.media = media;
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }
    private Seller seller(HttpServletRequest request, boolean unsafe) {
        String session = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if ("AGORA_SESSION".equals(cookie.getName())) {
                if (session != null) throw ProductInput.invalid("Ambiguous session cookie.");
                session = cookie.getValue();
            }
        }
        return access.resolve(session, request.getHeader("X-CSRF-TOKEN"), unsafe);
    }
    @GetMapping("/images")
    Images list(@PathVariable String productId, HttpServletRequest request) {
        Seller seller = seller(request, false);
        return media.list(seller.shopId(), ProductInput.uuid(productId));
    }
    @PostMapping(value = "/image-uploads", consumes = "multipart/form-data")
    ResponseEntity<Upload> upload(@PathVariable String productId, HttpServletRequest request) throws Exception {
        Seller seller = seller(request, true);
        UUID product = ProductInput.uuid(productId), key = ProductInput.uuid(request.getHeader("Idempotency-Key"));
        Collection<Part> parts;
        try { parts = request.getParts(); }
        catch (IllegalStateException e) { throw new CatalogFailure(413, "FILE_TOO_LARGE", "Image must be at most 5 MiB and request at most 6 MiB."); }
        catch (jakarta.servlet.ServletException e) { throw new CatalogFailure(400, "BAD_REQUEST", "Request must contain valid multipart data."); }
        if (parts.size() != 1 || !"file".equals(parts.iterator().next().getName()) || parts.iterator().next().getSubmittedFileName() == null)
            throw ProductInput.invalid("Provide exactly one file part.");
        ImageFile file = ImageFile.read(parts.iterator().next());
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.data())); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var body = json.createObjectNode().put("digest", digest).put("mime", file.mime());
        var intent = new Key(seller.accountId(), seller.shopId(), product, key, ProductInput.hash(json, body, seller.shopId(), product));
        return ResponseEntity.status(201).body(media.upload(intent, file.data(), file.mime(), file.width(), file.height()));
    }
    @PostMapping(value = "/images", consumes = "application/json")
    Images attach(@PathVariable String productId, HttpServletRequest request) throws IOException {
        Seller seller = seller(request, true);
        UUID product = ProductInput.uuid(productId);
        JsonNode body = body(request, Set.of("uploadId", "expectedVersion"));
        if (!body.path("uploadId").isString()) throw ProductInput.invalid("A valid uploadId is required.");
        return media.attach(key(seller, product, null, body, request), ProductInput.uuid(body.path("uploadId").stringValue()),
                ProductInput.version(body), ApiErrors.trace(request));
    }
    @DeleteMapping(value = "/images/{imageId}", consumes = "application/json")
    Images detach(@PathVariable String productId, @PathVariable String imageId, HttpServletRequest request) throws IOException {
        Seller seller = seller(request, true);
        UUID product = ProductInput.uuid(productId), image = ProductInput.uuid(imageId);
        JsonNode body = body(request, Set.of("expectedVersion"));
        return media.detach(key(seller, product, image, body, request), image, ProductInput.version(body), ApiErrors.trace(request));
    }
    private Key key(Seller seller, UUID product, UUID image, JsonNode body, HttpServletRequest request) {
        var canonical = json.createObjectNode().set("body", body).put("imageId", image == null ? "" : image.toString());
        return new Key(seller.accountId(), seller.shopId(), product, ProductInput.uuid(request.getHeader("Idempotency-Key")),
                ProductInput.hash(json, canonical, seller.shopId(), product));
    }
    private JsonNode body(HttpServletRequest request, Set<String> fields) throws IOException {
        byte[] data = request.getInputStream().readNBytes(2049);
        if (data.length > 2048) throw badJson();
        JsonNode body;
        try { body = json.readTree(data); }
        catch (RuntimeException e) { throw badJson(); }
        if (body == null || !body.isObject() || !body.propertyNames().equals(fields))
            throw ProductInput.invalid("Provide only the documented required fields.");
        return body;
    }
    private static CatalogFailure badJson() { return new CatalogFailure(400, "BAD_REQUEST", "Request must contain valid JSON."); }
}
