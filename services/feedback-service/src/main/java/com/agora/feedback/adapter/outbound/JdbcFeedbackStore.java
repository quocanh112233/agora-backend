package com.agora.feedback.adapter.outbound;

import com.agora.feedback.application.FeedbackStore;
import com.agora.feedback.domain.*;
import com.agora.feedback.domain.Feedback.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JdbcFeedbackStore implements FeedbackStore {
    private final JdbcTemplate jdbc;
    public JdbcFeedbackStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private Review row(ResultSet r)throws SQLException{return new Review(r.getObject("id",UUID.class),r.getObject("buyer_id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("sku_id",UUID.class),r.getObject("product_id",UUID.class),r.getObject("shop_id",UUID.class),r.getInt("rating"),r.getString("content"),r.getString("reply"),r.getBoolean("hidden"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
    @Override @Transactional(timeout=5)
    public Created create(Line line,Content content){
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"feedback-line:"+line.orderId()+":"+line.skuId());
        var found=jdbc.query("SELECT * FROM feedback.reviews WHERE order_id=? AND sku_id=?",(r,n)->row(r),line.orderId(),line.skuId());
        if(!found.isEmpty()){
            var existing=found.getFirst();if(!existing.buyerId().equals(line.buyerId())||!existing.productId().equals(line.productId())||!existing.shopId().equals(line.shopId()))throw Feedback.missing();
            if(existing.rating()!=content.rating()||!existing.content().equals(content.content()))throw Feedback.conflict();return new Created(existing,false);
        }
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content) VALUES(?,?,?,?,?,?,?,?)",id,line.buyerId(),line.orderId(),line.skuId(),line.productId(),line.shopId(),content.rating(),content.content());
        return new Created(find(id),true);
    }
    @Override @Transactional(readOnly=true)
    public ManagedReview managed(UUID shop,UUID id){
        var rows=shop==null?jdbc.query("SELECT * FROM feedback.reviews WHERE id=?",(r,n)->row(r),id):jdbc.query("SELECT * FROM feedback.reviews WHERE id=? AND shop_id=?",(r,n)->row(r),id,shop);
        return rows.stream().findFirst().orElseThrow(Feedback::missing).managedView();
    }
    @Override @Transactional(readOnly=true)
    public ManagedPage managedList(UUID shop,String cursor){
        String scope=shop==null?"admin":"seller-"+shop;
        String sql="SELECT * FROM feedback.reviews WHERE true";var args=new ArrayList<Object>();
        if(shop!=null){sql+=" AND shop_id=?";args.add(shop);}
        if(cursor!=null){
            try{
                if(cursor.length()>256||!cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",-1);
                if(parts.length!=3||!parts[0].equals(scope))throw new IllegalArgumentException();
                long micros=Long.parseLong(parts[1]);if(micros<0||micros>253402300799999999L)throw new IllegalArgumentException();
                UUID id=UUID.fromString(parts[2]);if(!id.toString().equals(parts[2]))throw new IllegalArgumentException();
                args.add(Timestamp.from(Instant.ofEpochSecond(Math.floorDiv(micros,1000000),Math.floorMod(micros,1000000)*1000)));args.add(id);
            }catch(RuntimeException e){throw Feedback.invalid();}
            sql+=" AND (created_at,id)<(?,?)";
        }
        var reviews=jdbc.query(sql+" ORDER BY created_at DESC,id DESC LIMIT 21",(r,n)->row(r),args.toArray());String next=null;
        if(reviews.size()>20){var anchor=reviews.get(19);long micros=Math.addExact(Math.multiplyExact(anchor.createdAt().getEpochSecond(),1000000),anchor.createdAt().getNano()/1000);next=Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+micros+":"+anchor.id()).getBytes(StandardCharsets.UTF_8));}
        return new ManagedPage(reviews.stream().limit(20).map(Review::managedView).toList(),next);
    }
    private Review find(UUID id){return jdbc.query("SELECT * FROM feedback.reviews WHERE id=?",(r,n)->row(r),id).stream().findFirst().orElseThrow(Feedback::missing);}
    @Override @Transactional(readOnly=true)
    public Review owned(UUID buyer,UUID order,UUID sku){return jdbc.query("SELECT * FROM feedback.reviews WHERE buyer_id=? AND order_id=? AND sku_id=?",(r,n)->row(r),buyer,order,sku).stream().findFirst().orElseThrow(Feedback::missing);}
    private Review lock(UUID id){return jdbc.query("SELECT * FROM feedback.reviews WHERE id=? FOR UPDATE",(r,n)->row(r),id).stream().findFirst().orElseThrow(Feedback::missing);}
    private void version(Review review,long expected){if(review.version()!=expected)throw Feedback.conflict();}
    @Override @Transactional(timeout=5)
    public Review edit(UUID buyer,UUID id,long version,Content content){var review=lock(id);if(!review.buyerId().equals(buyer))throw Feedback.missing();version(review,version);jdbc.update("UPDATE feedback.reviews SET rating=?,content=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",content.rating(),content.content(),id);return find(id);}
    @Override @Transactional(timeout=5)
    public Review reply(UUID shop,UUID id,long version,String text){var review=lock(id);if(!review.shopId().equals(shop))throw Feedback.missing();version(review,version);jdbc.update("UPDATE feedback.reviews SET reply=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",text,id);return find(id);}
    @Override @Transactional(timeout=5)
    public Review moderate(UUID actor,UUID id,long version,boolean hidden,String reason){
        var review=lock(id);version(review,version);jdbc.update("UPDATE feedback.reviews SET hidden=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",hidden,id);
        jdbc.update("INSERT INTO feedback.moderation_audit(review_id,actor_id,hidden,reason,review_version) VALUES(?,?,?,?,?)",id,actor,hidden,reason,version+1);return find(id);
    }
    @Override @Transactional(readOnly=true)
    public Page list(UUID product,String cursor){
        List<Review> reviews;
        if(cursor==null)reviews=jdbc.query("SELECT * FROM feedback.reviews WHERE product_id=? AND NOT hidden ORDER BY created_at DESC,id DESC LIMIT 21",(r,n)->row(r),product);
        else{
            Instant time;UUID id;
            try{
                if(cursor.length()>256||!cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",-1);if(parts.length!=3||!parts[0].equals(product.toString()))throw new IllegalArgumentException();
                long micros=Long.parseLong(parts[1]);if(micros<0||micros>253402300799999999L)throw new IllegalArgumentException();time=Instant.ofEpochSecond(Math.floorDiv(micros,1000000),Math.floorMod(micros,1000000)*1000);id=UUID.fromString(parts[2]);if(!id.toString().equals(parts[2]))throw new IllegalArgumentException();
            }catch(RuntimeException e){throw Feedback.invalid();}
            reviews=jdbc.query("SELECT * FROM feedback.reviews WHERE product_id=? AND NOT hidden AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT 21",(r,n)->row(r),product,Timestamp.from(time),id);
        }
        String next=null;
        if(reviews.size()>20){var anchor=reviews.get(19);long micros=Math.addExact(Math.multiplyExact(anchor.createdAt().getEpochSecond(),1000000),anchor.createdAt().getNano()/1000);next=Base64.getUrlEncoder().withoutPadding().encodeToString((product+":"+micros+":"+anchor.id()).getBytes(StandardCharsets.UTF_8));}
        return new Page(reviews.stream().limit(20).map(Review::publicView).toList(),next);
    }
    @Override @Transactional(readOnly=true)
    public Summary summary(UUID product){return jdbc.queryForObject("SELECT count(*) AS total,avg(rating)::double precision AS average FROM feedback.reviews WHERE product_id=? AND NOT hidden",(r,n)->new Summary(r.getLong("total"),r.getObject("average",Double.class)),product);}
}
