package com.agora.email;

import com.agora.contracts.email.v1.*;
import com.agora.email.configuration.EmailGrpcServer;
import com.agora.email.application.LinkMailer;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "agora.grpc.enabled=true", "agora.grpc.port=0",
    "agora.grpc.token=email-integration-token-not-a-real-secret", "agora.email.from=agora@example.test", "agora.email.origin=http://127.0.0.1:3000",
    "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false", "spring.mail.properties.mail.smtp.starttls.enable=false", "spring.mail.properties.mail.smtp.starttls.required=false" })
class EmailDeliveryTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    static final Sink SMTP = new Sink();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl); p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword); p.add("spring.mail.port", SMTP::port);
    }
    @Autowired LinkMailer mailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailGrpcServer grpc;
    @Autowired JavaMailSender sender;
    @BeforeEach void reset() { jdbc.execute("TRUNCATE email.deliveries"); SMTP.messages.clear(); }
    @AfterAll static void close() { SMTP.close(); }
    SendLinkRequest message(LinkPurpose purpose) {
        return SendLinkRequest.newBuilder().setDeliveryId(UUID.randomUUID().toString()).setRecipient("buyer@example.test").setPurpose(purpose)
            .setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])).setExpiresAtEpochSeconds(Instant.now().plusSeconds(1200).getEpochSecond()).build();
    }
    EmailDeliveryGrpc.EmailDeliveryBlockingStub rpc(ManagedChannel channel) {
        var metadata = new Metadata(); metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), "email-integration-token-not-a-real-secret");
        return EmailDeliveryGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata)).withDeadlineAfter(10, TimeUnit.SECONDS);
    }
    @Test void realSmtpRendersAllThreeLinksAndCommittedDeliveryIsIdempotent() throws Exception {
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            var rpc = rpc(channel);
            for (var purpose : List.of(LinkPurpose.VERIFY_EMAIL, LinkPurpose.RESET_PASSWORD, LinkPurpose.CHANGE_PASSWORD)) {
                var request = message(purpose);
                assertThat(rpc.sendLink(request).getAccepted()).isTrue(); assertThat(rpc.sendLink(request).getAccepted()).isTrue();
                byte[] raw = SMTP.messages.poll(2, TimeUnit.SECONDS); assertThat(raw).isNotNull();
                var mime = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
                assertThat(mime.getSubject()).contains("Agora");
                String route = switch (purpose) { case VERIFY_EMAIL -> "verify-email"; case RESET_PASSWORD -> "reset-password"; default -> "confirm-password-change"; };
                assertThat(mime.getContent().toString()).contains("http://127.0.0.1:3000/" + route + "#token=" + request.getToken());
                assertThatThrownBy(() -> rpc.sendLink(request.toBuilder().setRecipient("other@example.test").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            }
            assertThat(SMTP.messages).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email.deliveries WHERE delivered", Integer.class)).isEqualTo(3);
        } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
    @Test void rejectsUntrustedMalformedAndExpiredRequestsWithoutSending() throws Exception {
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            var valid = message(LinkPurpose.VERIFY_EMAIL);
            assertThatThrownBy(() -> EmailDeliveryGrpc.newBlockingStub(channel).sendLink(valid)).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            for (var invalid : List.of(valid.toBuilder().setRecipient("buyer@example.test\r\nBcc: other@example.test").build(), valid.toBuilder().setToken("bad").build(),
                valid.toBuilder().setPurpose(LinkPurpose.LINK_PURPOSE_UNSPECIFIED).build(), valid.toBuilder().setExpiresAtEpochSeconds(1).build())) {
                assertThatThrownBy(() -> rpc(channel).sendLink(invalid)).isInstanceOfSatisfying(StatusRuntimeException.class,
                    e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            }
            assertThat(SMTP.messages).isEmpty(); assertThat(jdbc.queryForObject("SELECT count(*) FROM email.deliveries", Integer.class)).isZero();
        } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
    @Test void smtpFailureRollsBackDeliveryClaimAndRetrySendsOnce() throws Exception {
        var mail = (JavaMailSenderImpl) sender; int port = mail.getPort();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            var request = message(LinkPurpose.RESET_PASSWORD);
            try (var unused = new ServerSocket(0)) { mail.setPort(unused.getLocalPort()); }
            assertThatThrownBy(() -> rpc(channel).sendLink(request)).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email.deliveries", Integer.class)).isZero();
            mail.setPort(port); assertThat(rpc(channel).sendLink(request).getAccepted()).isTrue();
            assertThat(SMTP.messages.poll(2, TimeUnit.SECONDS)).isNotNull(); assertThat(SMTP.messages).isEmpty();
        } finally { mail.setPort(port); channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
    @Test void retentionDeletesOnlyDedupeRecordsOlderThanMaximumLinkLifetime() {
        jdbc.update("INSERT INTO email.deliveries(id,payload_hash,delivered,created_at) VALUES (?,?,true,clock_timestamp()-interval '8 days'),(?,?,true,clock_timestamp()-interval '1 day')",
            UUID.randomUUID(),new byte[32],UUID.randomUUID(),new byte[32]);
        mailer.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email.deliveries",Integer.class)).isEqualTo(1);
    }
    @Test void linkOriginRejectsHostInjectionAndRemotePlainHttp() {
        for (String origin : List.of("http://evil.example", "https://agora.example/attacker", "https://user@agora.example", "https://agora.example/?redirect=evil", "https://agora.example/#x"))
            assertThatThrownBy(() -> new LinkMailer(jdbc, sender, origin, "agora@example.test")).isInstanceOf(IllegalArgumentException.class);
    }
    static class Sink implements AutoCloseable {
        final ServerSocket socket;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        Sink() {
            try { socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            executor.submit(() -> { while (!socket.isClosed()) { try { var client = socket.accept(); executor.submit(() -> receive(client)); } catch (IOException e) { if (!socket.isClosed()) throw new UncheckedIOException(e); } } });
        }
        int port() { return socket.getLocalPort(); }
        void receive(Socket client) {
            try (client; var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)); var out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {
                client.setSoTimeout(5000); reply(out, "220 localhost test SMTP");
                for (String line; (line = in.readLine()) != null;) {
                    if (line.startsWith("EHLO")) { reply(out, "250-localhost"); reply(out, "250 8BITMIME"); }
                    else if (line.startsWith("DATA")) {
                        reply(out, "354 Send data"); var data = new StringBuilder();
                        while ((line = in.readLine()) != null && !line.equals(".")) data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        messages.add(data.toString().getBytes(StandardCharsets.UTF_8)); reply(out, "250 Accepted");
                    } else if (line.startsWith("QUIT")) { reply(out, "221 Bye"); break; }
                    else reply(out, "250 OK");
                }
            } catch (IOException e) { if (!socket.isClosed()) throw new UncheckedIOException(e); }
        }
        void reply(PrintWriter out, String line) { out.print(line + "\r\n"); out.flush(); }
        public void close() { try { socket.close(); } catch (IOException e) { throw new UncheckedIOException(e); } executor.close(); }
    }
}
