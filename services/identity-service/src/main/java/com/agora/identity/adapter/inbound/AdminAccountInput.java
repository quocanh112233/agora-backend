package com.agora.identity.adapter.inbound;

import com.agora.identity.application.AdminAccountStore.Filter;
import com.agora.identity.domain.AdminAccountFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

final class AdminAccountInput {
    private AdminAccountInput() {}
    static Filter filter(HttpServletRequest request) {
        Set<String> allowed = Set.of("q", "status", "role", "accountType", "cursor");
        for (var item : request.getParameterMap().entrySet())
            if (!allowed.contains(item.getKey()) || item.getValue().length != 1) throw invalid();
        String q = request.getParameter("q");
        if (q != null) {
            q = q.strip();
            if (q.codePointCount(0, q.length()) > 100 || q.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            if (q.isEmpty()) q = null;
        }
        return new Filter(q, choice(request.getParameter("status"), Set.of("ACTIVE", "SUSPENDED")),
                choice(request.getParameter("role"), Set.of("USER", "ADMIN")),
                choice(request.getParameter("accountType"), Set.of("BUYER", "SELLER")));
    }
    static com.agora.identity.application.AdminAccountStore.Change change(tools.jackson.databind.JsonNode body) {
        if (!body.isObject() || body.size() != 3 || !body.has("status") || !body.has("expectedVersion") || !body.has("reason")
                || !body.path("status").isString() || !body.path("reason").isString()
                || !body.path("expectedVersion").isIntegralNumber() || !body.path("expectedVersion").canConvertToLong()) throw invalid();
        String status = choice(body.path("status").asString(), Set.of("ACTIVE", "SUSPENDED"));
        long version = body.path("expectedVersion").asLong();
        String reason = body.path("reason").asString().strip();
        int size = reason.codePointCount(0, reason.length());
        if (version < 0 || version > 9007199254740991L || size < 10 || size > 1000 || reason.indexOf('\0') >= 0) throw invalid();
        return new com.agora.identity.application.AdminAccountStore.Change(status, version, reason);
    }
    static String historyCursor(HttpServletRequest request) {
        for (var item : request.getParameterMap().entrySet())
            if (!item.getKey().equals("cursor") || item.getValue().length != 1) throw invalid();
        return request.getParameter("cursor");
    }
    private static String choice(String value, Set<String> choices) { if (value != null && !choices.contains(value)) throw invalid(); return value; }
    static UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException e) { throw invalid(); }
    }
    static AdminAccountFailure invalid() { return new AdminAccountFailure(422, "VALIDATION_ERROR", "Dữ liệu quản trị không hợp lệ."); }
}
