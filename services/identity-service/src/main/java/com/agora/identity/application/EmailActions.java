package com.agora.identity.application;

import com.agora.identity.domain.Account;
import com.agora.identity.domain.AdminAccountFailure;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailActions {
    private final EmailActionStore store;
    private final TokenCodec tokens;
    private final PasswordEncoder passwords;
    private final EmailPort mail;

    public EmailActions(EmailActionStore store, TokenCodec tokens, PasswordEncoder passwords, EmailPort mail) {
        this.store = store;
        this.tokens = tokens;
        this.passwords = passwords;
        this.mail = mail;
    }

    private boolean active(EmailActionStore.State state) {
        return "ACTIVE".equals(state.status()) && state.accountType() != null && state.version() < 9007199254740991L;
    }

    @Transactional
    public void verification(UUID id) {
        var account = store.lock(id).orElseThrow();
        if (active(account) && !account.verified()) queue(account, "VERIFY_EMAIL", null);
    }

    @Transactional
    public void request(String email, String purpose) {
        var id = store.find(Account.normalizeEmail(email));
        if (id.isEmpty()) return;
        var state = store.lock(id.get()).orElseThrow();
        if (!active(state) || ("VERIFY_EMAIL".equals(purpose) ? state.verified() : !state.verified())) return;
        queue(state, purpose, null);
    }

    private boolean queue(EmailActionStore.State state, String purpose, String pendingHash) {
        if (store.coolingDown(state.id(), purpose)) return false;
        UUID id = UUID.randomUUID();
        String token = tokens.create();
        var expiry = state.now().plusSeconds("VERIFY_EMAIL".equals(purpose) ? 86400 : 1800);
        store.queue(id, state, purpose, tokens.digest(token), tokens.seal(id, purpose, token), pendingHash, expiry);
        return true;
    }

    @Transactional
    public void change(UUID id, long version, String currentPassword, String newPassword) {
        password(newPassword);
        var state = store.lock(id).orElseThrow(IdentityService.Unauthenticated::new);
        if (!active(state) || !state.verified() || state.version() != version) throw new IdentityService.Unauthenticated();
        if (!passwords.matches(currentPassword, state.passwordHash())) {
            throw new AdminAccountFailure(422, "CURRENT_PASSWORD_INVALID", "Mật khẩu hiện tại không đúng.");
        }
        if (!queue(state, "CHANGE_PASSWORD", passwords.encode(newPassword))) {
            throw new AdminAccountFailure(429, "EMAIL_COOLDOWN", "Bạn vừa yêu cầu đổi mật khẩu. Chờ ít nhất một phút trước khi tạo link cho mật khẩu khác.");
        }
    }

    @Transactional
    public void confirm(String token, String purpose, String newPassword) {
        byte[] digest;
        try { digest = tokens.digest(token); }
        catch (IllegalArgumentException e) { throw invalid(); }
        var ref = store.locate(digest).orElseThrow(EmailActions::invalid);
        var state = store.lock(ref.accountId()).orElseThrow(EmailActions::invalid);
        EmailActionStore.Action action;
        try { action = store.action(ref.id()); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw invalid(); }
        if (!action.usable() || !action.purpose().equals(purpose) || !active(state) || state.version() != action.version()
                || ("VERIFY_EMAIL".equals(purpose) ? state.verified() : !state.verified())) throw invalid();
        String hash = null;
        if ("RESET_PASSWORD".equals(purpose)) {
            password(newPassword);
            hash = passwords.encode(newPassword);
        }
        if ("CHANGE_PASSWORD".equals(purpose)) {
            hash = action.pendingHash();
            if (hash == null) throw invalid();
        }
        store.apply(state, ref.id(), hash);
    }

    @Transactional
    public boolean dispatch() {
        var pending = store.pending();
        if (pending.isEmpty()) return false;
        var row = pending.get();
        try {
            mail.send(row.id(), row.recipient(), row.purpose(), tokens.open(row.id(), row.purpose(), row.cipher()), row.expiresAt());
            store.sent(row.id());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Email delivery deferred type={}", e.getClass().getSimpleName());
            store.retry(row.id());
        }
        return true;
    }

    @Transactional
    public void cleanup() { store.cleanup(); }

    private static void password(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) < 12 || value.codePointCount(0, value.length()) > 128) {
            throw new AdminAccountFailure(422, "VALIDATION_ERROR", "Mật khẩu cần từ 12 đến 128 ký tự.");
        }
    }

    private static AdminAccountFailure invalid() {
        return new AdminAccountFailure(422, "LINK_INVALID", "Liên kết không hợp lệ hoặc hết hạn. Hãy yêu cầu gửi link mới.");
    }
}
