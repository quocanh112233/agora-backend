package com.agora.merchant.adapter.inbound;

import com.agora.merchant.application.ShopService.Create;
import com.agora.merchant.domain.MerchantFailure;
import com.agora.merchant.domain.Shop;
import com.agora.merchant.domain.Shop.Address;
import com.agora.merchant.domain.Shop.Patch;
import com.agora.merchant.domain.Shop.Review;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ShopInput {
    private ShopInput() {}
    static Create create(JsonNode body) {
        fields(body, Set.of("name", "description", "contactPhone", "pickupAddress"));
        return new Create(text(body, "name", 3, 120), description(body), text(body, "contactPhone", 9, 20),
                address(body.path("pickupAddress")));
    }
    static Patch patch(JsonNode body) {
        fields(body, Set.of("expectedVersion", "name", "description", "contactPhone", "pickupAddress"));
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0 || body.size() < 2)
            throw invalid("expectedVersion and at least one editable field are required.");
        return new Patch(version.longValue(), body.has("name") ? text(body, "name", 3, 120) : null,
                description(body), body.has("description"), body.has("contactPhone") ? text(body, "contactPhone", 9, 20) : null,
                body.has("pickupAddress") ? address(body.get("pickupAddress")) : null);
    }
    static long submit(JsonNode body) {
        fields(body, Set.of("expectedVersion"));
        return version(body);
    }
    static Review review(JsonNode body) {
        fields(body, Set.of("expectedVersion", "decision", "reason"));
        long version = version(body);
        String decision = text(body, "decision", 6, 7);
        if (!Set.of("APPROVE", "REJECT").contains(decision)) throw invalid("Invalid review decision.");
        String reason = body.has("reason") || "REJECT".equals(decision) ? text(body, "reason", 10, 1000) : null;
        return new Review(version, decision, reason);
    }
    static Shop.StatusChange moderation(JsonNode body) {
        fields(body, Set.of("status", "expectedVersion", "reason"));
        String status = text(body, "status", 1, 20);
        if (!Set.of("ACTIVE", "SUSPENDED").contains(status)) throw invalid("Invalid moderation status.");
        long version = version(body);
        String reason = text(body, "reason", 0, 10000).strip();
        int size = reason.codePointCount(0, reason.length());
        if (version > 9007199254740991L || size < 10 || size > 1000 || reason.indexOf('\0') >= 0) throw invalid("Invalid moderation input.");
        return new Shop.StatusChange(status, version, reason);
    }
    private static long version(JsonNode body) {
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0)
            throw invalid("A non-negative expectedVersion is required.");
        return version.longValue();
    }
    static int page(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            if (number < min || number > max) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw invalid("Invalid pagination value."); }
    }
    static String status(String value) {
        if (value != null && !Set.of("DRAFT", "PENDING_REVIEW", "ACTIVE", "REJECTED", "SUSPENDED").contains(value))
            throw invalid("Invalid shop status.");
        return value;
    }
    private static Address address(JsonNode body) {
        fields(body, Set.of("countryCode", "provinceCode", "provinceName", "wardCode", "wardName", "streetAddress"));
        String country = text(body, "countryCode", 2, 2);
        if (!"VN".equals(country)) throw invalid("countryCode must be VN.");
        return new Address(country, text(body, "provinceCode", 1, 20), text(body, "provinceName", 1, 100),
                text(body, "wardCode", 1, 20), text(body, "wardName", 1, 100), text(body, "streetAddress", 1, 300));
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || !allowed.containsAll(body.propertyNames()))
            throw invalid("Request must be an object with only the documented fields.");
    }
    private static String description(JsonNode body) {
        return !body.hasNonNull("description") ? null : text(body, "description", 0, 2000);
    }
    private static String text(JsonNode body, String field, int min, int max) {
        JsonNode value = body.path(field);
        if (!value.isString()) throw invalid(field + " must be a string.");
        String text = value.stringValue();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) throw invalid(field + " has an invalid length.");
        return text;
    }
    static UUID uuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException | NullPointerException e) { throw invalid("A valid UUID is required."); }
    }
    static String hash(JsonMapper json, JsonNode body, UUID resource) {
        try {
            String canonical = (resource == null ? "" : resource.toString()) + "\n" + json.writeValueAsString(sorted(body));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object sorted(JsonNode node) {
        if (!node.isObject()) return node;
        var map = new TreeMap<String, Object>();
        node.forEachEntry((name, value) -> map.put(name, sorted(value)));
        return map;
    }
    static MerchantFailure invalid(String message) { return new MerchantFailure(422, "VALIDATION_ERROR", message); }
}
