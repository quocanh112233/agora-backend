package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcStorefrontStore implements StorefrontStore {
    private final JdbcTemplate jdbc;
    public JdbcStorefrontStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String STOCK="l.state='ACTIVE' AND l.expires_on>? AND l.on_hand-l.held-l.allocated>0";
    private static final String BASE="""
        SELECT p.id,p.shop_id,p.name,p.category,p.condition,p.origin,p.created_at,
          p.description,p.producer_name,p.storage_instructions,price.min_price,photo.url,photo.width,photo.height,
          EXISTS(SELECT 1 FROM catalog.inventory_lots l WHERE l.product_id=p.id AND %s) AS in_stock
        FROM catalog.products p
        JOIN LATERAL(SELECT min(s.price_vnd) AS min_price FROM catalog.skus s WHERE s.product_id=p.id) price ON price.min_price IS NOT NULL
        JOIN LATERAL(SELECT u.url,u.width,u.height FROM catalog.product_images pi
          JOIN catalog.image_uploads u ON u.id=pi.upload_id AND u.product_id=pi.product_id AND u.shop_id=pi.shop_id
          WHERE pi.product_id=p.id AND u.state='UPLOADED' ORDER BY pi.position LIMIT 1) photo ON true
        WHERE p.status='PUBLISHED'
        """.formatted(STOCK);
    private Candidate candidate(ResultSet r)throws SQLException{
        return new Candidate(r.getObject("id",UUID.class),r.getObject("shop_id",UUID.class),r.getString("name"),r.getString("category"),r.getString("condition"),r.getString("origin"),r.getLong("min_price"),r.getBoolean("in_stock"),new Image(r.getString("url"),r.getInt("width"),r.getInt("height")),r.getTimestamp("created_at").toInstant());
    }
    @Override public List<Candidate> candidates(StorefrontQuery q,LocalDate today){
        var sql=new StringBuilder(BASE);var args=new ArrayList<Object>();args.add(today);
        if(!q.q().isEmpty()){sql.append(" AND lower(p.name) LIKE lower(?) ESCAPE '\\'");args.add(literal(q.q()));}
        if(!q.origin().isEmpty()){sql.append(" AND lower(p.origin) LIKE lower(?) ESCAPE '\\'");args.add(literal(q.origin()));}
        if(!q.category().isEmpty()){sql.append(" AND p.category=?");args.add(q.category());}
        if(q.shopId()!=null){sql.append(" AND p.shop_id=?");args.add(q.shopId());}
        if(q.minPriceVnd()!=null){sql.append(" AND price.min_price>=?");args.add(q.minPriceVnd());}
        if(q.maxPriceVnd()!=null){sql.append(" AND price.min_price<=?");args.add(q.maxPriceVnd());}
        if(q.after()!=null){sql.append(" AND (p.created_at,p.id)<(?,?)");args.add(Timestamp.from(q.after().createdAt()));args.add(q.after().id());}
        sql.append(" ORDER BY p.created_at DESC,p.id DESC LIMIT 21");
        return jdbc.query(sql.toString(),(r,n)->candidate(r),args.toArray());
    }
    private String literal(String s){return "%"+s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    @Override public List<Offer> offers(List<UUID> skuIds,LocalDate today){
        var sql="""
            SELECT p.id AS product_id,p.shop_id,p.name,s.id AS sku_id,s.label,s.price_vnd,s.net_weight_grams,
              photo.url,photo.width,photo.height,
              EXISTS(SELECT 1 FROM catalog.inventory_lots l WHERE l.sku_id=s.id AND %s) AS in_stock
            FROM catalog.skus s
            JOIN catalog.products p ON p.id=s.product_id AND p.shop_id=s.shop_id
            JOIN LATERAL(SELECT u.url,u.width,u.height FROM catalog.product_images pi
              JOIN catalog.image_uploads u ON u.id=pi.upload_id AND u.product_id=pi.product_id AND u.shop_id=pi.shop_id
              WHERE pi.product_id=p.id AND u.state='UPLOADED' ORDER BY pi.position LIMIT 1) photo ON true
            WHERE p.status='PUBLISHED' AND s.id IN (%s)
            ORDER BY s.id
            """.formatted(STOCK,String.join(",",Collections.nCopies(skuIds.size(),"?")));
        var args=new ArrayList<Object>();args.add(today);args.addAll(skuIds);
        return jdbc.query(sql,(r,n)->new Offer(r.getObject("product_id",UUID.class),r.getObject("sku_id",UUID.class),
                r.getObject("shop_id",UUID.class),r.getString("name"),r.getString("label"),r.getLong("price_vnd"),
                r.getInt("net_weight_grams"),r.getBoolean("in_stock"),new Image(r.getString("url"),r.getInt("width"),r.getInt("height"))),args.toArray());
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Optional<Details> detail(UUID id,LocalDate today){
        var roots=jdbc.query(BASE+" AND p.id=?",(r,n)->new Details(candidate(r),r.getString("description"),r.getString("producer_name"),r.getString("storage_instructions"),List.of(),List.of()),today,id);
        if(roots.isEmpty())return Optional.empty();
        var skus=jdbc.query("SELECT s.*,EXISTS(SELECT 1 FROM catalog.inventory_lots l WHERE l.sku_id=s.id AND "+STOCK+") AS in_stock FROM catalog.skus s WHERE s.product_id=? ORDER BY s.position",(r,n)->new Sku(r.getObject("id",UUID.class),r.getString("label"),r.getLong("price_vnd"),r.getInt("net_weight_grams"),r.getString("sale_unit"),r.getBoolean("in_stock")),today,id);
        var images=jdbc.query("SELECT u.url,u.width,u.height FROM catalog.product_images pi JOIN catalog.image_uploads u ON u.id=pi.upload_id AND u.product_id=pi.product_id AND u.shop_id=pi.shop_id WHERE pi.product_id=? AND u.state='UPLOADED' ORDER BY pi.position",(r,n)->new Image(r.getString("url"),r.getInt("width"),r.getInt("height")),id);
        var root=roots.getFirst();return Optional.of(new Details(root.product(),root.description(),root.producerName(),root.storageInstructions(),skus,images));
    }
}
