package com.agora.identity;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 "spring.config.import=", "agora.grpc.enabled=true", "agora.grpc.port=0", "agora.grpc.token=email-flow-private-test-token-not-real-secret", "agora.auth.rate-limit.attempts=1000", "agora.email.delivery-enabled=false",
 "agora.email.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
@org.springframework.context.annotation.Import(EmailFlowsApiTests.MailFixture.class)
class EmailFlowsApiTests {
 @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
 @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
  p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
 }
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) static class MailFixture {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary CapturingMail fakeMail(){return new CapturingMail();}
 }
 static class CapturingMail implements com.agora.identity.application.EmailPort {
  record Link(String recipient,String purpose,String token){@Override public String toString(){return "TestLink[redacted]";}}
  final java.util.List<Link> links=new java.util.concurrent.CopyOnWriteArrayList<>();boolean fail;
  public void send(UUID id,String recipient,String purpose,String token,java.time.Instant expiry){if(fail)throw new IllegalStateException("Synthetic SMTP outage");links.add(new Link(recipient,purpose,token));}
 }
 @Autowired CapturingMail mail;
 @Autowired com.agora.identity.application.EmailActions actions;
 String link(Browser b,String purpose){for(int i=0;i<20&&actions.dispatch();i++){}return mail.links.stream().filter(x->x.recipient().equals(b.email)&&x.purpose().equals(purpose)).reduce((a,c)->c).orElseThrow().token();}
 void verify(Browser b)throws Exception{assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",link(b,"VERIFY_EMAIL"))).statusCode()).isEqualTo(204);}
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc;
 @Autowired com.agora.identity.configuration.IdentityGrpcServer grpc;
 @Autowired org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;
 final JsonMapper json=JsonMapper.builder().build();
 @BeforeEach void reset(){jdbc.execute("TRUNCATE identity.accounts,identity.spring_session CASCADE");mail.links.clear();mail.fail=false;}
 @Test void registrationCannotLoginBeforeEmailConfirmation() throws Exception {
  var browser=new Browser();browser.register();
  assertThat(browser.login().statusCode()).isEqualTo(401);
  assertThat(browser.get("/me").statusCode()).isEqualTo(401);
 }
 @Test void verificationLinkIsOpaqueSingleUseAndGetCannotConsume() throws Exception {
  var b=new Browser();UUID id=b.register();String token=link(b,"VERIFY_EMAIL");
  assertThat(jdbc.queryForObject("SELECT token_cipher IS NULL AND delivered_at IS NOT NULL FROM identity.email_actions WHERE account_id=?",Boolean.class,id)).isTrue();
  assertThat(b.get("/auth/email-verifications/confirm").statusCode()).isNotEqualTo(204);
  assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",token)).statusCode()).isEqualTo(204);
  assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",token)).statusCode()).isEqualTo(422);
  assertThat(b.login().statusCode()).isEqualTo(200);
 }
 @Test void resetHasUniformRequestResponseAndRevokesOldPasswordSessionsAndLinks() throws Exception {
  var b=new Browser();UUID id=b.register();verify(b);b.login();
  var known=b.post("/auth/password-resets",Map.of("email",b.email));var unknown=b.post("/auth/password-resets",Map.of("email","absent@example.test"));
  assertThat(known.statusCode()).isEqualTo(202);assertThat(unknown.statusCode()).isEqualTo(202);assertThat(known.body()).isEqualTo(unknown.body());
  String token=link(b,"RESET_PASSWORD");
  assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",token)).statusCode()).isEqualTo(422);
  assertThat(b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","            ")).statusCode()).isEqualTo(422);
  assertThat(b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","replacement-long-password")).statusCode()).isEqualTo(204);
  assertThat(b.get("/me").statusCode()).isEqualTo(401);assertThat(b.login().statusCode()).isEqualTo(401);
  assertThat(b.post("/auth/sessions",Map.of("email",b.email,"password","replacement-long-password")).statusCode()).isEqualTo(200);
  assertThat(b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","other-long-password")).statusCode()).isEqualTo(422);
  assertThat(jdbc.queryForObject("SELECT version FROM identity.accounts WHERE id=?",Long.class,id)).isEqualTo(2);
 }
 @Test void changeNeedsCurrentPasswordAndOnlyAppliesPendingPasswordAfterLink() throws Exception {
  var b=new Browser();b.register();verify(b);b.login();
  assertThat(b.post("/me/password-changes",Map.of("currentPassword","wrong","newPassword","replacement-long-password")).statusCode()).isEqualTo(422);
  assertThat(b.post("/me/password-changes",Map.of("currentPassword","synthetic-long-password","newPassword","replacement-long-password")).statusCode()).isEqualTo(202);
  assertThat(b.post("/me/password-changes",Map.of("currentPassword","synthetic-long-password","newPassword","different-long-password")).statusCode()).isEqualTo(429);
  assertThat(b.login().statusCode()).isEqualTo(200);
  String token=link(b,"CHANGE_PASSWORD");
  assertThat(b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","other-long-password")).statusCode()).isEqualTo(422);
  assertThat(b.post("/auth/password-changes/confirm",Map.of("token",token)).statusCode()).isEqualTo(204);
  assertThat(b.get("/me").statusCode()).isEqualTo(401);
  assertThat(b.post("/auth/sessions",Map.of("email",b.email,"password","replacement-long-password")).statusCode()).isEqualTo(200);
 }
 @Test void cooldownSupersededExpiredAndVersionBoundLinksFailClosed() throws Exception {
  var b=new Browser();UUID id=b.register();String old=link(b,"VERIFY_EMAIL");
  assertThat(b.post("/auth/email-verifications",Map.of("email",b.email)).statusCode()).isEqualTo(202);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.email_actions WHERE account_id=?",Integer.class,id)).isEqualTo(1);
  jdbc.update("UPDATE identity.email_actions SET created_at=clock_timestamp()-interval '61 seconds' WHERE account_id=?",id);
  b.post("/auth/email-verifications",Map.of("email",b.email));String current=link(b,"VERIFY_EMAIL");
  assertThat(current).isNotEqualTo(old);assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",old)).statusCode()).isEqualTo(422);
  jdbc.update("UPDATE identity.accounts SET version=version+1 WHERE id=?",id);
  assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",current)).statusCode()).isEqualTo(422);
  jdbc.update("UPDATE identity.email_actions SET created_at=clock_timestamp()-interval '61 seconds' WHERE account_id=?",id);
  b.post("/auth/email-verifications",Map.of("email",b.email));String expired=link(b,"VERIFY_EMAIL");
  jdbc.update("UPDATE identity.email_actions SET expires_at=clock_timestamp()-interval '1 second' WHERE account_id=?",id);
  assertThat(b.post("/auth/email-verifications/confirm",Map.of("token",expired)).statusCode()).isEqualTo(422);
 }
 @Test void concurrentConfirmHasOneWinnerAndSmtpOutageKeepsEncryptedOutbox() throws Exception {
  var b=new Browser();UUID id=b.register();mail.fail=true;assertThat(actions.dispatch()).isTrue();
  assertThat(jdbc.queryForObject("SELECT attempts FROM identity.email_actions WHERE account_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT token_cipher IS NOT NULL FROM identity.email_actions WHERE account_id=?",Boolean.class,id)).isTrue();
  mail.fail=false;jdbc.update("UPDATE identity.email_actions SET retry_at=clock_timestamp() WHERE account_id=?",id);String token=link(b,"VERIFY_EMAIL");
  var second=new Browser();
  try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var a=executor.submit(()->b.post("/auth/email-verifications/confirm",Map.of("token",token)));
   var c=executor.submit(()->second.post("/auth/email-verifications/confirm",Map.of("token",token)));
   assertThat(List.of(a.get().statusCode(),c.get().statusCode())).containsExactlyInAnyOrder(204,422);
  }
  assertThat(jdbc.queryForObject("SELECT version FROM identity.accounts WHERE id=?",Long.class,id)).isEqualTo(1);
 }
 @Test void failedPasswordCommitRollsBackTokenSessionsAndHash() throws Exception {
  var b=new Browser();UUID id=b.register();verify(b);b.login();
  b.post("/auth/password-resets",Map.of("email",b.email));String token=link(b,"RESET_PASSWORD");
  String previous=jdbc.queryForObject("SELECT password_hash FROM identity.accounts WHERE id=?",String.class,id);
  jdbc.execute("ALTER TABLE identity.accounts ADD CONSTRAINT synthetic_password_rollback CHECK(id<>'"+id+"'::uuid OR version<2)");
  try {
   var response=b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","replacement-long-password"));
   assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).doesNotContain("synthetic_password_rollback","UPDATE",token);
   assertThat(previous.equals(jdbc.queryForObject("SELECT password_hash FROM identity.accounts WHERE id=?",String.class,id))).isTrue();
   assertThat(b.get("/me").statusCode()).isEqualTo(200);
  }finally{jdbc.execute("ALTER TABLE identity.accounts DROP CONSTRAINT synthetic_password_rollback");}
  assertThat(b.post("/auth/password-resets/confirm",Map.of("token",token,"newPassword","replacement-long-password")).statusCode()).isEqualTo(204);
 }
 @Test void registrationOutboxFailureRollsBackNewIdentityAndCanBeRetried() throws Exception {
  var b=new Browser();jdbc.execute("ALTER TABLE identity.email_actions ADD CONSTRAINT synthetic_outbox_failure CHECK(purpose<>'VERIFY_EMAIL')");
  try {
   var response=b.post("/auth/registrations",Map.of("email",b.email,"password","synthetic-long-password","displayName","Email fixture"));
   assertThat(response.statusCode()).isEqualTo(503);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.accounts WHERE email=?",Integer.class,b.email)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.account_roles",Integer.class)).isZero();
  }finally{jdbc.execute("ALTER TABLE identity.email_actions DROP CONSTRAINT synthetic_outbox_failure");}
  b.register();assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.email_actions",Integer.class)).isEqualTo(1);
 }
 @SuppressWarnings("unchecked")
 @Test void unverifiedSellerCannotResolveLegacySessionOrQualifyAsActiveOwner() throws Exception {
  var b=new Browser();var registered=b.post("/auth/seller-registrations",Map.of("email",b.email,"password","synthetic-long-password","displayName","Email seller"));
  assertThat(registered.statusCode()).isEqualTo(201);String id=json.readTree(registered.body()).path("id").asString();
  var repo=(org.springframework.session.SessionRepository<org.springframework.session.Session>)(org.springframework.session.SessionRepository<?>)sessions;
  var session=repo.createSession();var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
  context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(id,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
  session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
  session.setAttribute("AGORA_ACCOUNT_TYPE","SELLER");session.setAttribute("AGORA_ACCOUNT_VERSION",Long.valueOf(0));repo.save(session);
  var channel=io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1",grpc.port()).usePlaintext().build();
  try {
   var headers=new io.grpc.Metadata();headers.put(io.grpc.Metadata.Key.of("x-agora-internal-token",io.grpc.Metadata.ASCII_STRING_MARSHALLER),"email-flow-private-test-token-not-real-secret");
   var rpc=com.agora.contracts.identity.v1.SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(5,java.util.concurrent.TimeUnit.SECONDS);
   assertThat(rpc.getAccountStates(com.agora.contracts.identity.v1.GetAccountStatesRequest.newBuilder().addAccountIds(id).build()).getAccountsCount()).isZero();
   assertThatThrownBy(()->rpc.resolveSession(com.agora.contracts.identity.v1.ResolveSessionRequest.newBuilder().setSessionId(session.getId()).build()))
    .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED));
   var old=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me")).header("Cookie","AGORA_SESSION="+session.getId()).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertThat(old.statusCode()).isEqualTo(401);verify(b);assertThat(b.login().statusCode()).isEqualTo(200);
   assertThat(rpc.getAccountStates(com.agora.contracts.identity.v1.GetAccountStatesRequest.newBuilder().addAccountIds(id).build()).getAccounts(0).getIsActive()).isTrue();
   String fresh=b.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("AGORA_SESSION")).findFirst().orElseThrow().getValue();
   assertThat(rpc.resolveSession(com.agora.contracts.identity.v1.ResolveSessionRequest.newBuilder().setSessionId(fresh).build()).getAccountId()).isEqualTo(id);
  }finally{channel.shutdownNow().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
 }
 class Browser {
  String email="email-flow-"+UUID.randomUUID()+"@example.test";
  final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
  final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
  HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null);}
  HttpResponse<String> send(String method,String path,String body,String token)throws Exception{
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
   if(token!=null)request.header("X-CSRF-TOKEN",token);
   return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  HttpResponse<String> post(String path,Object body)throws Exception{return send("POST",path,json.writeValueAsString(body),json.readTree(get("/auth/csrf").body()).path("token").asString());}
  UUID register()throws Exception{
   var r=post("/auth/registrations",Map.of("email",email,"password","synthetic-long-password","displayName","Email fixture"));assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
   return UUID.fromString(json.readTree(r.body()).path("id").asString());
  }
  HttpResponse<String> login()throws Exception{return post("/auth/sessions",Map.of("email",email,"password","synthetic-long-password"));}
 }
}
