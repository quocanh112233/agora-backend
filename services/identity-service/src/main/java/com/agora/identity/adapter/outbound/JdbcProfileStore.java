package com.agora.identity.adapter.outbound;
import com.agora.identity.application.ProfileStore;
import com.agora.identity.domain.AdminAccountFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class JdbcProfileStore implements ProfileStore {
 private final JdbcTemplate jdbc;
 public JdbcProfileStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Profile read(UUID id){return jdbc.queryForObject("SELECT a.id,a.email,a.display_name,a.profile_version,u.id AS upload_id,u.url FROM identity.accounts a LEFT JOIN identity.avatar_uploads u ON u.id=a.avatar_upload_id AND u.state='ATTACHED' WHERE a.id=?",(r,n)->new Profile(id,r.getString("email"),r.getString("display_name"),r.getLong("profile_version"),r.getObject("upload_id")==null?null:new Avatar(r.getObject("upload_id",UUID.class),r.getString("url"))),id);}
 public void name(UUID id,long expected,String name){check(jdbc.update("UPDATE identity.accounts SET display_name=?,profile_version=profile_version+1,updated_at=clock_timestamp() WHERE id=? AND profile_version=?",name,id,expected));}
 public Optional<Upload> upload(UUID id){return jdbc.query("SELECT * FROM identity.avatar_uploads WHERE id=? FOR UPDATE",(r,n)->new Upload(id,r.getObject("account_id",UUID.class),r.getString("request_hash"),r.getLong("expected_version"),r.getString("public_id"),r.getString("mime"),r.getLong("bytes"),r.getInt("width"),r.getInt("height"),r.getString("url"),r.getString("state"),r.getTimestamp("expires_at").toInstant()),id).stream().findFirst();}
 public void reserve(UUID id,UUID account,String hash,long expected,com.agora.media.ImageUpload image){
  long pending=jdbc.queryForObject("SELECT count(*) FROM identity.avatar_uploads WHERE account_id=? AND (state IN ('PENDING','UPLOADING') OR created_at>clock_timestamp()-interval '1 minute')",Long.class,account);
  if(pending>=5)throw new AdminAccountFailure(429,"AVATAR_LIMIT","Bạn đã gửi nhiều ảnh. Chờ trước khi thử lại.");
  jdbc.update("INSERT INTO identity.avatar_uploads(id,account_id,request_hash,expected_version,public_id,mime,bytes,width,height) VALUES (?,?,?,?,?,?,?,?,?)",id,account,hash,expected,"agora/avatars/"+account+"/"+id,image.mime(),image.data().length,image.width(),image.height());}
 public void beginUpload(UUID id){check(jdbc.update("UPDATE identity.avatar_uploads SET state='UPLOADING' WHERE id=? AND state='PENDING'",id));}
 public void retry(UUID id){jdbc.update("UPDATE identity.avatar_uploads SET retry_at=clock_timestamp()+interval '1 minute' WHERE id=?",id);}
 public void attach(UUID account,long expected,UUID upload,String url){
  var old=read(account).avatar();
  check(jdbc.update("UPDATE identity.accounts SET avatar_upload_id=?,profile_version=profile_version+1,updated_at=clock_timestamp() WHERE id=? AND profile_version=?",upload,account,expected));
  jdbc.update("UPDATE identity.avatar_uploads SET state='ATTACHED',url=? WHERE id=?",url,upload);
  if(old!=null&&!old.id().equals(upload))deleting(old.id());
 }
 public void remove(UUID account,long expected){var old=read(account).avatar();check(jdbc.update("UPDATE identity.accounts SET avatar_upload_id=NULL,profile_version=profile_version+1,updated_at=clock_timestamp() WHERE id=? AND profile_version=?",account,expected));if(old!=null)deleting(old.id());}
 public List<UUID> cleanupCandidates(){return jdbc.query("SELECT id FROM identity.avatar_uploads WHERE (state='DELETED' OR (state IN ('PENDING','UPLOADING','DELETING') AND expires_at<clock_timestamp()-interval '1 minute')) AND retry_at<=clock_timestamp() ORDER BY retry_at,expires_at LIMIT 10",(r,n)->r.getObject(1,UUID.class));}
 public boolean attached(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.accounts WHERE avatar_upload_id=?)",Boolean.class,id));}
 public void deleting(UUID id){jdbc.update("UPDATE identity.avatar_uploads SET state='DELETING' WHERE id=?",id);}
 public void deleted(UUID id){jdbc.update("UPDATE identity.avatar_uploads SET state='DELETED',retry_at=clock_timestamp()+interval '1 day' WHERE id=? AND state='DELETING'",id);}
 private static void check(int count){if(count!=1)throw new AdminAccountFailure(409,"PROFILE_CONFLICT","Hồ sơ đã thay đổi. Hãy tải lại trước khi sửa.");}
}
