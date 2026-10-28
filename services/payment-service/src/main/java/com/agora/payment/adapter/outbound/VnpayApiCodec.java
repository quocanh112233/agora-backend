package com.agora.payment.adapter.outbound;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure PAY 2.1.0 querydr/full-refund codec. Transport and durable replay checks belong to the caller. */
public final class VnpayApiCodec {
    private static final String[] QUERY_REQUEST = fields("RequestId Version Command TmnCode TxnRef TransactionDate CreateDate IpAddr OrderInfo");
    private static final String[] REFUND_REQUEST = fields("RequestId Version Command TmnCode TransactionType TxnRef Amount TransactionNo TransactionDate CreateBy CreateDate IpAddr OrderInfo");
    private static final String[] REFUND_RESPONSE = fields("ResponseId Command ResponseCode Message TmnCode TxnRef Amount BankCode PayDate TransactionNo TransactionType TransactionStatus OrderInfo");
    private static final String[] QUERY_RESPONSE = fields("ResponseId Command ResponseCode Message TmnCode TxnRef Amount BankCode PayDate TransactionNo TransactionType TransactionStatus OrderInfo PromotionCode PromotionAmount");
    private static final Set<String> REQUEST_FIELDS = allowed(REFUND_REQUEST);
    private static final Set<String> OPTIONAL = Set.of("vnp_PayDate", "vnp_PromotionCode", "vnp_PromotionAmount");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Result UNKNOWN = new Result("", "", Outcome.UNKNOWN);
    private final String merchant;
    private final SecretKeySpec key;

    public VnpayApiCodec(String merchant, String hashSecret) {
        require(matches(merchant, "[A-Za-z0-9]{8}"));
        require(hashSecret != null && !hashSecret.isBlank() && hashSecret.length() <= 512);
        require(hashSecret.chars().allMatch(c -> c >= 33 && c <= 126));
        this.merchant = merchant;
        this.key = new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
    }

    public record Expected(String txnRef, long amountVnd) {
        public Expected {
            require(matches(txnRef, "[A-Za-z0-9]{1,100}"));
            scaled(amountVnd);
        }
    }

    /** Immutable JSON string parameters, including the checksum; contains no secret. */
    public record Request(Map<String, String> params) {
        public Request { params = bounded(params, REQUEST_FIELDS); }
    }

    public enum Outcome { SUCCEEDED, UNKNOWN }
    public record Result(String transactionNo, String status, Outcome outcome) { }

    /** transactionDate is the stored original PAY creation date, already expressed in GMT+7. */
    public Request query(String requestId, Expected expected, String transactionDate,
                         String createDate, String ipAddr, String orderInfo) {
        return request(common("querydr", requestId, expected, transactionDate, createDate, ipAddr, orderInfo), QUERY_REQUEST);
    }

    public Request refund(String requestId, Expected expected, String transactionNo, String transactionDate,
                          String createBy, String createDate, String ipAddr, String orderInfo) {
        var f = common("refund", requestId, expected, transactionDate, createDate, ipAddr, orderInfo);
        require(matches(transactionNo, "[0-9]{1,15}") && nonzero(transactionNo));
        f.put("vnp_TransactionType", "02");
        f.put("vnp_Amount", Long.toString(scaled(expected.amountVnd())));
        f.put("vnp_TransactionNo", transactionNo);
        f.put("vnp_CreateBy", createBy);
        return request(f, REFUND_REQUEST);
    }

    public Result verifyQuery(Map<?, ?> response, Expected expected) {
        return verify(response, expected, false, null);
    }

    public Result verifyRefund(Map<?, ?> response, Expected expected) {
        return verify(response, expected, true, null);
    }

    /** Query a known full refund; zero-padded wire transaction numbers bind by their numeric value. */
    public Result verifyRefundQuery(Map<?, ?> response, Expected expected, String knownRefundTransactionNo) {
        if (!matches(knownRefundTransactionNo, "[0-9]{1,15}") || !nonzero(knownRefundTransactionNo))
            return UNKNOWN;
        return verify(response, expected, false, knownRefundTransactionNo);
    }

    private Map<String, String> common(String command, String requestId, Expected expected,
                                       String transactionDate, String createDate, String ipAddr, String orderInfo) {
        require(expected != null);
        var f = new HashMap<String, String>();
        f.put("vnp_RequestId", requestId);
        f.put("vnp_Version", "2.1.0");
        f.put("vnp_Command", command);
        f.put("vnp_TmnCode", merchant);
        f.put("vnp_TxnRef", expected.txnRef());
        f.put("vnp_TransactionDate", transactionDate);
        f.put("vnp_CreateDate", createDate);
        f.put("vnp_IpAddr", ipAddr);
        f.put("vnp_OrderInfo", orderInfo);
        return f;
    }

    private Request request(Map<String, String> f, String[] order) {
        Map<String, String> checked = bounded(f, REQUEST_FIELDS);
        f.put("vnp_SecureHash", HexFormat.of().formatHex(hmac(canonical(checked, order))));
        return new Request(f);
    }

    private Result verify(Map<?, ?> response, Expected expected, boolean refund, String knownRefundTransactionNo) {
        try {
            require(expected != null);
            String[] order = refund ? REFUND_RESPONSE : QUERY_RESPONSE;
            Map<String, String> f = bounded(response, allowed(order));
            String hash = f.get("vnp_SecureHash");
            require(matches(hash, "[0-9a-fA-F]{128}"));
            require(MessageDigest.isEqual(hmac(canonical(f, order)), HexFormat.of().parseHex(hash)));
            require(merchant.equals(f.get("vnp_TmnCode")) && expected.txnRef().equals(f.get("vnp_TxnRef")));
            require(Long.parseLong(f.get("vnp_Amount")) == scaled(expected.amountVnd()));
            require((refund ? "refund" : "querydr").equals(f.get("vnp_Command")));
            require((refund || knownRefundTransactionNo != null ? "02" : "01").equals(f.get("vnp_TransactionType")));
            String no = f.get("vnp_TransactionNo");
            if (knownRefundTransactionNo != null)
                require(Long.parseLong(no) == Long.parseLong(knownRefundTransactionNo));
            String status = f.get("vnp_TransactionStatus");
            boolean success = "00".equals(f.get("vnp_ResponseCode")) && "00".equals(status) && nonzero(no);
            return new Result(no, status, success ? Outcome.SUCCEEDED : Outcome.UNKNOWN);
        } catch (IllegalArgumentException e) {
            // Missing, malformed or unbound evidence cannot establish a financial outcome.
            return UNKNOWN;
        }
    }

    private static String canonical(Map<String, String> f, String[] order) {
        return Arrays.stream(order).map(name -> {
            String value = f.get(name);
            require(value != null || OPTIONAL.contains(name));
            return value == null ? "" : value;
        }).collect(Collectors.joining("|"));
    }

    private static Map<String, String> bounded(Map<?, ?> input, Set<String> allowed) {
        require(input != null && !input.isEmpty() && input.size() <= 32);
        var copy = new HashMap<String, String>();
        int length = 0;
        for (var entry : input.entrySet()) {
            require(entry.getKey() instanceof String && entry.getValue() instanceof String);
            String name = (String) entry.getKey();
            String value = (String) entry.getValue();
            require(name.length() <= 64 && allowed.contains(name) && value.length() <= 512);
            require(value.chars().allMatch(c -> c >= 32 && c <= 126 && c != '|'));
            length += name.length() + value.length();
            require(length <= 8192);
            validate(name, value);
            require(copy.putIfAbsent(name, value) == null);
        }
        return Map.copyOf(copy);
    }

    private static void validate(String name, String value) {
        if (value.isEmpty() && OPTIONAL.contains(name)) return;
        String regex = switch (name) {
            case "vnp_RequestId", "vnp_ResponseId" -> "[A-Za-z0-9]{1,32}";
            case "vnp_Version" -> "2\\.1\\.0";
            case "vnp_Command" -> "querydr|refund";
            case "vnp_TmnCode" -> "[A-Za-z0-9]{8}";
            case "vnp_TxnRef" -> "[A-Za-z0-9]{1,100}";
            case "vnp_Amount", "vnp_PromotionCode", "vnp_PromotionAmount" -> "[0-9]{1,12}";
            case "vnp_TransactionNo" -> "[0-9]{1,15}";
            case "vnp_TransactionType", "vnp_ResponseCode", "vnp_TransactionStatus" -> "[0-9]{2}";
            case "vnp_OrderInfo", "vnp_Message" -> ".{1,255}";
            case "vnp_CreateBy" -> ".{1,245}";
            case "vnp_BankCode" -> "[A-Za-z0-9]{3,20}";
            case "vnp_IpAddr" -> "[0-9a-fA-F:.]{2,45}";
            case "vnp_TransactionDate", "vnp_CreateDate", "vnp_PayDate" -> "[0-9]{14}";
            case "vnp_SecureHash" -> "[0-9a-fA-F]{128}";
            default -> throw invalid();
        };
        require(matches(value, regex));
        if (name.equals("vnp_IpAddr")) InetAddress.ofLiteral(value);
        if (name.equals("vnp_TransactionDate") || name.equals("vnp_CreateDate") || name.equals("vnp_PayDate")) {
            try {
                require(LocalDateTime.parse(value, DATE).getYear() >= 1);
            } catch (DateTimeParseException e) {
                throw invalid();
            }
        }
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(key);
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VNPAY HMAC unavailable");
        }
    }

    private static String[] fields(String names) {
        return Arrays.stream(names.split(" ")).map(name -> "vnp_" + name).toArray(String[]::new);
    }

    private static Set<String> allowed(String[] fields) {
        var names = new java.util.HashSet<>(Arrays.asList(fields));
        names.add("vnp_SecureHash");
        return Set.copyOf(names);
    }

    private static long scaled(long amountVnd) {
        require(amountVnd >= 5000 && amountVnd <= 9999999999L);
        return Math.multiplyExact(amountVnd, 100L);
    }

    private static boolean nonzero(String no) { return no.chars().anyMatch(c -> c != '0'); }
    private static boolean matches(String value, String regex) { return value != null && value.matches(regex); }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid VNPAY data"); }
}
