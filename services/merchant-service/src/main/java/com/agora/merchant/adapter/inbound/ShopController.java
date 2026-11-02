package com.agora.merchant.adapter.inbound;

import com.agora.merchant.application.IdentityPort;
import com.agora.merchant.application.IdentityPort.Principal;
import com.agora.merchant.application.MerchantStore.Page;
import com.agora.merchant.domain.MerchantFailure;
import com.agora.merchant.application.MerchantStore.Key;
import com.agora.merchant.application.MerchantStore.Result;
import com.agora.merchant.application.ShopService;
import com.agora.merchant.domain.Shop;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
public class ShopController {
    private final IdentityPort identity;
    private final ShopService shops;
    private final JsonMapper json;
    private final com.agora.merchant.application.ShopModeration moderation;
    public ShopController(IdentityPort identity, ShopService shops, JsonMapper json, com.agora.merchant.application.ShopModeration moderation) {
        this.identity = identity; this.shops = shops; this.json = json; this.moderation = moderation;
    }
    private Principal principal(HttpServletRequest request, boolean unsafe) {
        String session = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) {
            if ("AGORA_SESSION".equals(cookie.getName())) {
                if (session != null) throw ShopInput.invalid("Ambiguous session cookie.");
                session = cookie.getValue();
            }
        }
        return identity.resolve(session, request.getHeader("X-CSRF-TOKEN"), unsafe);
    }
    private UUID seller(HttpServletRequest request, boolean unsafe) {
        Principal actor = principal(request, unsafe);
        if (!"SELLER".equals(actor.accountType()))
            throw new MerchantFailure(403, "FORBIDDEN", "A SELLER account is required.");
        return actor.accountId();
    }
    @GetMapping("/me/shop")
    Shop mine(HttpServletRequest request) { return shops.mine(seller(request, false)); }

    @PostMapping(value = "/shops", consumes = "application/json")
    ResponseEntity<Shop> create(@RequestBody JsonNode body, HttpServletRequest request) {
        UUID actor = seller(request, true);
        var input = ShopInput.create(body);
        Result result = shops.create(new Key(actor, "createShop", ShopInput.uuid(request.getHeader("Idempotency-Key")),
                ShopInput.hash(json, body, null)), input, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).header("Location", "/api/v1/me/shop").body(result.shop());
    }
    @PatchMapping(value = "/shops/{shopId}", consumes = "application/json")
    ResponseEntity<Shop> patch(@PathVariable String shopId, @RequestBody JsonNode body, HttpServletRequest request) {
        UUID actor = seller(request, true);
        UUID id = ShopInput.uuid(shopId);
        var input = ShopInput.patch(body);
        Result result = shops.patch(new Key(actor, "updateShop", ShopInput.uuid(request.getHeader("Idempotency-Key")),
                ShopInput.hash(json, body, id)), id, input, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.shop());
    }
    private UUID admin(HttpServletRequest request, boolean unsafe) {
        Principal actor = principal(request, unsafe);
        if (!actor.roles().contains("ADMIN"))
            throw new MerchantFailure(403, "FORBIDDEN", "ADMIN role is required.");
        return actor.accountId();
    }
    @PostMapping(value = "/shops/{shopId}/submissions", consumes = "application/json")
    ResponseEntity<Shop> submit(@PathVariable String shopId, @RequestBody JsonNode body, HttpServletRequest request) {
        UUID actor = seller(request, true);
        UUID id = ShopInput.uuid(shopId);
        long version = ShopInput.submit(body);
        Result result = shops.submit(new Key(actor, "submitShop", ShopInput.uuid(request.getHeader("Idempotency-Key")),
                ShopInput.hash(json, body, id)), id, version, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.shop());
    }
    @PutMapping(value = "/admin/shops/{shopId}/status", consumes = "application/json")
    Shop status(@PathVariable String shopId, @RequestBody JsonNode body, HttpServletRequest request) {
        Principal actor = principal(request, true);
        if (!actor.roles().contains("ADMIN")) throw new MerchantFailure(403, "FORBIDDEN", "ADMIN role is required.");
        if (!request.getParameterMap().isEmpty()) throw ShopInput.invalid("Unknown query parameters.");
        return moderation.change(actor, ShopInput.uuid(shopId), ShopInput.moderation(body), ApiErrors.trace(request));
    }
    @GetMapping("/admin/shops/{shopId}/status-history")
    com.agora.merchant.application.MerchantStore.HistoryPage history(@PathVariable String shopId, HttpServletRequest request) {
        admin(request, false);
        for (var item : request.getParameterMap().entrySet())
            if (!item.getKey().equals("cursor") || item.getValue().length != 1) throw ShopInput.invalid("Invalid history query.");
        return moderation.history(ShopInput.uuid(shopId), request.getParameter("cursor"));
    }
    @GetMapping("/admin/shops/by-owner/{accountId}")
    Shop byOwner(@PathVariable String accountId, HttpServletRequest request) {
        admin(request, false);
        if (!request.getParameterMap().isEmpty()) throw ShopInput.invalid("Unknown query parameters.");
        return shops.mine(ShopInput.uuid(accountId));
    }
    @GetMapping("/admin/shops")
    Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String page,
              @RequestParam(required = false) String pageSize, HttpServletRequest request) {
        admin(request, false);
        return shops.list(ShopInput.status(status), ShopInput.page(page == null ? "0" : page, 0, Integer.MAX_VALUE),
                ShopInput.page(pageSize == null ? "20" : pageSize, 1, 100));
    }
    @PostMapping(value = "/admin/shops/{shopId}/reviews", consumes = "application/json")
    ResponseEntity<Shop> review(@PathVariable String shopId, @RequestBody JsonNode body, HttpServletRequest request) {
        UUID actor = admin(request, true);
        UUID id = ShopInput.uuid(shopId);
        var input = ShopInput.review(body);
        Result result = shops.review(new Key(actor, "reviewShop", ShopInput.uuid(request.getHeader("Idempotency-Key")),
                ShopInput.hash(json, body, id)), id, input, ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.shop());
    }
}
