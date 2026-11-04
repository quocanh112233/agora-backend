package com.agora.identity.application;
import java.time.Instant;
import java.util.UUID;
public interface EmailPort {
 void send(UUID id,String recipient,String purpose,String token,Instant expiresAt);
}
