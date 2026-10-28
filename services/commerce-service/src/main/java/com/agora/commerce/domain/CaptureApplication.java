package com.agora.commerce.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class CaptureApplication {
    private CaptureApplication() {}
    public record Request(UUID captureId,UUID attemptId,UUID buyerId,UUID orderId,String providerTransactionNo,long amountVnd,String currency) {
        public Request {
            if(captureId==null||attemptId==null||buyerId==null||orderId==null||providerTransactionNo==null||!providerTransactionNo.matches("[0-9]{1,15}")||providerTransactionNo.matches("0+")||!"VND".equals(currency)||amountVnd<1||amountVnd>9999999999L)
                throw new CartFailure(422,"INVALID_CAPTURE","Provide a valid capture.");
        }
        public String hash(){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((captureId+"|"+attemptId+"|"+buyerId+"|"+orderId+"|"+providerTransactionNo+"|"+amountVnd+"|"+currency).getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
    }
    public record Receipt(UUID captureId,UUID buyerId,UUID orderId,String state,String reason,boolean needsReconciliation) {}
    public record OwnedOrder(UUID buyerId,UUID checkoutId,Checkout.Order order) {}
    public record Work(Receipt receipt,long revision,OwnedOrder order) {}
}
