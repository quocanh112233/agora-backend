package com.agora.payment.adapter.outbound;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class VnpayCodecTests {
    private static final String KEY = "synthetic-test-key-only";
    private static final URI RETURN = URI.create("https://shop.example/payments/return");
    private final VnpayCodec codec = new VnpayCodec("TESTCODE", KEY, RETURN);
    private final VnpayCodec.Expected expected = new VnpayCodec.Expected("abc123", 5000);
    private static final Instant CREATED = Instant.parse("2026-10-27T16:59:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-27T17:04:00Z");

    private Map<String, String> fields() {
        return new TreeMap<>(Map.of("vnp_TmnCode", "TESTCODE", "vnp_TxnRef", "abc123",
                "vnp_Amount", "500000", "vnp_ResponseCode", "00",
                "vnp_TransactionStatus", "00", "vnp_TransactionNo", "123456"));
    }

    // Independent fixture signer; the literal golden below was computed with Python hmac, not this codec.
    private String signed(Map<String, String> fields) throws Exception {
        var pairs = new ArrayList<String>();
        new TreeMap<>(fields).forEach((k, v) -> pairs.add(URLEncoder.encode(k, StandardCharsets.US_ASCII)
                + "=" + URLEncoder.encode(v, StandardCharsets.US_ASCII)));
        String canonical = String.join("&", pairs);
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII), "HmacSHA512"));
        return canonical + "&vnp_SecureHash=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test void independentSyntheticGoldenWithoutCurrency() {
        String raw = "vnp_Amount=500000&vnp_ResponseCode=00&vnp_TmnCode=TESTCODE&vnp_TransactionNo=123456&vnp_TransactionStatus=00&vnp_TxnRef=abc123"
                + "&vnp_SecureHash=39194bcc92031d4f4795e15003508d050aa50dfee90998a0b0f2ddf9925db459e951baf8172cd1a159d232fadbb5ffd9d51e7246bf5adcecb76acbd104f5c359";
        assertEquals(VnpayCodec.Outcome.SUCCEEDED, codec.verifyIpn(raw, expected).outcome());
    }

    @Test void redirectIsFixedSignedAndImmutableWithOriginalExpiry() throws Exception {
        var result = codec.redirect("abc123", 5000, CREATED, EXPIRES, "127.0.0.1");
        var params = result.serverParams();
        assertEquals("2.1.0", params.get("vnp_Version"));
        assertEquals("pay", params.get("vnp_Command"));
        assertEquals("500000", params.get("vnp_Amount"));
        assertEquals("20261027235900", params.get("vnp_CreateDate"));
        assertEquals("20261028000400", params.get("vnp_ExpireDate"));
        assertEquals(RETURN.toString(), params.get("vnp_ReturnUrl"));
        assertEquals("VND", params.get("vnp_CurrCode"));
        assertEquals("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?" + signed(params), result.url().toString());
        assertEquals(result, codec.redirect("abc123", 5000, CREATED, EXPIRES, "127.0.0.1"));
        assertThrows(UnsupportedOperationException.class, () -> params.put("vnp_Amount", "1"));
    }

    @Test void boundsAndExactLongScaling() {
        assertEquals("999999999900", codec.redirect("max", 9999999999L, CREATED, EXPIRES, "::1").serverParams().get("vnp_Amount"));
        for (long amount : new long[]{Long.MIN_VALUE, -1, 0, 4999, 10000000000L, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> codec.redirect("abc123", amount, CREATED, EXPIRES, "127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> codec.redirect("abc123", 5000, EXPIRES, CREATED, "127.0.0.1"));
    }

    @Test void fixedReturnConfigurationRejectsUnsafeUrlsAndMissingCredentials() {
        for (String url : List.of("http://shop.example/return", "https://user@shop.example/return", "https://shop.example/return?next=https://evil.example", "https://shop.example/#fragment"))
            assertThrows(IllegalArgumentException.class, () -> new VnpayCodec("TESTCODE", KEY, URI.create(url)));
        assertThrows(IllegalArgumentException.class, () -> new VnpayCodec("TESTCODE", "", RETURN));
    }

    @Test void unknownSignedFieldsPercentPlusAndSpaceDecodeExactlyOnce() throws Exception {
        var f = fields(); f.put("vnp_Extension", "a+b c%20d&=~*");
        String raw = signed(f);
        assertEquals(VnpayCodec.Outcome.SUCCEEDED, codec.verifyIpn(raw, expected).outcome());
        assertEquals(VnpayCodec.Outcome.SUCCEEDED, codec.verifyIpn(raw.replace("+", "%20"), expected).outcome());
        assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(raw.replace("a%2Bb", "a+b"), expected));
        assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(raw.replace("c%2520d", "c%20d"), expected));
        assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(raw.replace("a%2Bb+c%2520d%26%3D%7E*", "changed"), expected));
    }

    @Test void rejectsMalformedRawQueryAndNeverLeaksIt() throws Exception {
        String good = signed(fields());
        for (String raw : List.of("", "x".repeat(8193), good + "&vnp_%54xnRef=abc123", good + "&vnp_TxnRef=abc123",
                good + "&vnp_Ext=%", good + "&vnp_Ext=%GG", good + "&vnp_Ext=%C0%AF", good + "&vnp_Ext=%ED%A0%80",
                good + "&vnp_Ext=%00", good + "&vnp_Ext=%0D%0A", good + "&vnp_Ext=", good + "&",
                good + "&vnp_Ext=" + "x".repeat(513), good + "&" + "vnp_x=x&".repeat(65), good + "&other=x",
                good + "&vnp_Ext=é", good + "&vnp_Ext=%C3%A9")) {
            var ex = assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(raw, expected));
            assertEquals("Invalid VNPAY data", ex.getMessage());
            assertNull(ex.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(null, expected));
    }

    @Test void rejectsInvalidHashesBeforeInterpretingNoticeFields() throws Exception {
        var f = fields(); f.put("vnp_Amount", "not-money");
        assertEquals("Invalid VNPAY checksum", assertThrows(IllegalArgumentException.class,
                () -> codec.verifyIpn(signed(f).replaceFirst("vnp_SecureHash=.", "vnp_SecureHash=0f"), expected)).getMessage());
        for (String hash : List.of("", "a".repeat(127), "g".repeat(128), "a".repeat(128)))
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn("vnp_SecureHash=" + hash, expected));
    }

    @Test void merchantReferenceAmountAndOptionalCurrencyAreBound() throws Exception {
        for (var e : Map.of("vnp_TmnCode", "OTHERONE", "vnp_TxnRef", "other", "vnp_Amount", "500001", "vnp_CurrCode", "USD").entrySet()) {
            var f = fields(); f.put(e.getKey(), e.getValue());
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(signed(f), expected));
        }
        var f = fields(); f.put("vnp_CurrCode", "VND");
        assertEquals(VnpayCodec.Outcome.SUCCEEDED, codec.verifyIpn(signed(f), expected).outcome());
        for (String amount : List.of("9223372036854775808", "-500000", "+500000", "500000.0", "0000005000000")) {
            f.put("vnp_Amount", amount);
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(signed(f), expected));
        }
    }

    @Test void requiredNoticeFieldsAndTheirBounds() throws Exception {
        for (String key : fields().keySet()) {
            var f = fields(); f.remove(key);
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(signed(f), expected));
        }
        for (var e : Map.of("vnp_TxnRef", "x".repeat(101), "vnp_TransactionNo", "1".repeat(16), "vnp_ResponseCode", "000", "vnp_TransactionStatus", "xx").entrySet()) {
            var f = fields(); f.put(e.getKey(), e.getValue());
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(signed(f), expected));
        }
    }

    @Test void unsupportedLegacyHashTypeRejectedAndSupportedTypeExcludedFromSignature() throws Exception {
        String raw = signed(fields());
        assertEquals(VnpayCodec.Outcome.SUCCEEDED, codec.verifyIpn(raw + "&vnp_SecureHashType=HmacSHA512", expected).outcome());
        for (String type : List.of("MD5", "SHA256", "SHA512"))
            assertThrows(IllegalArgumentException.class, () -> codec.verifyIpn(raw + "&vnp_SecureHashType=" + type, expected));
    }

    @Test void onlyDoubleZeroSucceedsAndAmbiguousStatusesRemainUnknown() throws Exception {
        for (String status : List.of("01", "04", "05", "06", "07", "09", "99")) {
            var f = fields(); f.put("vnp_TransactionStatus", status);
            assertEquals(VnpayCodec.Outcome.UNKNOWN, codec.verifyIpn(signed(f), expected).outcome());
        }
        var f = fields(); f.put("vnp_ResponseCode", "24"); f.put("vnp_TransactionStatus", "02");
        assertEquals(VnpayCodec.Outcome.FAILED, codec.verifyIpn(signed(f), expected).outcome());
        f.put("vnp_ResponseCode", "07");
        assertEquals(VnpayCodec.Outcome.UNKNOWN, codec.verifyIpn(signed(f), expected).outcome());
        f.put("vnp_ResponseCode", "00");
        assertEquals(VnpayCodec.Outcome.UNKNOWN, codec.verifyIpn(signed(f), expected).outcome());
    }

    @Test void returnProvidesCorrelationOnlyNeverMonetarySuccess() throws Exception {
        assertEquals(new VnpayCodec.ReturnReference("abc123"), codec.verifyReturn(signed(fields()), expected));
        assertArrayEquals(new String[]{"txnRef"}, Arrays.stream(VnpayCodec.ReturnReference.class.getRecordComponents()).map(c -> c.getName()).toArray(String[]::new));
        assertThrows(IllegalArgumentException.class, () -> codec.verifyReturn("vnp_ResponseCode=00", expected));
    }

    @Test void authenticationRejectsBadChecksumBeforeExposingLookupReference() throws Exception {
        var lookupRef = new java.util.concurrent.atomic.AtomicReference<String>();
        String tampered = signed(fields()).replace("vnp_TxnRef=abc123", "vnp_TxnRef=other");
        assertEquals("Invalid VNPAY checksum", assertThrows(IllegalArgumentException.class,
                () -> lookupRef.set(codec.authenticate(tampered).txnRef())).getMessage());
        assertNull(lookupRef.get());
    }

    @Test void authenticationChecksMerchantAndReferenceBeforeLookup() throws Exception {
        for (var entry : Map.of("vnp_TmnCode", "OTHERONE", "vnp_TxnRef", "x".repeat(101)).entrySet()) {
            var f = fields(); f.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> codec.authenticate(signed(f)));
        }
    }

    @Test void authenticatedReferenceBindsOnlyAfterExpectedIsLoaded() throws Exception {
        var authenticated = codec.authenticate(signed(fields()));
        assertEquals("abc123", authenticated.txnRef());
        assertEquals(codec.verifyIpn(signed(fields()), expected), authenticated.bind(expected));
        for (var mismatch : List.of(new VnpayCodec.Expected("other", 5000), new VnpayCodec.Expected("abc123", 5001))) {
            assertThrows(IllegalArgumentException.class, () -> authenticated.bind(mismatch));
            assertThrows(IllegalArgumentException.class, () -> authenticated.bindReturn(mismatch));
        }
        var malformed = fields(); malformed.put("vnp_Amount", "not-money");
        var referenceOnly = codec.authenticate(signed(malformed));
        assertEquals("abc123", referenceOnly.txnRef());
        assertThrows(IllegalArgumentException.class, () -> referenceOnly.bind(expected));
        assertEquals(0, authenticated.getClass().getConstructors().length);
        assertFalse(Arrays.stream(authenticated.getClass().getMethods())
                .anyMatch(m -> m.getName().equals("outcome") || m.getName().equals("fields")));
    }

    @Test void authenticatedReturnStillHasNoMonetaryOutcome() throws Exception {
        var authenticated = codec.authenticate(signed(fields()));
        assertEquals(new VnpayCodec.ReturnReference("abc123"), authenticated.bindReturn(expected));
        assertEquals(codec.verifyReturn(signed(fields()), expected), authenticated.bindReturn(expected));
        assertArrayEquals(new String[]{"txnRef"}, Arrays.stream(authenticated.bindReturn(expected).getClass()
                .getRecordComponents()).map(c -> c.getName()).toArray(String[]::new));
    }

    @org.junit.jupiter.api.Test void allowsHttpOnlyForExactLoopbackSandboxReturn(){
        var codec=new VnpayCodec("TEST0001","synthetic-local-secret",java.net.URI.create("http://127.0.0.1:3000/api/v1/payments/vnpay/return"));
        org.assertj.core.api.Assertions.assertThat(codec.redirect("abcd1234",5000,java.time.Instant.parse("2026-10-27T12:00:00Z"),java.time.Instant.parse("2026-10-27T12:15:00Z"),"127.0.0.1").url().toString()).contains("vnp_ReturnUrl=http");
        for(String url:java.util.List.of("http://example.test/return","http://127.0.0.1.example.test/return","http://127.0.0.2/return"))org.assertj.core.api.Assertions.assertThatThrownBy(()->new VnpayCodec("TEST0001","synthetic-local-secret",java.net.URI.create(url))).isInstanceOf(IllegalArgumentException.class);
    }

}
