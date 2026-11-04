package com.agora.identity.adapter.outbound;
import com.agora.identity.application.EmailPort;
import com.agora.contracts.email.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class GrpcEmail implements EmailPort,AutoCloseable {
 private final ManagedChannel channel;private final EmailDeliveryGrpc.EmailDeliveryBlockingStub rpc;
 public GrpcEmail(@Value("${agora.email.host}")String host,@Value("${agora.email.port}")int port,@Value("${agora.grpc.token}")String token){
  if(!java.util.Set.of("127.0.0.1","localhost","::1").contains(host))throw new IllegalArgumentException("Email gRPC requires loopback; add mTLS before remote use.");
  channel=ManagedChannelBuilder.forAddress(host,port).usePlaintext().maxInboundMessageSize(4096).build();
  var headers=new Metadata();if(!token.isBlank())headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
  rpc=EmailDeliveryGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
 }
 public void send(UUID id,String recipient,String purpose,String token,Instant expires){
  var response=rpc.withDeadlineAfter(8,TimeUnit.SECONDS).sendLink(SendLinkRequest.newBuilder().setDeliveryId(id.toString()).setRecipient(recipient).setPurpose(LinkPurpose.valueOf(purpose)).setToken(token).setExpiresAtEpochSeconds(expires.getEpochSecond()).build());
  if(!response.getAccepted())throw new IllegalStateException("Email delivery not accepted.");
 }
 @jakarta.annotation.PreDestroy public void close(){channel.shutdownNow();}
}
