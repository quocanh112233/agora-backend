package com.agora.payment.adapter.outbound;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure PAY 2.1.0 wire codec. Verified IPN evidence still requires durable application and replay checks. */
public final class VnpayCodec {
    private static final String GATEWAY = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final Set<String> DECLINED = Set.of("09", "10", "11", "12", "13", "24", "51", "65", "75", "79");
    private final String merchant;
    private final SecretKeySpec key;
    private final URI returnUrl;

    /** Allow plaintext only for the exact loopback sandbox return; public returns require HTTPS. */
    public VnpayCodec(String merchant, String hashSecret, URI returnUrl) {
        require(matches(merchant, "[A-Za-z0-9]{8}"));
        require(hashSecret != null && !hashSecret.isBlank() && hashSecret.length() <= 512);
        require(hashSecret.chars().allMatch(c -> c >= 33 && c <= 126));
        require(returnUrl != null && ("https".equals(returnUrl.getScheme())
                || "http".equals(returnUrl.getScheme()) && "127.0.0.1".equals(returnUrl.getHost())) && returnUrl.getHost() != null
                && returnUrl.getRawUserInfo() == null && returnUrl.getRawQuery() == null && returnUrl.getRawFragment() == null);
        require(returnUrl.toString().length() >= 10 && returnUrl.toString().length() <= 255);
        require(returnUrl.toString().chars().allMatch(c -> c >= 33 && c <= 126));
        this.merchant = merchant;
        this.key = new SecretKeySpec(hashSecret.getBytes(StandardCharsets.US_ASCII), "HmacSHA512");
        this.returnUrl = returnUrl;
    }

    public record Expected(String txnRef, long amountVnd) {
        public Expected {
            require(matches(txnRef, "[A-Za-z0-9]{1,100}"));
            scaled(amountVnd);
        }
    }

    /** Parameters exclude the checksum and are the immutable ingredients to persist with the attempt. */
    public record Redirect(URI url, Map<String, String> serverParams) {
        public Redirect { serverParams = Map.copyOf(serverParams); }
    }

    public enum Outcome { SUCCEEDED, FAILED, UNKNOWN }
    public record Notice(String txnRef, long amountVnd, String transactionNo,
                         String responseCode, String transactionStatus, Outcome outcome) { }
    /** Correlation only: this type deliberately carries no monetary outcome. */
    public record ReturnReference(String txnRef) { }

    public Redirect redirect(String txnRef, long amountVnd, Instant createdAt, Instant expiresAt, String ipAddress) {
        new Expected(txnRef, amountVnd);
        require(createdAt != null && expiresAt != null && expiresAt.isAfter(createdAt));
        String created = TIME.format(createdAt);
        String expires = TIME.format(expiresAt);
        require(matches(created, "[0-9]{14}") && matches(expires, "[0-9]{14}") && expires.compareTo(created) > 0);
        require(ipAddress != null && ipAddress.length() >= 3 && ipAddress.length() <= 45);
        try {
            InetAddress.ofLiteral(ipAddress);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        var params = new TreeMap<String, String>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", merchant);
        params.put("vnp_Amount", Long.toString(scaled(amountVnd)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_CreateDate", created);
        params.put("vnp_ExpireDate", expires);
        params.put("vnp_IpAddr", ipAddress);
        params.put("vnp_Locale", "vn");
        params.put("vnp_OrderType", "other");
        params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
        params.put("vnp_ReturnUrl", returnUrl.toString());
        String canonical = canonical(params);
        return new Redirect(URI.create(GATEWAY + "?" + canonical + "&vnp_SecureHash="
                + HexFormat.of().formatHex(hmac(canonical))), params);
    }

    /** Authenticate before looking up an attempt. No financial result is exposed until binding. */
    public AuthenticatedReference authenticate(String rawQuery) {
        Map<String, String> fields = verified(rawQuery);
        require(merchant.equals(fields.get("vnp_TmnCode")));
        require(matches(fields.get("vnp_TxnRef"), "[A-Za-z0-9]{1,100}"));
        return new AuthenticatedReference(fields);
    }

    public static final class AuthenticatedReference {
        private final Map<String, String> fields;

        private AuthenticatedReference(Map<String, String> fields) {
            this.fields = Map.copyOf(fields);
        }

        public String txnRef() { return fields.get("vnp_TxnRef"); }

        public Notice bind(Expected expected) { return boundNotice(fields, expected); }

        public ReturnReference bindReturn(Expected expected) {
            VnpayCodec.bind(fields, expected);
            return new ReturnReference(txnRef());
        }
    }

    public Notice verifyIpn(String rawQuery, Expected expected) {
        return authenticate(rawQuery).bind(expected);
    }

    private static Notice boundNotice(Map<String, String> fields, Expected expected) {
        bind(fields, expected);
        String transactionNo = fields.get("vnp_TransactionNo");
        String response = fields.get("vnp_ResponseCode");
        String status = fields.get("vnp_TransactionStatus");
        require(matches(transactionNo, "[0-9]{1,15}"));
        require(matches(response, "[0-9]{2}") && matches(status, "[0-9]{2}"));
        Outcome outcome = Outcome.UNKNOWN;
        if ("00".equals(response) && "00".equals(status)) {
            require(!transactionNo.chars().allMatch(c -> c == '0'));
            outcome = Outcome.SUCCEEDED;
        } else if ("02".equals(status) && DECLINED.contains(response)) {
            outcome = Outcome.FAILED;
        }
        return new Notice(expected.txnRef(), expected.amountVnd(), transactionNo, response, status, outcome);
    }

    public ReturnReference verifyReturn(String rawQuery, Expected expected) {
        return authenticate(rawQuery).bindReturn(expected);
    }

    private static void bind(Map<String, String> fields, Expected expected) {
        require(expected != null);
        require(expected.txnRef().equals(fields.get("vnp_TxnRef")));
        String amount = fields.get("vnp_Amount");
        require(matches(amount, "[0-9]{1,12}"));
        require(Long.parseLong(amount) == scaled(expected.amountVnd()));
        require(!fields.containsKey("vnp_CurrCode") || "VND".equals(fields.get("vnp_CurrCode")));
    }

    private Map<String, String> verified(String rawQuery) {
        Map<String, String> fields = parse(rawQuery);
        String hash = fields.remove("vnp_SecureHash");
        String type = fields.remove("vnp_SecureHashType");
        // Legacy metadata is not signed by PAY; only our configured HMAC algorithm is accepted.
        require(type == null || "HmacSHA512".equals(type));
        if (!matches(hash, "[0-9a-fA-F]{128}") || !MessageDigest.isEqual(
                hmac(canonical(fields)), HexFormat.of().parseHex(hash))) {
            throw new IllegalArgumentException("Invalid VNPAY checksum");
        }
        return fields;
    }

    // Bound before allocating/splitting; reject duplicates after decoding, including encoded names.
    private static Map<String, String> parse(String rawQuery) {
        require(rawQuery != null && !rawQuery.isEmpty() && rawQuery.length() <= 8192);
        require(rawQuery.chars().allMatch(c -> c >= 33 && c <= 126));
        String[] pairs = rawQuery.split("&", -1);
        require(pairs.length <= 64);
        var fields = new TreeMap<String, String>();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            require(equals > 0);
            String name = decode(pair.substring(0, equals));
            String value = decode(pair.substring(equals + 1));
            require(matches(name, "vnp_[A-Za-z0-9_]{1,60}"));
            require(!value.isEmpty() && value.length() <= 512);
            require(fields.putIfAbsent(name, value) == null);
        }
        return fields;
    }

    private static String decode(String raw) {
        var bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                require(i + 2 < raw.length());
                int high = Character.digit(raw.charAt(++i), 16);
                int low = Character.digit(raw.charAt(++i), 16);
                require(high >= 0 && low >= 0);
                bytes.write(high * 16 + low);
            } else {
                bytes.write(c == '+' ? ' ' : c);
            }
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            // Official Java PAY canonicalization uses US_ASCII: reject instead of lossy replacement.
            require(decoded.chars().allMatch(c -> c >= 32 && c <= 126));
            return decoded;
        } catch (CharacterCodingException e) {
            throw invalid();
        }
    }

    private static String canonical(Map<String, String> fields) {
        return new TreeMap<>(fields).entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(key);
            return mac.doFinal(canonical.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VNPAY HMAC unavailable");
        }
    }

    private static long scaled(long amountVnd) {
        require(amountVnd >= 5000 && amountVnd <= 9999999999L);
        return Math.multiplyExact(amountVnd, 100L);
    }

    private static boolean matches(String value, String regex) { return value != null && value.matches(regex); }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid VNPAY data"); }
}
