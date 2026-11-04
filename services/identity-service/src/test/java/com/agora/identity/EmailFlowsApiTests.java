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
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary AvatarMedia avatarMedia(){return new AvatarMedia();}
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary CapturingMail fakeMail(){return new CapturingMail();}
 }
 static class AvatarMedia implements com.agora.media.ImageStorage {
  final Map<String,Stored> files=new java.util.concurrent.ConcurrentHashMap<>();int uploads;boolean malformed;Runnable afterUpload;Runnable beforeFind;final Set<String> failDeletes=new HashSet<>();
  public Optional<Stored> find(String id){if(beforeFind!=null)beforeFind.run();return Optional.ofNullable(files.get(id));}
  public Stored upload(byte[] data,String mime,String id){uploads++;String format=mime.equals("image/png")?"png":"jpg";var file=new Stored(id,1,format,data.length,2,2,malformed?"https://attacker.invalid/image":"https://res.cloudinary.com/fixture-cloud/image/upload/v1/"+id+"."+format);files.put(id,file);if(afterUpload!=null)afterUpload.run();return file;}
  public void delete(String id){if(failDeletes.contains(id))throw new IllegalStateException("Synthetic deletion failure");files.remove(id);}
 }
 @Autowired AvatarMedia avatarMedia;
 @Autowired com.agora.identity.application.ProfileService profiles;
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
 @BeforeEach void reset(){jdbc.execute("TRUNCATE identity.accounts,identity.spring_session CASCADE");mail.links.clear();mail.fail=false;avatarMedia.files.clear();avatarMedia.uploads=0;avatarMedia.malformed=false;avatarMedia.afterUpload=null;avatarMedia.beforeFind=null;avatarMedia.failDeletes.clear();}
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
 @Test void emailChangeProvesNewAddressAndRevokesOldSessions() throws Exception {
  var b=new Browser();UUID id=b.register();verify(b);b.login();String next="new-"+UUID.randomUUID()+"@example.test";
  assertThat(b.post("/me/email-changes",Map.of("currentPassword","wrong","newEmail",next)).statusCode()).isEqualTo(422);
  assertThat(b.post("/me/email-changes",Map.of("currentPassword","synthetic-long-password","newEmail",next)).statusCode()).isEqualTo(202);
  assertThat(b.post("/me/email-changes",Map.of("currentPassword","synthetic-long-password","newEmail","other@example.test")).statusCode()).isEqualTo(429);
  assertThat(b.login().statusCode()).isEqualTo(200);while(actions.dispatch()){}
  String token=mail.links.stream().filter(x->x.recipient().equals(next)&&x.purpose().equals("CHANGE_EMAIL")).findFirst().orElseThrow().token();
  assertThat(b.post("/auth/password-changes/confirm",Map.of("token",token)).statusCode()).isEqualTo(422);
  assertThat(b.post("/auth/email-changes/confirm",Map.of("token",token)).statusCode()).isEqualTo(204);
  assertThat(b.get("/me").statusCode()).isEqualTo(401);assertThat(b.login().statusCode()).isEqualTo(401);
  assertThat(b.post("/auth/sessions",Map.of("email",next,"password","synthetic-long-password")).statusCode()).isEqualTo(200);
  assertThat(b.post("/auth/email-changes/confirm",Map.of("token",token)).statusCode()).isEqualTo(422);
  assertThat(jdbc.queryForObject("SELECT email FROM identity.accounts WHERE id=?",String.class,id)).isEqualTo(next);
 }
 @Test void emailChangeRejectsDuplicatesAndLateUniqueRaceAtomically() throws Exception {
  var a=new Browser();UUID id=a.register();verify(a);a.login();var other=new Browser();other.register();
  assertThat(a.post("/me/email-changes",Map.of("currentPassword","synthetic-long-password","newEmail",other.email)).statusCode()).isEqualTo(409);
  assertThat(a.post("/me/email-changes",Map.of("currentPassword","synthetic-long-password","newEmail",a.email)).statusCode()).isEqualTo(422);
  String target="race-"+UUID.randomUUID()+"@example.test";assertThat(a.post("/me/email-changes",Map.of("currentPassword","synthetic-long-password","newEmail",target)).statusCode()).isEqualTo(202);
  while(actions.dispatch()){}String token=mail.links.stream().filter(x->x.recipient().equals(target)).findFirst().orElseThrow().token();
  var racer=new Browser();racer.email=target;racer.register();
  assertThat(a.post("/auth/email-changes/confirm",Map.of("token",token)).statusCode()).isEqualTo(409);
  assertThat(jdbc.queryForObject("SELECT email FROM identity.accounts WHERE id=?",String.class,id)).isEqualTo(a.email);assertThat(a.get("/me").statusCode()).isEqualTo(200);
 }
 @Test void displayNameHasIndependentVersionAndRejectsEscalation() throws Exception {
  var b=new Browser();UUID id=b.register();verify(b);b.login();var csrf=json.readTree(b.get("/auth/csrf").body()).path("token").asString();
  String body=json.writeValueAsString(Map.of("displayName","  Tên mới 🌱  ","expectedVersion",0));var response=b.send("PATCH","/me/profile",body,csrf);
  assertThat(response.statusCode()).isEqualTo(200);assertThat(json.readTree(response.body()).path("displayName").asString()).isEqualTo("Tên mới 🌱");assertThat(b.get("/me").statusCode()).isEqualTo(200);
  assertThat(jdbc.queryForObject("SELECT version FROM identity.accounts WHERE id=?",Long.class,id)).isEqualTo(1);
  assertThat(b.send("PATCH","/me/profile",body,csrf).statusCode()).isEqualTo(409);
  assertThat(b.send("PATCH","/me/profile",json.writeValueAsString(Map.of("displayName","Again","expectedVersion",1,"roles",List.of("ADMIN"))),csrf).statusCode()).isEqualTo(422);
  assertThat(b.send("PATCH","/me/profile",body,null).statusCode()).isEqualTo(403);
 }
 byte[] png()throws Exception{var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();}
 HttpResponse<String> avatar(Browser b,UUID id,long expected,byte[] data,String mime)throws Exception{
  String boundary="fixture-"+UUID.randomUUID();var bytes=new java.io.ByteArrayOutputStream();
  for(var field:Map.of("uploadId",id.toString(),"expectedVersion",Long.toString(expected)).entrySet())bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes());bytes.write(data);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes());
  String csrf=json.readTree(b.get("/auth/csrf").body()).path("token").asString();
  return b.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/avatar")).header("X-CSRF-TOKEN",csrf).header("Content-Type","multipart/form-data; boundary="+boundary).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void avatarReplayReplaceRemoveAndCleanupAreOwnedAndVersioned()throws Exception{
  var b=new Browser();b.register();verify(b);b.login();UUID first=UUID.randomUUID();byte[] image=png();
  var response=avatar(b,first,0,image,"image/png");assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(json.readTree(response.body()).path("avatar").path("id").asString()).isEqualTo(first.toString());
  assertThat(avatar(b,first,0,image,"image/png").statusCode()).isEqualTo(200);assertThat(avatarMedia.uploads).isEqualTo(1);
  var second=new Browser();second.register();verify(second);second.login();assertThat(avatar(second,first,0,image,"image/png").statusCode()).isEqualTo(409);
  UUID replacement=UUID.randomUUID();assertThat(avatar(b,replacement,0,image,"image/png").statusCode()).isEqualTo(409);assertThat(avatar(b,replacement,1,image,"image/png").statusCode()).isEqualTo(200);
  jdbc.update("UPDATE identity.avatar_uploads SET expires_at=clock_timestamp()-interval '2 minutes' WHERE state='DELETING'");profiles.cleanup();assertThat(avatarMedia.files).hasSize(1);assertThat(avatarMedia.files.keySet().iterator().next()).endsWith(replacement.toString());
  String csrf=json.readTree(b.get("/auth/csrf").body()).path("token").asString();assertThat(b.send("DELETE","/me/avatar",json.writeValueAsString(Map.of("expectedVersion",2)),csrf).statusCode()).isEqualTo(200);jdbc.update("UPDATE identity.avatar_uploads SET expires_at=clock_timestamp()-interval '2 minutes' WHERE state='DELETING'");profiles.cleanup();assertThat(avatarMedia.files).isEmpty();assertThat(b.get("/me").statusCode()).isEqualTo(200);
 }
 @Test void avatarValidationAndUntrustedMetadataNeverAttach()throws Exception{
  var b=new Browser();b.register();verify(b);b.login();
  assertThat(avatar(b,UUID.randomUUID(),0,png(),"image/jpeg").statusCode()).isEqualTo(415);
  assertThat(avatar(b,UUID.randomUUID(),0,new byte[5242881],"image/png").statusCode()).isEqualTo(413);
  assertThat(avatarMedia.uploads).isZero();avatarMedia.malformed=true;
  assertThat(avatar(b,UUID.randomUUID(),0,png(),"image/png").statusCode()).isEqualTo(503);
  assertThat(json.readTree(b.get("/me/profile").body()).path("avatar").isNull()).isTrue();
 }
 @Test void avatarRemoteRaceCannotAttachAfterSecurityOrProfileChange()throws Exception{
  var b=new Browser();UUID owner=b.register();verify(b);b.login();
  avatarMedia.afterUpload=()->jdbc.update("UPDATE identity.accounts SET profile_version=profile_version+1 WHERE id=?",owner);
  UUID first=UUID.randomUUID();assertThat(avatar(b,first,0,png(),"image/png").statusCode()).isEqualTo(409);
  avatarMedia.afterUpload=()->jdbc.update("UPDATE identity.accounts SET version=version+1 WHERE id=?",owner);
  assertThat(avatar(b,UUID.randomUUID(),1,png(),"image/png").statusCode()).isEqualTo(401);
  jdbc.update("UPDATE identity.avatar_uploads SET expires_at=clock_timestamp()-interval '2 minutes'");profiles.cleanup();assertThat(avatarMedia.files).isEmpty();
 }
 @Test void profileStrictInputsAndAuthenticatedScopeAreEnforced()throws Exception{
  var b=new Browser();assertThat(b.get("/me/profile").statusCode()).isEqualTo(401);b.register();verify(b);b.login();String csrf=json.readTree(b.get("/auth/csrf").body()).path("token").asString();
  for(Object name:List.of("x"," ","A\u0000B","A\nB","x".repeat(101),42))assertThat(b.send("PATCH","/me/profile",json.writeValueAsString(Map.of("displayName",name,"expectedVersion",0)),csrf).statusCode()).isEqualTo(422);
  for(Object version:List.of(-1,0.5,"0",9007199254740991L))assertThat(b.send("PATCH","/me/profile",json.writeValueAsString(Map.of("displayName","Name","expectedVersion",version)),csrf).statusCode()).isEqualTo(422);
 }
 @Test void concurrentAvatarReplayNeverStartsAnotherProviderWriteOrAttach()throws Exception{
  var b=new Browser();b.register();verify(b);b.login();UUID id=UUID.randomUUID();byte[] file=png();var arrived=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  avatarMedia.afterUpload=()->{arrived.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}};
  try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var first=executor.submit(()->avatar(b,id,0,file,"image/png"));assertThat(arrived.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   try{assertThat(avatar(b,id,0,file,"image/png").statusCode()).isEqualTo(409);}finally{release.countDown();}
   assertThat(first.get().statusCode()).isEqualTo(200);assertThat(avatarMedia.uploads).isEqualTo(1);
  }
 }
 @Test void cleanupFailuresYieldToLaterCandidates()throws Exception{
  var b=new Browser();UUID owner=b.register();verify(b);String oldest=null;
  for(int i=0;i<11;i++){UUID id=UUID.randomUUID();String publicId="agora/avatars/"+owner+"/"+id;if(i==0)oldest=publicId;
   jdbc.update("INSERT INTO identity.avatar_uploads(id,account_id,request_hash,expected_version,public_id,mime,bytes,width,height,expires_at) VALUES (?,?,?,0,?,'image/png',1,2,2,clock_timestamp()-interval '2 hours'+?*interval '1 second')",id,owner,"a".repeat(64),publicId,i);
   avatarMedia.files.put(publicId,new com.agora.media.ImageStorage.Stored(publicId,1,"png",1,2,2,"https://res.cloudinary.com/fixture-cloud/image/upload/v1/"+publicId+".png"));if(i<10)avatarMedia.failDeletes.add(publicId);
  }
  profiles.cleanup();profiles.cleanup();assertThat(avatarMedia.files).hasSize(10);assertThat(avatarMedia.files).containsKey(oldest);
 }
 @Test void lateProviderWriteAfterDeletionIsReconciledByExactTombstone()throws Exception{
  var b=new Browser();b.register();verify(b);b.login();UUID id=UUID.randomUUID();byte[] file=png();var arrived=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  avatarMedia.beforeFind=()->{arrived.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}};
  try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var late=executor.submit(()->avatar(b,id,0,file,"image/png"));assertThat(arrived.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   try{jdbc.update("UPDATE identity.avatar_uploads SET expires_at=clock_timestamp()-interval '2 minutes' WHERE id=?",id);profiles.cleanup();assertThat(jdbc.queryForObject("SELECT state FROM identity.avatar_uploads WHERE id=?",String.class,id)).isEqualTo("DELETED");}finally{release.countDown();}
   assertThat(late.get().statusCode()).isEqualTo(409);assertThat(avatarMedia.files).hasSize(1);
   jdbc.update("UPDATE identity.avatar_uploads SET retry_at=clock_timestamp() WHERE id=?",id);profiles.cleanup();assertThat(avatarMedia.files).isEmpty();
  }
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
