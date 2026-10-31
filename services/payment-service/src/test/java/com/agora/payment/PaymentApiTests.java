package com.agora.payment;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agora.vnpay.query-enabled=true","agora.vnpay.ipn-enabled=true","spring.config.import=","agora.vnpay.enabled=true","agora.vnpay.tmn-code=TEST0001","agora.vnpay.hash-secret=synthetic-payment-test-secret-not-real","agora.vnpay.return-url=https://app.example.test/api/v1/payments/vnpay/return","agora.payment.worker-initial-delay-ms=3600000"})
@Import(PaymentApiTests.RemoteConfiguration.class)
class PaymentApiTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->PaymentRpcFixture.TOKEN);}
    @TestConfiguration(proxyBeanMethods=false) static class RemoteConfiguration {
        @Bean(destroyMethod="close") PaymentRpcFixture paymentRpcFixture()throws Exception{return new PaymentRpcFixture();}
        @Bean(destroyMethod="") @Primary @Qualifier("identityChannel") io.grpc.ManagedChannel testIdentityChannel(PaymentRpcFixture r){return r.identityChannel;}
        @Bean(destroyMethod="") @Primary @Qualifier("commerceChannel") io.grpc.ManagedChannel testCommerceChannel(PaymentRpcFixture r){return r.commerceChannel;}
    }
    @org.junit.jupiter.api.BeforeEach void resetData(){jdbc.execute("TRUNCATE payment.query_wakeups,payment.ledger_entries,payment.refunds,payment.observations,payment.capture_jobs,payment.provider_queries,payment.captures,payment.attempts");rpc.captures.clear();rpc.applied.clear();rpc.identityFailure.set(null);rpc.commerceFailure.set(null);rpc.afterCaptureFailure.set(null);rpc.captureHook=()->{};rpc.orderHook=()->{};}
    @Autowired PaymentRpcFixture rpc;@Autowired JsonMapper json;@LocalServerPort int port;
    HttpResponse<String> call(String session,String method,String path,String body,String key,boolean csrf)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));if(session!=null)b.header("Cookie","AGORA_SESSION="+session);if(csrf)b.header("X-CSRF-TOKEN","csrf-fixture");if(key!=null)b.header("Idempotency-Key",key);if(body!=null)b.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authenticatedBuyerCreatesServerPricedAttemptAndRecoversByOriginalKey()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();var response=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);var attempt=json.readTree(response.body());assertThat(attempt.path("state").asString()).isEqualTo("PENDING");assertThat(attempt.path("orderId").asString()).isEqualTo(order);assertThat(attempt.path("redirectUrl").asString()).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?").contains("vnp_Amount=7000000");
        var recovered=call(session,"GET","/me/payment-attempts/by-request-key/"+key,null,null,false);assertThat(recovered.statusCode()).isEqualTo(200);assertThat(json.readTree(recovered.body())).isEqualTo(attempt);assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.body()).doesNotContain("synthetic-payment-test-secret-not-real",rpc.sessions.get(session).toString());
    }
    @Test void replaySurvivesCommerceOutageAndRejectsDifferentOrder()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();
        var first=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);assertThat(first.statusCode()).isEqualTo(201);
        rpc.commerceFailure.set(io.grpc.Status.UNAVAILABLE);
        try {var replay=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
            assertThat(call(session,"POST","/me/orders/"+UUID.randomUUID()+"/payment-attempts","{}",key,true).statusCode()).isEqualTo(422);
        }finally{rpc.commerceFailure.set(null);}
    }
    @Test void currentAuthenticationCsrfAndOwnershipProtectReplayAndReads()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();var first=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);assertThat(first.statusCode()).isEqualTo(201);String id=json.readTree(first.body()).path("id").asString();
        assertThat(call(null,"GET","/me/payment-attempts/"+id,null,null,false).statusCode()).isEqualTo(401);
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,false).statusCode()).isEqualTo(403);
        assertThat(call(rpc.account(),"GET","/me/payment-attempts/"+id,null,null,false).statusCode()).isEqualTo(404);
        assertThat(call(rpc.account(),"GET","/me/payment-attempts/by-request-key/"+key,null,null,false).statusCode()).isEqualTo(404);
        assertThat(call(session,"GET","/me/payment-attempts/"+id,null,null,false).statusCode()).isEqualTo(200);
    }
    @Test void strictEmptyBodyAndIdentifiersRejectClientPrice()throws Exception {
        String session=rpc.account(),order=rpc.order(session);for(String body:List.of("{\"amountVnd\":1}","[]","null"))assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts",body,UUID.randomUUID().toString(),true).statusCode()).isEqualTo(422);
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{} {}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(400);
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}","bad",true).statusCode()).isEqualTo(422);
        assertThat(call(session,"GET","/me/payment-attempts/not-a-uuid",null,null,false).statusCode()).isEqualTo(422);
        assertThat(call(session,"GET","/me/payment-attempts/by-request-key/"+UUID.randomUUID()+"?extra=1",null,null,false).statusCode()).isEqualTo(422);
    }
    @Test void unresolvedOrderCannotCreateSecondChargeAndExpiredLinkStaysHidden()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();var first=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);assertThat(first.statusCode()).isEqualTo(201);
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE payment.attempts SET expires_at=clock_timestamp()-interval '1 second' WHERE request_key=?",UUID.fromString(key));
        var recovered=call(session,"GET","/me/payment-attempts/by-request-key/"+key,null,null,false);assertThat(recovered.statusCode()).isEqualTo(200);var node=json.readTree(recovered.body());assertThat(node.path("state").asString()).isEqualTo("EXPIRED");assertThat(node.path("redirectUrl").isNull()).isTrue();
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
    }
    @Test void validatesRemoteOrderBeforePersistingAttempt()throws Exception {
        String session=rpc.account(),order=rpc.order(session);var original=rpc.orders.get(order);rpc.orders.put(order,original.toBuilder().setBuyerId(UUID.randomUUID().toString()).build());
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(404);
        rpc.orders.put(order,original.toBuilder().setCurrency("USD").build());assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(503);
        rpc.orders.put(order,original.toBuilder().setAmountVnd(4999).build());assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    String attempt(String session,String order)throws Exception {var r=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true);assertThat(r.statusCode()).isEqualTo(201);return json.readTree(r.body()).path("id").asString();}
    static String signed(String attempt,String txn,String code,String status,long amount)throws Exception {
        var params=new TreeMap<String,String>();params.put("vnp_TmnCode","TEST0001");params.put("vnp_TxnRef",attempt.replace("-",""));params.put("vnp_Amount",Long.toString(amount));params.put("vnp_TransactionNo",txn);params.put("vnp_ResponseCode",code);params.put("vnp_TransactionStatus",status);
        String canonical=params.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.US_ASCII)+"="+URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.US_ASCII)).collect(java.util.stream.Collectors.joining("&"));
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");mac.init(new javax.crypto.spec.SecretKeySpec("synthetic-payment-test-secret-not-real".getBytes(java.nio.charset.StandardCharsets.US_ASCII),"HmacSHA512"));return canonical+"&vnp_SecureHash="+HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    String ipn(String query)throws Exception {var r=call(null,"GET","/payments/vnpay/ipn?"+query,null,null,false);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("RspCode").asString();}
    @Test void ipnCommitsCaptureLedgerAndJobBeforeAckAndNormalizesReplay()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="1"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(id,"00"+txn,"00","00",7000000))).isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id IN(SELECT id FROM payment.captures WHERE attempt_id=?) AND kind='CAPTURE'",Integer.class,UUID.fromString(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.capture_jobs WHERE capture_id IN(SELECT id FROM payment.captures WHERE attempt_id=?)",Integer.class,UUID.fromString(id))).isEqualTo(1);
        assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("02");
        var read=call(session,"GET","/me/payment-attempts/"+id,null,null,false);assertThat(json.readTree(read.body()).path("state").asString()).isEqualTo("SUCCEEDED");assertThat(json.readTree(read.body()).path("applicationState").asString()).isEqualTo("PROCESSING");
    }
    @Test void malformedOrMismatchedIpnCannotCaptureAndUnknownStatusBlocksNewCharge()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order);
        assertThat(ipn(signed(id,"42","00","00",1))).isEqualTo("04");
        String query=signed(id,"42","00","00",7000000);assertThat(ipn(query.substring(0,query.length()-1)+"z")).isEqualTo("97");
        assertThat(ipn(signed(id,"42","07","07",7000000))).isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isZero();
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
    }
    @Test void duplicateMoneyAfterSuccessCreatesSeparateCaptureAndJob()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="2"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");
        assertThat(ipn(signed(id,"3"+txn,"00","00",7000000))).isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(2);
        assertThat(ipn(signed(id,"0","24","02",7000000))).isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT state FROM payment.attempts WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("SUCCEEDED");
    }
    @Test void lateSuccessCannotBeLostAgainstNewPendingAttempt()throws Exception {
        String session=rpc.account(),order=rpc.order(session),first=attempt(session,order),txn="4"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(first,"0","24","02",7000000))).isEqualTo("00");String second=attempt(session,order);
        assertThat(ipn(signed(first,txn,"00","00",7000000))).isEqualTo("00");assertThat(ipn(signed(second,"5"+txn,"00","00",7000000))).isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE order_id=?",Integer.class,UUID.fromString(order))).isEqualTo(2);
    }
    @Test void sameProviderTransactionCannotCreditDifferentAttempt()throws Exception {
        String s=rpc.account(),first=attempt(s,rpc.order(s)),second=attempt(s,rpc.order(s)),txn="6"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(first,txn,"00","00",7000000))).isEqualTo("00");assertThat(ipn(signed(second,txn,"00","00",7000000))).isEqualTo("99");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE provider_transaction_no=?",Integer.class,txn)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT needs_reconciliation FROM payment.attempts WHERE id=?",Boolean.class,UUID.fromString(second))).isTrue();
    }
    @Test void sqlFailureBeforeCommitCannotAcknowledgeMoney()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="7"+Long.toString(System.nanoTime()).substring(3,13);
        jdbc.execute("ALTER TABLE payment.ledger_entries ADD CONSTRAINT injected_ledger_failure CHECK(kind!='CAPTURE') NOT VALID");
        try{assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("99");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isZero();}
        finally{jdbc.execute("ALTER TABLE payment.ledger_entries DROP CONSTRAINT injected_ledger_failure");}
        assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");
    }
    @Test void returnOnlyCorrelatesAndNeverCredits()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order);
        var response=call(null,"GET","/payments/vnpay/return?"+signed(id,"888","00","00",7000000),null,null,false);assertThat(response.statusCode()).isEqualTo(303);assertThat(response.headers().firstValue("Location").orElseThrow()).startsWith("/orders/"+order+"?").contains("attemptId="+id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isZero();assertThat(jdbc.queryForObject("SELECT state FROM payment.attempts WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("PENDING");
    }

    @Test void concurrentSameKeyCreatesExactlyOneAttemptWithoutNetworkInsideTransaction()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();
        rpc.orderHook=()->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();};
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true));
            var second=executor.submit(()->call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true));
            var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(201,200);assertThat(json.readTree(a.body())).isEqualTo(json.readTree(b.body()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.attempts WHERE request_key=?",Integer.class,UUID.fromString(key))).isEqualTo(1);
        }finally{rpc.orderHook=()->{};}
    }

    @Autowired org.springframework.context.ApplicationContext context;
    void recoverPayments(){context.getBean(com.agora.payment.application.PaymentReconciliation.class).recover();}
    void retryCaptureJobs(){jdbc.update("UPDATE payment.capture_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE state='PENDING'");}
    UUID captureId(String id){return jdbc.queryForObject("SELECT id FROM payment.captures WHERE attempt_id=? ORDER BY created_at LIMIT 1",UUID.class,UUID.fromString(id));}
    @Test void recoveryAppliesCommittedCaptureOutsideDatabaseTransaction()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order),txn="8"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");
        rpc.captureHook=()->assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try{recoverPayments();}finally{rpc.captureHook=()->{};}
        var result=json.readTree(call(session,"GET","/me/payment-attempts/"+id,null,null,false).body());assertThat(result.path("applicationState").asString()).isEqualTo("APPLIED");
        var capture=rpc.captures.stream().filter(c->c.getOrderId().equals(order)).findFirst().orElseThrow();assertThat(capture.getCaptureId()).isEqualTo(captureId(id).toString());assertThat(capture.getAmountVnd()).isEqualTo(70000);assertThat(capture.getBuyerId()).isEqualTo(rpc.sessions.get(session).toString());
    }
    @Test void lostCommerceReplyReplaysSameCaptureWithoutRefundOrExtraLedger()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order),txn="9"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");
        rpc.afterCaptureFailure.set(io.grpc.Status.UNAVAILABLE);try{recoverPayments();}finally{rpc.afterCaptureFailure.set(null);}
        assertThat(jdbc.queryForObject("SELECT application_state FROM payment.captures WHERE attempt_id=?",String.class,UUID.fromString(id))).isEqualTo("PROCESSING");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refunds WHERE capture_id=?",Integer.class,captureId(id))).isZero();
        retryCaptureJobs();recoverPayments();var requests=rpc.captures.stream().filter(c->c.getOrderId().equals(order)).toList();assertThat(requests).hasSize(2);assertThat(requests.getFirst()).isEqualTo(requests.getLast());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id=? AND kind='CAPTURE'",Integer.class,captureId(id))).isEqualTo(1);
    }
    @Test void definitiveRefundDecisionReservesMoneyOnceWithoutClaimingProviderRefund()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order),txn="11"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");UUID capture=captureId(id);
        rpc.applied.put(capture.toString(),com.agora.contracts.commerce.v1.CaptureApplicationResponse.newBuilder().setCaptureId(capture.toString()).setBuyerId(rpc.sessions.get(session).toString()).setOrderId(order).setState(com.agora.contracts.commerce.v1.CaptureApplicationState.CAPTURE_REFUND_REQUIRED).setReason(com.agora.contracts.commerce.v1.CaptureApplicationReason.DUPLICATE_CAPTURE).build());
        recoverPayments();recoverPayments();var result=json.readTree(call(session,"GET","/me/payment-attempts/"+id,null,null,false).body());assertThat(result.path("applicationState").asString()).isEqualTo("REFUND_REQUIRED");assertThat(result.path("refundState").asString()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refunds WHERE capture_id=? AND amount_vnd=70000",Integer.class,capture)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id=? AND kind='REFUND_RESERVED'",Integer.class,capture)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id=? AND kind='REFUND_SUCCEEDED'",Integer.class,capture)).isZero();
    }
    @Test void staleLeaseCannotCompleteEvenWhenRemoteAlreadyApplied()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="12"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");UUID capture=captureId(id);
        rpc.captureHook=()->jdbc.update("UPDATE payment.capture_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE capture_id=?",capture);
        try{recoverPayments();}finally{rpc.captureHook=()->{};}
        assertThat(jdbc.queryForObject("SELECT application_state FROM payment.captures WHERE id=?",String.class,capture)).isEqualTo("PROCESSING");recoverPayments();assertThat(jdbc.queryForObject("SELECT application_state FROM payment.captures WHERE id=?",String.class,capture)).isEqualTo("APPLIED");
    }

    @Test void lateCapturedMoneyHidesOtherPendingLinkForSameOrder()throws Exception {
        String session=rpc.account(),order=rpc.order(session),first=attempt(session,order),txn="13"+Long.toString(System.nanoTime()).substring(3,13);
        assertThat(ipn(signed(first,"0","24","02",7000000))).isEqualTo("00");String second=attempt(session,order);assertThat(ipn(signed(first,txn,"00","00",7000000))).isEqualTo("00");
        var read=json.readTree(call(session,"GET","/me/payment-attempts/"+second,null,null,false).body());assertThat(read.path("state").asString()).isEqualTo("PENDING");assertThat(read.path("redirectUrl").isNull()).isTrue();
    }
    @Test void malformedCommerceApplicationCannotFabricateRefundOrApplied()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order),txn="14"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");UUID capture=captureId(id);
        var valid=com.agora.contracts.commerce.v1.CaptureApplicationResponse.newBuilder().setCaptureId(capture.toString()).setBuyerId(rpc.sessions.get(session).toString()).setOrderId(order).setState(com.agora.contracts.commerce.v1.CaptureApplicationState.CAPTURE_APPLIED).build();
        for(var bad:List.of(valid.toBuilder().setCaptureId(UUID.randomUUID().toString()).build(),valid.toBuilder().setOrderId(UUID.randomUUID().toString()).build(),valid.toBuilder().setState(com.agora.contracts.commerce.v1.CaptureApplicationState.CAPTURE_REFUND_REQUIRED).build(),valid.toBuilder().setReason(com.agora.contracts.commerce.v1.CaptureApplicationReason.DUPLICATE_CAPTURE).build(),valid.toBuilder().setStateValue(99).build())){
            rpc.applied.put(capture.toString(),bad);retryCaptureJobs();recoverPayments();assertThat(jdbc.queryForObject("SELECT application_state FROM payment.captures WHERE id=?",String.class,capture)).isEqualTo("PROCESSING");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refunds WHERE capture_id=?",Integer.class,capture)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT needs_reconciliation FROM payment.attempts WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean com.agora.payment.adapter.outbound.VnpayApiClient vnpayApi;
    void recoverProvider(){context.getBean(com.agora.payment.application.ProviderReconciliation.class).recover();}
    void queryDue(){jdbc.update("UPDATE payment.provider_queries SET next_run_at=clock_timestamp()-interval '1 second',lease_until='-infinity' WHERE NOT done");}
    Map<String,String> apiResponse(com.agora.payment.adapter.outbound.VnpayApiCodec.Request request,String txn,String type,String status)throws Exception {
        var p=request.params();var f=new LinkedHashMap<String,String>();f.put("vnp_ResponseId","reply123");f.put("vnp_Command",p.get("vnp_Command"));f.put("vnp_ResponseCode","00");f.put("vnp_Message","Request successful");f.put("vnp_TmnCode","TEST0001");f.put("vnp_TxnRef",p.get("vnp_TxnRef"));f.put("vnp_Amount","7000000");f.put("vnp_BankCode","NCB");f.put("vnp_PayDate",p.get("vnp_TransactionDate"));f.put("vnp_TransactionNo",txn);f.put("vnp_TransactionType",type);f.put("vnp_TransactionStatus",status);f.put("vnp_OrderInfo",p.get("vnp_OrderInfo"));
        String data=String.join("|",f.values());if(p.get("vnp_Command").equals("querydr"))data+="||";
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");mac.init(new javax.crypto.spec.SecretKeySpec("synthetic-payment-test-secret-not-real".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));f.put("vnp_SecureHash",HexFormat.of().formatHex(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))));return f;
    }
    @Test void signedProviderQueryCanRecoverMissingIpnUsingStoredCreationDate()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="15"+Long.toString(System.nanoTime()).substring(3,13);var requests=new java.util.concurrent.CopyOnWriteArrayList<com.agora.payment.adapter.outbound.VnpayApiCodec.Request>();
        org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var request=invocation.getArgument(0,com.agora.payment.adapter.outbound.VnpayApiCodec.Request.class);requests.add(request);return apiResponse(request,txn,"01","00");});
        queryDue();recoverProvider();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        String original=jdbc.queryForObject("SELECT to_char(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYYMMDDHH24MISS') FROM payment.attempts WHERE id=?",String.class,UUID.fromString(id));var request=requests.stream().filter(r->r.params().get("vnp_TxnRef").equals(id.replace("-",""))).findFirst().orElseThrow();assertThat(request.params().get("vnp_TransactionDate")).isEqualTo(original);
    }
    @Test void unknownProviderQueryNeverProvesAbsenceOfMoney()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order);org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("vnp_ResponseCode","91"));queryDue();recoverProvider();
        assertThat(jdbc.queryForObject("SELECT needs_reconciliation FROM payment.attempts WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isZero();assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
    }
    UUID reserveRefund(String session,String order,String txn)throws Exception {
        String id=attempt(session,order);assertThat(ipn(signed(id,txn,"00","00",7000000))).isEqualTo("00");UUID capture=captureId(id);rpc.applied.put(capture.toString(),com.agora.contracts.commerce.v1.CaptureApplicationResponse.newBuilder().setCaptureId(capture.toString()).setBuyerId(rpc.sessions.get(session).toString()).setOrderId(order).setState(com.agora.contracts.commerce.v1.CaptureApplicationState.CAPTURE_REFUND_REQUIRED).setReason(com.agora.contracts.commerce.v1.CaptureApplicationReason.ORDER_EXPIRED).build());recoverPayments();return capture;
    }
    @Test void signedRefundCompletionRequiresRefundTypeAndReservesExactlyCapturedMoney()throws Exception {
        String session=rpc.account();UUID capture=reserveRefund(session,rpc.order(session),"16"+Long.toString(System.nanoTime()).substring(3,13));org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->apiResponse(invocation.getArgument(0),"987654321","02","00"));recoverProvider();
        assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT amount_vnd FROM payment.ledger_entries WHERE capture_id=? AND kind='REFUND_SUCCEEDED'",Long.class,capture)).isEqualTo(70000);
    }
    @Test void originalPaySuccessCannotMasqueradeAsRefundCompletion()throws Exception {
        String session=rpc.account();UUID capture=reserveRefund(session,rpc.order(session),"17"+Long.toString(System.nanoTime()).substring(3,13));org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->apiResponse(invocation.getArgument(0),"987654322","01","00"));recoverProvider();
        assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("NEEDS_RECONCILIATION");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id=? AND kind='REFUND_SUCCEEDED'",Integer.class,capture)).isZero();
    }
    @Test void lostRefundReplyKeepsReservationAndCannotSendFreshRefund()throws Exception {
        String session=rpc.account();UUID capture=reserveRefund(session,rpc.order(session),"18"+Long.toString(System.nanoTime()).substring(3,13));var calls=new java.util.concurrent.atomic.AtomicInteger();org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{if(invocation.getArgument(0,com.agora.payment.adapter.outbound.VnpayApiCodec.Request.class).params().get("vnp_Command").equals("refund"))calls.incrementAndGet();throw new IllegalStateException("simulated timeout");});recoverProvider();String request=jdbc.queryForObject("SELECT request_id::text FROM payment.refunds WHERE capture_id=?",String.class,capture);int first=calls.get();recoverProvider();assertThat(calls.get()).isEqualTo(first);assertThat(jdbc.queryForObject("SELECT request_id::text FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo(request);assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("NEEDS_RECONCILIATION");
    }

    @Test void acceptedRefundRemainsPendingUntilBoundRefundQueryConfirmsCompletion()throws Exception {
        String session=rpc.account();UUID capture=reserveRefund(session,rpc.order(session),"19"+Long.toString(System.nanoTime()).substring(3,13));
        org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{var request=invocation.getArgument(0,com.agora.payment.adapter.outbound.VnpayApiCodec.Request.class);return apiResponse(request,"333333333","02",request.params().get("vnp_Command").equals("refund")?"05":"00");});
        recoverProvider();assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE capture_id=? AND kind='REFUND_SUCCEEDED'",Integer.class,capture)).isZero();
        jdbc.update("UPDATE payment.refunds SET next_run_at='-infinity',lease_until='-infinity' WHERE capture_id=?",capture);recoverProvider();assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("SUCCEEDED");
    }

    @Test void contradictoryUnknownAfterDeclineBlocksAnotherChargeAndReopensQuery()throws Exception {
        String session=rpc.account(),order=rpc.order(session),id=attempt(session,order);assertThat(ipn(signed(id,"0","24","02",7000000))).isEqualTo("00");assertThat(ipn(signed(id,"0","07","07",7000000))).isEqualTo("00");
        assertThat(call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
    }
    @Test void contradictoryDeclinedAttemptCanStillReconcileCapturedMoney()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="20"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(id,"0","24","02",7000000))).isEqualTo("00");assertThat(ipn(signed(id,"0","07","07",7000000))).isEqualTo("00");
        org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->apiResponse(invocation.getArgument(0),txn,"01","00"));queryDue();recoverProvider();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }
    @Test void contradictoryEvidenceReopensCompletedProviderQuery()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="21"+Long.toString(System.nanoTime()).substring(3,13);org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->apiResponse(invocation.getArgument(0),txn,"01","00"));queryDue();recoverProvider();assertThat(jdbc.queryForObject("SELECT done FROM payment.provider_queries WHERE attempt_id=?",Boolean.class,UUID.fromString(id))).isTrue();
        assertThat(ipn(signed(id,txn,"00","00",1))).isEqualTo("04");assertThat(providerJobs.pendingQueries()).contains(UUID.fromString(id));
    }
    @Test void collisionIsVisibleToOriginalCaptureOwner()throws Exception {
        String session=rpc.account(),first=attempt(session,rpc.order(session)),second=attempt(session,rpc.order(session)),txn="22"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(first,txn,"00","00",7000000))).isEqualTo("00");assertThat(ipn(signed(second,txn,"00","00",7000000))).isEqualTo("99");
        var result=json.readTree(call(session,"GET","/me/payment-attempts/"+first,null,null,false).body());assertThat(result.path("needsReconciliation").asBoolean()).isTrue();
    }
    @Test void unknownReturnUsesDocumentedBadRequestWithoutExposingReferences()throws Exception {
        var response=call(null,"GET","/payments/vnpay/return?"+signed(UUID.randomUUID().toString(),"123","00","00",7000000),null,null,false);assertThat(response.statusCode()).isEqualTo(400);assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test void newerContradictoryEvidenceKeepsValidMoneyAndSchedulesFollowup()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="23"+Long.toString(System.nanoTime()).substring(3,13);var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{var request=invocation.getArgument(0,com.agora.payment.adapter.outbound.VnpayApiCodec.Request.class);if(calls.incrementAndGet()==1)assertThat(ipn(signed(id,"0","07","07",7000000))).isEqualTo("00");return apiResponse(request,txn,"01","00");});
        queryDue();recoverProvider();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);assertThat(providerJobs.pendingQueries()).contains(UUID.fromString(id));
        recoverProvider();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
    }
    @Test void concurrentIdenticalIpnsCommitOneCaptureAndOneLedger()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session)),txn="24"+Long.toString(System.nanoTime()).substring(3,13),query=signed(id,txn,"00","00",7000000);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->ipn(query));var b=executor.submit(()->ipn(query));assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder("00","02");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE kind='CAPTURE'",Integer.class)).isEqualTo(1);
    }
    @Test void refundCompletionSqlFailureCannotSendAnotherRefund()throws Exception {
        String session=rpc.account();UUID capture=reserveRefund(session,rpc.order(session),"25"+Long.toString(System.nanoTime()).substring(3,13));var calls=new java.util.concurrent.atomic.AtomicInteger();org.mockito.Mockito.when(vnpayApi.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{calls.incrementAndGet();return apiResponse(invocation.getArgument(0),"987654325","02","00");});
        jdbc.execute("ALTER TABLE payment.ledger_entries ADD CONSTRAINT injected_refund_failure CHECK(kind!='REFUND_SUCCEEDED') NOT VALID");
        try{recoverProvider();assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries WHERE kind='REFUND_SUCCEEDED'",Integer.class)).isZero();}finally{jdbc.execute("ALTER TABLE payment.ledger_entries DROP CONSTRAINT injected_refund_failure");}
        jdbc.update("UPDATE payment.refunds SET lease_until='-infinity',next_run_at='-infinity' WHERE capture_id=?",capture);recoverProvider();assertThat(calls.get()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT state FROM payment.refunds WHERE capture_id=?",String.class,capture)).isEqualTo("NEEDS_RECONCILIATION");
    }

    @Test void newAttemptHasNoApplicationOrRefundUntilMoneyExists()throws Exception {
        String session=rpc.account(),order=rpc.order(session),key=UUID.randomUUID().toString();
        var created=call(session,"POST","/me/orders/"+order+"/payment-attempts","{}",key,true);assertThat(created.statusCode()).isEqualTo(201);
        var attempt=json.readTree(created.body());String id=attempt.path("id").asString();
        for(var response:List.of(created,call(session,"GET","/me/payment-attempts/"+id,null,null,false),call(session,"GET","/me/payment-attempts/by-request-key/"+key,null,null,false))){
            assertThat(response.statusCode()).isIn(200,201);var body=json.readTree(response.body());assertThat(body.path("state").asString()).isEqualTo("PENDING");
            assertThat(body.path("applicationState").isNull()).as("No capture has been recorded").isTrue();assertThat(body.path("refundState").isNull()).as("No refund has been reserved").isTrue();
        }
    }

    @Autowired com.agora.payment.application.ProviderJobs providerJobs;
    @Autowired com.agora.payment.application.PaymentStore paymentStore;
    @Test void repeatedWakeupsPreserveActiveLeaseAndKeepOneDurableFollowup()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session));var target=paymentStore.target(id.replace("-","")).orElseThrow();providerJobs.scheduleQuery(target);var work=providerJobs.acquireQuery(target.id()).orElseThrow();
        providerJobs.scheduleQuery(target);providerJobs.scheduleQuery(target);
        assertThat(providerJobs.acquireQuery(target.id())).as("Repeated callbacks must not start an overlapping query").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.query_wakeups WHERE attempt_id=?",Integer.class,target.id())).isEqualTo(1);
        providerJobs.completeQuery(work,new com.agora.payment.domain.Payment.ProviderResult("987654326","00",true));assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,target.id())).isEqualTo(1);
        assertThat(providerJobs.pendingQueries()).contains(target.id());var followup=providerJobs.acquireQuery(target.id()).orElseThrow();providerJobs.completeQuery(followup,new com.agora.payment.domain.Payment.ProviderResult("987654326","00",true));assertThat(providerJobs.pendingQueries()).doesNotContain(target.id());
    }
    @Test void failedWakeupRollsBackReconciliationEvidence()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session));jdbc.update("UPDATE payment.provider_queries SET done=true WHERE attempt_id=?",UUID.fromString(id));
        jdbc.execute("ALTER TABLE payment.query_wakeups ADD CONSTRAINT injected_wake_failure CHECK(attempt_id!='"+id+"'::uuid) NOT VALID");
        try{assertThat(ipn(signed(id,"123","00","00",1))).isEqualTo("99");assertThat(jdbc.queryForObject("SELECT needs_reconciliation FROM payment.attempts WHERE id=?",Boolean.class,UUID.fromString(id))).isFalse();}finally{jdbc.execute("ALTER TABLE payment.query_wakeups DROP CONSTRAINT injected_wake_failure");}
        assertThat(ipn(signed(id,"123","00","00",1))).isEqualTo("04");assertThat(providerJobs.pendingQueries()).contains(UUID.fromString(id));
    }
    @Test void transactionCollisionWakesBothOwnersWithoutLockingOriginalQuery()throws Exception {
        String session=rpc.account(),first=attempt(session,rpc.order(session)),second=attempt(session,rpc.order(session)),txn="26"+Long.toString(System.nanoTime()).substring(3,13);assertThat(ipn(signed(first,txn,"00","00",7000000))).isEqualTo("00");jdbc.update("UPDATE payment.provider_queries SET done=true");
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT attempt_id FROM payment.provider_queries WHERE attempt_id=? FOR UPDATE")){statement.setObject(1,UUID.fromString(first));statement.executeQuery().close();}
            var collision=executor.submit(()->ipn(signed(second,txn,"00","00",7000000)));assertThat(collision.get(3,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("99");connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.query_wakeups",Integer.class)).isEqualTo(2);assertThat(providerJobs.pendingQueries()).contains(UUID.fromString(first),UUID.fromString(second));
    }

    @Test void claimedWakeupRecoversAfterCrashEvenForPreviouslyDeclinedAttempt()throws Exception {
        String session=rpc.account(),id=attempt(session,rpc.order(session));assertThat(ipn(signed(id,"0","24","02",7000000))).isEqualTo("00");var target=paymentStore.target(id.replace("-","")).orElseThrow();providerJobs.scheduleQuery(target);var old=providerJobs.acquireQuery(target.id()).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.query_wakeups",Integer.class)).isZero();jdbc.update("UPDATE payment.provider_queries SET lease_until='-infinity' WHERE attempt_id=?",target.id());assertThat(providerJobs.pendingQueries()).contains(target.id());
        var recovered=providerJobs.acquireQuery(target.id()).orElseThrow();providerJobs.completeQuery(recovered,new com.agora.payment.domain.Payment.ProviderResult("987654327","00",true));providerJobs.completeQuery(old,com.agora.payment.domain.Payment.ProviderResult.unknown());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE attempt_id=?",Integer.class,target.id())).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT done FROM payment.provider_queries WHERE attempt_id=?",Boolean.class,target.id())).isTrue();
    }
    @Test void oppositeCollisionsAcrossActorsCommitBothWakeupsWithoutForeignKeyDeadlock()throws Exception {
        String a=rpc.account(),b=rpc.account(),first=attempt(a,rpc.order(a)),second=attempt(b,rpc.order(b)),txnA="27123456789",txnB="28123456789";
        assertThat(ipn(signed(first,txnA,"00","00",7000000))).isEqualTo("00");assertThat(ipn(signed(second,txnB,"00","00",7000000))).isEqualTo("00");
        jdbc.execute("CREATE FUNCTION payment.test_collision_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(27654321); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_collision_gate BEFORE UPDATE OF needs_reconciliation ON payment.captures FOR EACH ROW EXECUTE FUNCTION payment.test_collision_gate()");
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            connection.createStatement().execute("SELECT pg_advisory_lock(27654321)");
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                var x=executor.submit(()->ipn(signed(first,txnB,"00","00",7000000)));var y=executor.submit(()->ipn(signed(second,txnA,"00","00",7000000)));
                try{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);int waiting=0;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=27654321 AND NOT granted",Integer.class);if(waiting==2)break;Thread.sleep(10);}assertThat(waiting).isEqualTo(2);}
                finally{connection.createStatement().execute("SELECT pg_advisory_unlock(27654321)");}
                assertThat(x.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("99");assertThat(y.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("99");
            }
        }finally{jdbc.execute("DROP TRIGGER test_collision_gate ON payment.captures");jdbc.execute("DROP FUNCTION payment.test_collision_gate()");}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures WHERE needs_reconciliation",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.query_wakeups",Integer.class)).isEqualTo(2);
    }

}
