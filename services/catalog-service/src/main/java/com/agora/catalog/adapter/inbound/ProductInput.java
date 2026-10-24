package com.agora.catalog.adapter.inbound;

import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Product;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ProductInput {
    private static final Set<String> FIELDS = Set.of("name", "description", "category", "condition", "origin", "producerName", "storageInstructions", "skus");
    // JSON Schema's nonblank pattern uses ECMAScript whitespace, including NBSP and BOM.
    private static final Pattern NONBLANK = Pattern.compile("[^\\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]");
    private ProductInput() {}
    static Product.Input product(JsonNode body, boolean replacing) {
        var allowed = new HashSet<>(FIELDS);
        if (replacing) allowed.add("expectedVersion");
        fields(body, allowed);
        if (replacing) version(body);
        JsonNode skus = body.path("skus");
        if (!skus.isArray() || skus.size() < 1 || skus.size() > 10) throw invalid("Provide 1–10 SKUs.");
        var codes = new HashSet<String>();
        var inputs = new ArrayList<Product.SkuInput>();
        for (JsonNode sku : skus) {
            fields(sku, Set.of("code", "label", "priceVnd", "netWeightGrams", "shippingWeightGrams", "lengthCm", "widthCm", "heightCm"));
            String code = text(sku, "code", 1, 40, true);
            if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,39}") || !codes.add(code)) throw invalid("SKU codes must be valid and distinct.");
            int net = (int) integer(sku, "netWeightGrams", 1, 50000);
            int shipping = (int) integer(sku, "shippingWeightGrams", 1, 50000);
            if (shipping < net) throw invalid("Shipping weight must be at least net weight.");
            inputs.add(new Product.SkuInput(code, text(sku, "label", 1, 100, true), integer(sku, "priceVnd", 1, 1000000000),
                    net, shipping, (int) integer(sku, "lengthCm", 1, 200), (int) integer(sku, "widthCm", 1, 200), (int) integer(sku, "heightCm", 1, 200)));
        }
        String category = text(body, "category", 1, 20, true), condition = text(body, "condition", 1, 20, true);
        if (!Set.of("PRODUCE", "SEAFOOD").contains(category) || !Set.of("FRESH", "FROZEN", "DRIED", "PROCESSED").contains(condition))
            throw invalid("Invalid category or condition.");
        return new Product.Input(text(body, "name", 3, 160, true), nullable(body, "description", 5000), category, condition,
                text(body, "origin", 1, 120, true), nullable(body, "producerName", 160), text(body, "storageInstructions", 1, 1000, true), inputs);
    }
    static long version(JsonNode body) { return integer(body, "expectedVersion", 0, 9007199254740991L); }
    static String visibility(JsonNode body) {
        fields(body, Set.of("status", "expectedVersion"));
        String status = text(body, "status", 1, 20, true);
        if (!Set.of("PUBLISHED", "HIDDEN").contains(status)) throw invalid("Visibility must be PUBLISHED or HIDDEN.");
        version(body);
        return status;
    }
    private static long integer(JsonNode body, String field, long min, long max) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max)
            throw invalid(field + " must be an integer within the documented bounds.");
        return value.longValue();
    }
    private static String nullable(JsonNode body, String field, int max) {
        return body.hasNonNull(field) ? text(body, field, 0, max, false) : null;
    }
    private static String text(JsonNode body, String field, int min, int max, boolean nonblank) {
        JsonNode value = body.path(field);
        if (!value.isString()) throw invalid(field + " must be a string.");
        String text = value.stringValue();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max || nonblank && !NONBLANK.matcher(text).find()
                || text.codePoints().anyMatch(c -> c == 0 || c >= 0xd800 && c <= 0xdfff))
            throw invalid(field + " has invalid text or length.");
        return text;
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || !allowed.containsAll(body.propertyNames()))
            throw invalid("Request must be an object with only the documented fields.");
    }
    static UUID uuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException | NullPointerException e) { throw invalid("A valid UUID is required."); }
    }
    static int page(String value, int min, int max) {
        try {
            int page = Integer.parseInt(value);
            if (page < min || page > max) throw new NumberFormatException();
            return page;
        } catch (NumberFormatException e) { throw invalid("Invalid pagination value."); }
    }
    static String hash(JsonMapper json, JsonNode body, UUID shopId, UUID resource) {
        try {
            String canonical = shopId + "\n" + (resource == null ? "" : resource) + "\n" + json.writeValueAsString(sorted(body));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object sorted(JsonNode node) {
        if (node.isArray()) return node.valueStream().map(ProductInput::sorted).toList();
        if (!node.isObject()) return node;
        var fields = new TreeMap<String, Object>();
        node.forEachEntry((name, value) -> fields.put(name, sorted(value)));
        return fields;
    }
    static CatalogFailure invalid(String message) { return new CatalogFailure(422, "VALIDATION_ERROR", message); }
}
