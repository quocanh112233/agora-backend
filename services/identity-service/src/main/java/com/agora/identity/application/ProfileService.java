package com.agora.identity.application;
import com.agora.identity.domain.AdminAccountFailure;
import com.agora.media.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class ProfileService {
 public record Actor(UUID id,long version) {}
 private final ProfileStore store;private final EmailActionStore accounts;private final ImageStorage media;private final TransactionTemplate tx;
 public ProfileService(ProfileStore store,EmailActionStore accounts,ImageStorage media,org.springframework.transaction.PlatformTransactionManager manager){this.store=store;this.accounts=accounts;this.media=media;tx=new TransactionTemplate(manager);}
 private EmailActionStore.State lock(Actor actor){var a=accounts.lock(actor.id()).orElseThrow(IdentityService.Unauthenticated::new);if(!"ACTIVE".equals(a.status())||a.accountType()==null||!a.verified()||a.version()!=actor.version())throw new IdentityService.Unauthenticated();return a;}
 public ProfileStore.Profile read(Actor actor){return tx.execute(s->{lock(actor);return store.read(actor.id());});}
 public ProfileStore.Profile name(Actor actor,long expected,String value){String name=value.strip();int length=name.codePointCount(0,name.length());if(length<2||length>100||name.codePoints().anyMatch(Character::isISOControl))throw invalid();return tx.execute(s->{lock(actor);store.name(actor.id(),expected,name);return store.read(actor.id());});}
 public ProfileStore.Profile remove(Actor actor,long expected){return tx.execute(s->{lock(actor);store.remove(actor.id(),expected);return store.read(actor.id());});}
 public ProfileStore.Profile avatar(Actor actor,long expected,UUID id,ImageUpload file){
  String hash;
  try{var digest=MessageDigest.getInstance("SHA-256");digest.update(file.mime().getBytes(StandardCharsets.UTF_8));digest.update((":"+expected+":").getBytes(StandardCharsets.UTF_8));hash=HexFormat.of().formatHex(digest.digest(file.data()));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  var intent=tx.execute(s->{var account=lock(actor);var prior=store.upload(id);
   if(prior.isPresent()){var row=prior.get();if(!row.accountId().equals(actor.id())||!row.requestHash().equals(hash)||!Set.of("PENDING","ATTACHED").contains(row.state()))throw conflict();return row;}
   if(store.read(actor.id()).version()!=expected)throw conflict();store.reserve(id,actor.id(),hash,expected,file);return store.upload(id).orElseThrow();
  });
  var current=read(actor);
  if("ATTACHED".equals(intent.state())){if(current.avatar()!=null&&current.avatar().id().equals(id))return current;throw conflict();}
  // Expiry is checked before and after remote work; cleanup waits beyond the provider deadline.
  tx.executeWithoutResult(s->{var account=lock(actor);var row=store.upload(id).orElseThrow();if(!row.expiresAt().isAfter(account.now())||!"PENDING".equals(row.state()))throw conflict();store.beginUpload(id);});
  ImageStorage.Stored asset=media.find(intent.publicId()).orElseGet(()->media.upload(file.data(),file.mime(),intent.publicId()));
  String format=file.mime().equals("image/png")?"png":"jpg";
  if(!asset.publicId().equals(intent.publicId())||asset.bytes()!=file.data().length||asset.width()!=file.width()||asset.height()!=file.height()||!asset.format().equals(format)||!asset.url().matches("https://res[.]cloudinary[.]com/[A-Za-z0-9_-]{1,128}/image/upload/v"+asset.version()+"/"+java.util.regex.Pattern.quote(intent.publicId())+"[.]"+format))throw new AdminAccountFailure(503,"MEDIA_UNAVAILABLE","Metadata ảnh không khớp.");
  return tx.execute(s->{var account=lock(actor);var row=store.upload(id).orElseThrow();var profile=store.read(actor.id());
   if("ATTACHED".equals(row.state())&&profile.avatar()!=null&&profile.avatar().id().equals(id))return profile;
   if(!"UPLOADING".equals(row.state())||!row.expiresAt().isAfter(account.now()))throw conflict();
   store.attach(actor.id(),expected,id,asset.url());return store.read(actor.id());
  });
 }
 public void cleanup(){for(UUID id:store.cleanupCandidates())try{
  var candidate=tx.execute(s->{var row=store.upload(id).orElseThrow();return row;});
  boolean delete=Boolean.TRUE.equals(tx.execute(s->{var account=accounts.lock(candidate.accountId());if(account.isEmpty())return false;var row=store.upload(id).orElseThrow();var profile=store.read(row.accountId());if(store.attached(id))return false;if("ATTACHED".equals(row.state()))return false;if(row.expiresAt().plusSeconds(60).isAfter(account.get().now()))return false;store.deleting(id);return true;}));
  // Tombstones stay durable: a provider may finish an upload after the request deadline.
  if(delete){if(!"DELETED".equals(candidate.state())||media.find(candidate.publicId()).isPresent())media.delete(candidate.publicId());tx.executeWithoutResult(s->store.deleted(id));}
 }catch(RuntimeException e){tx.executeWithoutResult(s->store.retry(id));org.slf4j.LoggerFactory.getLogger(getClass()).warn("Avatar cleanup deferred type={}",e.getClass().getSimpleName());}}
 static AdminAccountFailure invalid(){return new AdminAccountFailure(422,"VALIDATION_ERROR","Dữ liệu hồ sơ không hợp lệ.");}
 static AdminAccountFailure conflict(){return new AdminAccountFailure(409,"PROFILE_CONFLICT","Hồ sơ hoặc yêu cầu ảnh đã thay đổi. Hãy tải lại.");}
}
