package com.agora.identity.application;
import java.time.Instant;
import java.util.*;
public interface ProfileStore {
 record Avatar(UUID id,String url) {}
 record Profile(UUID accountId,String email,String displayName,long version,Avatar avatar) {}
 record Upload(UUID id,UUID accountId,String requestHash,long expectedVersion,String publicId,String mime,long bytes,int width,int height,String url,String state,Instant expiresAt) {}
 Profile read(UUID account);
 void name(UUID account,long expected,String name);
 Optional<Upload> upload(UUID id);
 void reserve(UUID id,UUID account,String digest,long expected,com.agora.media.ImageUpload image);
 void beginUpload(UUID id);
 void retry(UUID id);
 void attach(UUID account,long expected,UUID upload,String url);
 void remove(UUID account,long expected);
 List<UUID> cleanupCandidates();
 boolean attached(UUID id);
 void deleting(UUID id);
 void deleted(UUID id);
}
