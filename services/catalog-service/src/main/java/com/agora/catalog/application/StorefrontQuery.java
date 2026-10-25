package com.agora.catalog.application;

import com.agora.catalog.domain.CatalogFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public record StorefrontQuery(String q, String category, String origin, UUID shopId,
                              Long minPriceVnd, Long maxPriceVnd, Cursor after) {
    public record Cursor(Instant createdAt, UUID id) {}
    private static final Set<String> KEYS=Set.of("q","category","origin","shopId","minPriceVnd","maxPriceVnd","cursor");
    public static StorefrontQuery parse(Map<String,String[]> params) {
        params.forEach((key,value)->{if(!KEYS.contains(key)||value==null||value.length!=1||value[0]==null)throw invalid();});
        String q=text(value(params,"q"),100),origin=text(value(params,"origin"),120),category=value(params,"category").strip();
        if(!category.isEmpty()&&!Set.of("PRODUCE","SEAFOOD").contains(category))throw invalid();
        String shop=value(params,"shopId").strip();
        Long min=price(value(params,"minPriceVnd")),max=price(value(params,"maxPriceVnd"));
        if(min!=null&&max!=null&&min>max)throw invalid();
        var query=new StorefrontQuery(q,category,origin,shop.isEmpty()?null:uuid(shop),min,max,null);
        if(!params.containsKey("cursor"))return query;
        String cursor=value(params,"cursor");
        try {
            if(!cursor.matches("[A-Za-z0-9_-]{1,256}"))throw invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3||!parts[2].equals(query.fingerprint()))throw invalid();
            Instant time=Instant.parse(parts[0]);int year=time.atOffset(ZoneOffset.UTC).getYear();
            if(year<1||year>9999)throw invalid();
            return new StorefrontQuery(q,category,origin,query.shopId,min,max,new Cursor(time,uuid(parts[1])));
        } catch(RuntimeException e){throw invalid();}
    }
    public String next(Instant at,UUID id){return Base64.getUrlEncoder().withoutPadding().encodeToString((at+"|"+id+"|"+fingerprint()).getBytes(StandardCharsets.UTF_8));}
    private String fingerprint(){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",q,category,origin,String.valueOf(shopId),String.valueOf(minPriceVnd),String.valueOf(maxPriceVnd)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String value(Map<String,String[]> p,String key){return p.containsKey(key)?p.get(key)[0]:"";}
    private static String text(String value,int limit){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isISOControl(c))throw invalid();
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
        String normalized=value.strip();if(normalized.codePointCount(0,normalized.length())>limit)throw invalid();return normalized;
    }
    private static Long price(String value){
        String v=value.strip();if(v.isEmpty())return null;
        try {if(!v.matches("[0-9]{1,10}"))throw invalid();long n=Long.parseLong(v);if(n<1||n>1_000_000_000)throw invalid();return n;}
        catch(NumberFormatException e){throw invalid();}
    }
    public static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw invalid();return id;}catch(IllegalArgumentException e){throw invalid();}}
    private static CatalogFailure invalid(){return new CatalogFailure(422,"VALIDATION_ERROR","Public product filters are invalid.");}
}
