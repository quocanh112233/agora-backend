package com.agora.email.application;

import com.agora.contracts.email.v1.*;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkMailer {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final String origin;
    private final String from;
    public LinkMailer(JdbcTemplate jdbc, JavaMailSender sender, @Value("${agora.email.origin}") String origin, @Value("${agora.email.from}") String from) {
        this.jdbc = jdbc; this.sender = sender; this.from = from;
        URI uri = URI.create(origin);
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(uri.getHost(), ""));
        if (!("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("PUBLIC_WEB_ORIGIN must be an HTTPS origin, or loopback HTTP.");
        this.origin = origin.replaceAll("/+$", "");
    }
    @Transactional
    public void send(SendLinkRequest request) {
        UUID id;
        try {
            id = UUID.fromString(request.getDeliveryId());
            if (!id.toString().equals(request.getDeliveryId()) || !request.getToken().matches("[A-Za-z0-9_-]{43}")
                    || Base64.getUrlDecoder().decode(request.getToken()).length != 32) throw new IllegalArgumentException();
            address(request.getRecipient());
            if (!Set.of(LinkPurpose.VERIFY_EMAIL, LinkPurpose.RESET_PASSWORD, LinkPurpose.CHANGE_PASSWORD).contains(request.getPurpose())) throw new IllegalArgumentException();
            var expiry = Instant.ofEpochSecond(request.getExpiresAtEpochSeconds());
            if (!expiry.isAfter(Instant.now()) || expiry.isAfter(Instant.now().plusSeconds(86700))) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid private delivery request."); }
        byte[] fingerprint;
        try { fingerprint = MessageDigest.getInstance("SHA-256").digest(request.toByteArray()); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        int inserted = jdbc.update("INSERT INTO email.deliveries(id,payload_hash) VALUES (?,?) ON CONFLICT (id) DO NOTHING", id, fingerprint);
        if (inserted == 0) {
            var row = jdbc.queryForMap("SELECT payload_hash,delivered FROM email.deliveries WHERE id = ? FOR UPDATE", id);
            if (!MessageDigest.isEqual((byte[]) row.get("payload_hash"), fingerprint)) throw new IllegalArgumentException("Delivery identity mismatch.");
            if (Boolean.TRUE.equals(row.get("delivered"))) return;
        }
        String subject, route;
        switch (request.getPurpose()) {
            case VERIFY_EMAIL -> { subject = "Xác nhận email tài khoản Agora"; route = "verify-email"; }
            case RESET_PASSWORD -> { subject = "Đặt lại mật khẩu Agora"; route = "reset-password"; }
            case CHANGE_PASSWORD -> { subject = "Xác nhận đổi mật khẩu Agora"; route = "confirm-password-change"; }
            default -> throw new IllegalArgumentException();
        }
        address(from);
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(request.getRecipient()); message.setSubject(subject);
        message.setText(subject + "\n\nNếu bạn đã yêu cầu thao tác này, mở link rồi xác nhận trên website:\n"
                + origin + "/" + route + "#token=" + request.getToken() + "\n\nLink chỉ dùng một lần, hết hạn lúc "
                + Instant.ofEpochSecond(request.getExpiresAtEpochSeconds()) + ". Nếu không phải bạn yêu cầu, bỏ qua email này. Không chia sẻ link.");
        sender.send(message);
        jdbc.update("UPDATE email.deliveries SET delivered = true WHERE id = ?", id);
    }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        jdbc.update("DELETE FROM email.deliveries WHERE created_at < clock_timestamp() - interval '7 days'");
    }
    private static void address(String value) {
        try {
            if (value.isEmpty() || value.length() > 254 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) throw new IllegalArgumentException();
            var address = new InternetAddress(value, true); address.validate();
            if (!address.getAddress().equals(value)) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid email address."); }
    }
}
