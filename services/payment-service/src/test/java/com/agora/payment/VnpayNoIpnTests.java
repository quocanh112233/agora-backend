package com.agora.payment;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","agora.vnpay.enabled=true","agora.vnpay.tmn-code=TEST0001","agora.vnpay.hash-secret=synthetic-payment-test-secret-not-real","agora.payment.worker-initial-delay-ms=3600000"})
@org.springframework.context.annotation.Import(PaymentApiTests.RemoteConfiguration.class)
class VnpayNoIpnTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->PaymentRpcFixture.TOKEN);}
    @Autowired PaymentRpcFixture rpc;@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;@Autowired tools.jackson.databind.json.JsonMapper json;
    @LocalServerPort int port;@Autowired org.springframework.context.ApplicationContext context;
    int get(String path)throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/payments/vnpay/"+path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    @Test void ipnIsDisabledByDefault()throws Exception{assertThat(context.getBeansOfType(com.agora.payment.adapter.inbound.VnpayIpnController.class)).isEmpty();assertThat(get("ipn")).isEqualTo(404);}
    @Test void returnRemainsAvailableWithoutAutomaticQuery()throws Exception{assertThat(context.getBeansOfType(com.agora.payment.adapter.inbound.VnpayController.class)).hasSize(1);assertThat(context.getBeansOfType(com.agora.payment.application.ProviderReconciliation.class)).isEmpty();assertThat(get("return")).isEqualTo(400);}
    @Test void gatewayAndAuthenticatedReturnNeverScheduleQueryOrInventCapture()throws Exception{
        String session=rpc.account(),order=rpc.order(session),key=java.util.UUID.randomUUID().toString();
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/orders/"+order+"/payment-attempts")).header("Content-Type","application/json").header("Cookie","AGORA_SESSION="+session).header("X-CSRF-TOKEN","csrf-fixture").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);var attempt=json.readTree(response.body());assertThat(attempt.path("redirectUrl").asString()).startsWith("https://sandbox.vnpayment.vn/");
        String id=attempt.path("id").asString(),query=PaymentApiTests.signed(id,"12345678","00","00",7000000);
        assertThat(get("return?"+query.replace("vnp_Amount=7000000","vnp_Amount=7000001"))).isEqualTo(400);
        assertThat(get("return?"+PaymentApiTests.signed(id,"12345678","00","00",7000001))).isEqualTo(400);
        assertThat(get("return?"+PaymentApiTests.signed(java.util.UUID.randomUUID().toString(),"12345678","00","00",7000000))).isEqualTo(400);
        var returned=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/payments/vnpay/return?"+query)).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertThat(returned.statusCode()).isEqualTo(303);assertThat(returned.headers().firstValue("Location")).hasValue("/orders/"+order+"?attemptId="+id+"&requestKey="+key);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.ledger_entries",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.capture_jobs",Integer.class)).isZero();assertThat(rpc.captures).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.provider_queries",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.query_wakeups",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.captures",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT state FROM payment.attempts",String.class)).isEqualTo("PENDING");
    }
}

