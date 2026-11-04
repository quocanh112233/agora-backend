package com.agora.identity.application;
import java.time.Instant;
import java.util.*;
public interface EmailActionStore {
 record State(UUID id,String email,String passwordHash,String status,String accountType,long version,boolean verified,Instant now) {
  @Override public String toString(){return "EmailAccountState[redacted]";}
 }
 record Ref(UUID id,UUID accountId) {}
 record Action(UUID id,UUID accountId,String purpose,long version,String pendingHash,String pendingEmail,Instant expiresAt,boolean usable) {
  @Override public String toString(){return "EmailAction[redacted]";}
 }
 record Delivery(UUID id,String recipient,String purpose,byte[] cipher,Instant expiresAt) {
  @Override public String toString(){return "EmailDelivery[redacted]";}
 }
 Optional<UUID> find(String email);
 Optional<State> lock(UUID id);
 boolean coolingDown(UUID id,String purpose);
 void queue(UUID id,State account,String purpose,byte[] digest,byte[] cipher,String pendingHash,Instant expires);
 void targetEmail(UUID action,String email);
 void applyEmail(State account,UUID action,String email);
 Optional<Ref> locate(byte[] digest);
 Action action(UUID id);
 void apply(State account,UUID action,String passwordHash);
 Optional<Delivery> pending();
 void sent(UUID id);
 void retry(UUID id);
 void cleanup();
}
