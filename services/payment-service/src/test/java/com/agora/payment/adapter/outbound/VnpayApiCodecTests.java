package com.agora.payment.adapter.outbound;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VnpayApiCodecTests {
    private static final String KEY = "synthetic-test-key-only";
    private static final String DATE = "20261028123000";
    private static final String CREATED = "20261028130000";
    private final VnpayApiCodec codec = new VnpayApiCodec("TESTCODE", KEY);
    private final VnpayApiCodec.Expected expected = new VnpayApiCodec.Expected("abc123", 5000);

    private VnpayApiCodec.Request query() {
        return codec.query("req123", expected, DATE, CREATED, "127.0.0.1", "Query order abc123");
    }

    private VnpayApiCodec.Request refund() {
        return codec.refund("req124", expected, "123456", DATE, "operator", CREATED,
                "127.0.0.1", "Refund order abc123");
    }

    private Map<String, String> response(boolean refund) {
        var f = new HashMap<String, String>();
        f.put("vnp_ResponseId", "resp123");
        f.put("vnp_Command", refund ? "refund" : "querydr");
        f.put("vnp_ResponseCode", "00");
        f.put("vnp_Message", "Request successful");
        f.put("vnp_TmnCode", "TESTCODE");
        f.put("vnp_TxnRef", "abc123");
        f.put("vnp_Amount", "500000");
        f.put("vnp_BankCode", "NCB");
        f.put("vnp_PayDate", DATE);
        f.put("vnp_TransactionNo", "123456");
        f.put("vnp_TransactionType", refund ? "02" : "01");
        f.put("vnp_TransactionStatus", "00");
        f.put("vnp_OrderInfo", "Order abc123");
        return f;
    }

    // Independent fixture uses the literal official field order, never the production canonicalizer.
    private Map<String, String> signed(Map<String, String> f, boolean refund) throws Exception {
        String canonical = String.join("|", List.of("ResponseId", "Command", "ResponseCode", "Message",
                "TmnCode", "TxnRef", "Amount", "BankCode", "PayDate", "TransactionNo", "TransactionType",
                "TransactionStatus", "OrderInfo").stream().map(k -> f.getOrDefault("vnp_" + k, "")).toList());
        if (!refund) canonical += "|" + f.getOrDefault("vnp_PromotionCode", "")
                + "|" + f.getOrDefault("vnp_PromotionAmount", "");
        var result = new HashMap<>(f);
        result.put("vnp_SecureHash", hash(canonical));
        return result;
    }

    private String hash(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void queryUsesOfficialPipeOrderAndStoredTransactionDate() throws Exception {
        var f = query().params();
        assertEquals(10, f.size());
        assertEquals("2.1.0", f.get("vnp_Version"));
        assertEquals("querydr", f.get("vnp_Command"));
        assertEquals(DATE, f.get("vnp_TransactionDate"));
        assertEquals(hash("req123|2.1.0|querydr|TESTCODE|abc123|20261028123000|20261028130000|127.0.0.1|Query order abc123"),
                f.get("vnp_SecureHash"));
        assertFalse(f.containsKey("vnp_Amount"));
        assertThrows(UnsupportedOperationException.class, () -> f.put("vnp_TxnRef", "other"));
    }

    @Test void refundUsesOfficialOrderFullRefundTypeAndScaledAmount() throws Exception {
        var f = refund().params();
        assertEquals(14, f.size());
        assertEquals("02", f.get("vnp_TransactionType"));
        assertEquals("500000", f.get("vnp_Amount"));
        assertEquals(hash("req124|2.1.0|refund|TESTCODE|02|abc123|500000|123456|20261028123000|operator|20261028130000|127.0.0.1|Refund order abc123"),
                f.get("vnp_SecureHash"));
    }

    @Test void positiveResponsesExposeSignedTransactionAndStatus() throws Exception {
        for (boolean refund : List.of(false, true)) {
            var result = verify(signed(response(refund), refund), refund);
            assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, result.outcome());
            assertEquals("123456", result.transactionNo());
            assertEquals("00", result.status());
        }
    }

    private VnpayApiCodec.Result verify(Map<?, ?> f, boolean refund) {
        return refund ? codec.verifyRefund(f, expected) : codec.verifyQuery(f, expected);
    }

    @Test void queryPromotionsAreOptionalEmptyAndSignedWhenPresent() throws Exception {
        var f = response(false);
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, verify(signed(f, false), false).outcome());
        f.put("vnp_PromotionCode", "123"); f.put("vnp_PromotionAmount", "10000");
        var good = signed(f, false);
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, verify(good, false).outcome());
        good.put("vnp_PromotionAmount", "20000");
        assertUnknown(good, false);
        f.put("vnp_PromotionCode", ""); f.put("vnp_PromotionAmount", "");
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, verify(signed(f, false), false).outcome());
    }

    @Test void refundNeverUsesQueryResponseCanonical() throws Exception {
        assertUnknown(signed(response(true), false), true);
        assertUnknown(signed(response(false), true), false);
    }

    @Test void merchantReferenceAmountCommandAndTypeMustAllBind() throws Exception {
        for (boolean refund : List.of(false, true)) {
            for (var entry : Map.of("vnp_TmnCode", "OTHERONE", "vnp_TxnRef", "other",
                    "vnp_Amount", "500001", "vnp_Command", refund ? "querydr" : "refund",
                    "vnp_TransactionType", refund ? "01" : "02").entrySet()) {
                var f = response(refund); f.put(entry.getKey(), entry.getValue());
                assertUnknown(signed(f, refund), refund);
            }
            var f = response(refund); f.put("vnp_TransactionType", "03");
            assertUnknown(signed(f, refund), refund);
        }
    }

    @Test void queryNotFoundDuplicateAndRefundAcceptedAreUnknownWithoutMonetaryProof() throws Exception {
        for (boolean refund : List.of(false, true)) {
            for (String api : List.of("91", "94", "95", "97", "99")) {
                var f = response(refund); f.put("vnp_ResponseCode", api);
                assertUnknown(signed(f, refund), refund);
            }
            for (String status : List.of("01", "02", "04", "05", "06", "07", "09", "99")) {
                var f = response(refund); f.put("vnp_TransactionStatus", status);
                var result = verify(signed(f, refund), refund);
                assertEquals(VnpayApiCodec.Outcome.UNKNOWN, result.outcome());
                assertEquals(status, result.status());
            }
            assertUnknown(Map.of("vnp_ResponseCode", "00"), refund);
        }
    }

    @Test void absentTimeoutMalformedAndMissingFieldsCannotProveSuccess() throws Exception {
        for (boolean refund : List.of(false, true)) {
            assertUnknown(null, refund);
            assertUnknown(Map.of(), refund);
            for (String key : response(refund).keySet()) {
                if (key.equals("vnp_PayDate")) continue;
                var f = response(refund); f.remove(key);
                assertUnknown(signed(f, refund), refund);
            }
            var f = response(refund); f.remove("vnp_PayDate");
            assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, verify(signed(f, refund), refund).outcome());
        }
    }

    @Test void hashIsRequiredExactHexCaseInsensitiveAndTamperEvident() throws Exception {
        for (boolean refund : List.of(false, true)) {
            var good = signed(response(refund), refund);
            good.put("vnp_SecureHash", good.get("vnp_SecureHash").toUpperCase(java.util.Locale.ROOT));
            assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, verify(good, refund).outcome());
            for (String hash : List.of("", "a".repeat(127), "g".repeat(128), "0".repeat(128))) {
                var f = signed(response(refund), refund); f.put("vnp_SecureHash", hash);
                assertUnknown(f, refund);
            }
            good.put("vnp_Message", "Changed signed message");
            assertUnknown(good, refund);
        }
    }

    @Test void rejectsNonStringNullUnknownOverlongAndDelimiterInjectedMaps() throws Exception {
        for (boolean refund : List.of(false, true)) {
            for (Object value : new Object[]{null, 500000, List.of("500000"), "x".repeat(513), "bad|field", "bad\nfield", "bad\u0000field"}) {
                var f = new HashMap<Object, Object>(signed(response(refund), refund)); f.put("vnp_Amount", value);
                assertUnknown(f, refund);
            }
            for (Object key : new Object[]{null, 1, "unknown", "vnp_Extension", "vnp_" + "x".repeat(65)}) {
                var f = new HashMap<Object, Object>(signed(response(refund), refund)); f.put(key, "x");
                assertUnknown(f, refund);
            }
            var f = new HashMap<Object, Object>();
            for (int i = 0; i < 65; i++) f.put("vnp_" + i, "x");
            assertUnknown(f, refund);
        }
    }

    @Test void responseFieldBoundsAndNonzeroTransactionAreEnforced() throws Exception {
        for (boolean refund : List.of(false, true)) {
            for (var entry : Map.of("vnp_ResponseId", "x".repeat(33), "vnp_Message", "x".repeat(256),
                    "vnp_OrderInfo", "x".repeat(256), "vnp_BankCode", "x".repeat(21),
                    "vnp_Amount", "0000000500000", "vnp_ResponseCode", "000",
                    "vnp_TransactionStatus", "x0", "vnp_PayDate", "20260230123000").entrySet()) {
                var f = response(refund); f.put(entry.getKey(), entry.getValue());
                assertUnknown(signed(f, refund), refund);
            }
            for (String no : List.of("0", "000000", "-1", "x", "1".repeat(16))) {
                var f = response(refund); f.put("vnp_TransactionNo", no);
                assertUnknown(signed(f, refund), refund);
            }
        }
    }

    @Test void requestBoundsDatesIpAndDelimiterValidation() {
        for (String id : List.of("", "x".repeat(33), "bad|id"))
            assertThrows(IllegalArgumentException.class, () -> codec.query(id, expected, DATE, CREATED, "127.0.0.1", "Order"));
        for (String date : List.of("20260230123000", "2026102812300", "not-a-date"))
            assertThrows(IllegalArgumentException.class, () -> codec.query("id", expected, date, CREATED, "127.0.0.1", "Order"));
        for (String ip : List.of("localhost", "127.0.0.999", "host.example", "bad|ip"))
            assertThrows(IllegalArgumentException.class, () -> codec.query("id", expected, DATE, CREATED, ip, "Order"));
        for (String info : List.of("", "x".repeat(256), "bad|info", "bad\ninfo", "bad\uD800info"))
            assertThrows(IllegalArgumentException.class, () -> codec.query("id", expected, DATE, CREATED, "127.0.0.1", info));
        assertThrows(IllegalArgumentException.class, () -> codec.refund("id", expected, "0", DATE, "operator", CREATED, "127.0.0.1", "Order"));
        assertThrows(IllegalArgumentException.class, () -> codec.refund("id", expected, "123", DATE, "x".repeat(246), CREATED, "127.0.0.1", "Order"));
    }

    @Test void exactAmountBoundsAndSafeCredentials() {
        for (long amount : new long[]{Long.MIN_VALUE, -1, 0, 4999, 10000000000L, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec.Expected("abc123", amount));
        var max = new VnpayApiCodec.Expected("max", 9999999999L);
        assertEquals("999999999900", codec.refund("id", max, "123", DATE, "operator", CREATED, "::1", "Order").params().get("vnp_Amount"));
        assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec.Expected("bad|ref", 5000));
        for (String secret : List.of("", " ", "x".repeat(513), "secret\n"))
            assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec("TESTCODE", secret));
        assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec("bad", KEY));
        assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec("TESTCODE", null));
    }

    @Test void requestRecordCopiesAndBoundsItsMap() {
        var original = new HashMap<>(query().params());
        var request = new VnpayApiCodec.Request(original);
        original.put("vnp_TxnRef", "changed");
        assertEquals("abc123", request.params().get("vnp_TxnRef"));
        assertThrows(IllegalArgumentException.class, () -> new VnpayApiCodec.Request(Map.of("vnp_OrderInfo", "bad|info")));
    }

    private Map<String, String> refundQueryResponse() {
        var f = response(false);
        f.put("vnp_TransactionType", "02");
        return f;
    }

    @Test void refundQueryUsesQueryCanonicalWithOptionalSignedPromotions() throws Exception {
        var f = refundQueryResponse();
        var result = codec.verifyRefundQuery(signed(f, false), expected, "123456");
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, result.outcome());
        assertEquals("123456", result.transactionNo());
        assertEquals("00", result.status());
        f.put("vnp_PromotionCode", "123"); f.put("vnp_PromotionAmount", "10000");
        var signed = signed(f, false);
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, codec.verifyRefundQuery(signed, expected, "123456").outcome());
        signed.put("vnp_PromotionAmount", "10001");
        assertRefundQueryUnknown(signed, "123456");
        f.put("vnp_PromotionCode", ""); f.put("vnp_PromotionAmount", "");
        assertEquals(VnpayApiCodec.Outcome.SUCCEEDED, codec.verifyRefundQuery(signed(f, false), expected, "123456").outcome());
        assertRefundQueryUnknown(signed(f, true), "123456");
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN, codec.verifyQuery(signed(f, false), expected).outcome());
    }

    @Test void refundQueryBindsNormalizedExactNonzeroRefundNumber() throws Exception {
        for (String actual : List.of("123456", "000123456")) {
            var f = refundQueryResponse(); f.put("vnp_TransactionNo", actual);
            for (String known : List.of("123456", "000123456"))
                assertEquals(VnpayApiCodec.Outcome.SUCCEEDED,
                        codec.verifyRefundQuery(signed(f, false), expected, known).outcome());
            assertRefundQueryUnknown(signed(f, false), "123457");
        }
        for (String invalid : new String[]{null, "", "0", "0000", "-123456", "+123456", "123456 ", "123|456", "1".repeat(16)}) {
            assertRefundQueryUnknown(signed(refundQueryResponse(), false), invalid);
            var f = refundQueryResponse();
            if (invalid == null) f.remove("vnp_TransactionNo"); else f.put("vnp_TransactionNo", invalid);
            assertRefundQueryUnknown(signed(f, false), "123456");
        }
    }

    @Test void refundQueryRequiresQueryCommandFullRefundTypeAndAllMonetaryBindings() throws Exception {
        for (var e : Map.of("vnp_Command", "refund", "vnp_TmnCode", "OTHERONE",
                "vnp_TxnRef", "other", "vnp_Amount", "500001").entrySet()) {
            var f = refundQueryResponse(); f.put(e.getKey(), e.getValue());
            assertRefundQueryUnknown(signed(f, false), "123456");
        }
        for (String type : List.of("01", "03", "00")) {
            var f = refundQueryResponse(); f.put("vnp_TransactionType", type);
            assertRefundQueryUnknown(signed(f, false), "123456");
        }
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN,
                codec.verifyRefundQuery(signed(refundQueryResponse(), false), new VnpayApiCodec.Expected("other", 5000), "123456").outcome());
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN,
                codec.verifyRefundQuery(signed(refundQueryResponse(), false), new VnpayApiCodec.Expected("abc123", 5001), "123456").outcome());
    }

    @Test void refundQueryNeedsSignedDoubleZeroNotApiAcceptanceOrPendingStatus() throws Exception {
        for (String api : List.of("91", "94", "95", "97", "99")) {
            var f = refundQueryResponse(); f.put("vnp_ResponseCode", api);
            assertRefundQueryUnknown(signed(f, false), "123456");
        }
        for (String status : List.of("01", "02", "04", "05", "06", "07", "09", "99")) {
            var f = refundQueryResponse(); f.put("vnp_TransactionStatus", status);
            var result = codec.verifyRefundQuery(signed(f, false), expected, "123456");
            assertEquals(VnpayApiCodec.Outcome.UNKNOWN, result.outcome());
            assertEquals(status, result.status());
        }
        var f = signed(refundQueryResponse(), false);
        f.put("vnp_Message", "Tampered refund query message");
        assertRefundQueryUnknown(f, "123456");
        f = refundQueryResponse(); f.put("vnp_SecureHash", "0".repeat(128));
        assertRefundQueryUnknown(f, "123456");
    }

    @Test void refundQueryAbsentMalformedAndMissingEvidenceStaysUnknown() throws Exception {
        assertRefundQueryUnknown(null, "123456");
        assertRefundQueryUnknown(Map.of(), "123456");
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN,
                codec.verifyRefundQuery(signed(refundQueryResponse(), false), null, "123456").outcome());
        for (String key : refundQueryResponse().keySet()) {
            if (key.equals("vnp_PayDate")) continue;
            var f = refundQueryResponse(); f.remove(key);
            assertRefundQueryUnknown(signed(f, false), "123456");
        }
        var f = new HashMap<Object, Object>(signed(refundQueryResponse(), false));
        f.put("vnp_Amount", 500000);
        assertRefundQueryUnknown(f, "123456");
        var injected = refundQueryResponse(); injected.put("vnp_Message", "bad|message");
        assertRefundQueryUnknown(signed(injected, false), "123456");
    }

    private void assertRefundQueryUnknown(Map<?, ?> fields, String knownTransactionNo) {
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN,
                codec.verifyRefundQuery(fields, expected, knownTransactionNo).outcome());
    }

    private void assertUnknown(Map<?, ?> f, boolean refund) {
        assertEquals(VnpayApiCodec.Outcome.UNKNOWN, verify(f, refund).outcome());
    }
}
