package com.agora.identity.application;

import com.agora.identity.application.AdminAccountStore.*;
import com.agora.identity.domain.AdminAccountFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class AdminAccountService {
    private final AdminAccountStore store;
    public AdminAccountService(AdminAccountStore store) { this.store = store; }
    public Page list(Filter filter, String cursor) {
        String fingerprint = fingerprint(filter);
        Position position = decode(cursor, fingerprint);
        try {
            var rows = store.list(filter, position, 21);
            var items = rows.subList(0, Math.min(20, rows.size()));
            return new Page(items, rows.size() > 20 ? encode(items.getLast(), fingerprint) : null);
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public View find(UUID id) {
        try { return store.find(id).orElseThrow(() -> new AdminAccountFailure(404, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.")); }
        catch (DataAccessException e) { throw unavailable(); }
    }
    public View change(Actor actor, UUID target, Change change) {
        try { return store.change(actor, target, change); }
        catch (DataAccessException e) { throw unavailable(); }
    }
    public HistoryPage history(UUID target, String cursor) {
        Position position = decode(cursor, target.toString());
        try {
            find(target);
            var rows = store.history(target, position, 21);
            var items = rows.subList(0, Math.min(20, rows.size()));
            var last = items.isEmpty() ? null : items.getLast();
            String next = rows.size() > 20 ? encode(last.createdAt(), last.id(), target.toString()) : null;
            return new HistoryPage(items, next);
        } catch (DataAccessException e) { throw unavailable(); }
    }
    private static String fingerprint(Filter filter) {
        try {
            String value = String.join("\0", "admin-accounts-v1", Objects.toString(filter.q(), ""), Objects.toString(filter.status(), ""),
                    Objects.toString(filter.role(), ""), Objects.toString(filter.accountType(), ""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String encode(View last, String fingerprint) {
        return encode(last.createdAt(), last.id(), fingerprint);
    }
    private static String encode(Instant time, UUID id, String fingerprint) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (time + "|" + id + "|" + fingerprint).getBytes(StandardCharsets.UTF_8));
    }
    private static Position decode(String cursor, String fingerprint) {
        if (cursor == null) return null;
        try {
            if (!cursor.matches("[A-Za-z0-9_-]{1,512}")) throw new IllegalArgumentException();
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 3 || !fingerprint.equals(values[2])) throw new IllegalArgumentException();
            Instant time = Instant.parse(values[0]); UUID id = UUID.fromString(values[1]);
            if (!id.toString().equals(values[1]) || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z")) || time.getNano() % 1000 != 0)
                throw new IllegalArgumentException();
            return new Position(time, id);
        } catch (RuntimeException e) { throw new AdminAccountFailure(422, "VALIDATION_ERROR", "Liên kết phân trang không hợp lệ."); }
    }
    public static AdminAccountFailure unavailable() { return new AdminAccountFailure(503, "IDENTITY_UNAVAILABLE", "Dịch vụ tài khoản tạm thời chưa sẵn sàng."); }
}
