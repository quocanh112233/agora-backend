package com.agora.payment.domain;
import java.time.Instant;
import java.util.UUID;
public final class Payment {
    private Payment(){}
    public record Attempt(UUID id,UUID orderId,UUID requestKey,String state,Instant expiresAt,String redirectUrl,String applicationState,String refundState,boolean needsReconciliation){}
    public record Target(UUID id,UUID actorId,UUID orderId,UUID requestKey,String txnRef,long amountVnd){}
    public record Notice(String transactionNo,String responseCode,String transactionStatus,String outcome){}
    public record Capture(UUID id,UUID attemptId,UUID actorId,UUID orderId,String providerTransactionNo,long amountVnd){}
    public record Work(Capture capture,long revision){}
    public record Application(String state,String reason,boolean needsReconciliation){}
    public record ProviderResult(String transactionNo,String status,boolean succeeded){public static ProviderResult unknown(){return new ProviderResult("","",false);}}
    public record Created(Attempt attempt,boolean created){}
    public record Payable(UUID orderId,UUID checkoutId,UUID shopId,long amountVnd,Instant expiresAt){}
    public static PaymentFailure missing(){return new PaymentFailure(404,"NOT_FOUND","Payment attempt was not found.");}
    public static PaymentFailure unavailable(){return new PaymentFailure(503,"PAYMENT_UNAVAILABLE","Payment is temporarily unavailable.");}
    public static PaymentFailure conflict(){return new PaymentFailure(409,"ORDER_NOT_PAYABLE","Order already has an unresolved payment.");}
    public static void amount(long amount){if(amount<5000||amount>9999999999L)throw new PaymentFailure(409,"PAYMENT_AMOUNT_UNSUPPORTED","Payment amount is outside the supported range.");}
}
