package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.PublicShops;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.contracts.merchant.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GrpcPublicShops implements PublicShops {
    private final MerchantAccessGrpc.MerchantAccessBlockingStub merchant;
    public GrpcPublicShops(@Qualifier("merchantChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        merchant=MerchantAccessGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public Map<UUID,Shop> find(List<UUID> ids){
        var unique=new ArrayList<>(new LinkedHashSet<>(ids));if(unique.size()>20)throw new IllegalArgumentException("Public shop window exceeds 20.");
        var result=new HashMap<UUID,Shop>();
        try {
            for(int offset=0;offset<unique.size();offset+=5){
                var batch=unique.subList(offset,Math.min(offset+5,unique.size()));
                var request=FindPublicShopsRequest.newBuilder();batch.forEach(id->request.addShopIds(id.toString()));
                var response=merchant.withDeadlineAfter(2,TimeUnit.SECONDS).findPublicShops(request.build());
                if(response.getShopsCount()>batch.size())throw unavailable();
                for(var shop:response.getShopsList()){
                    UUID id=UUID.fromString(shop.getId());String name=shop.getName();
                    if(!id.toString().equals(shop.getId())||!batch.contains(id)||result.containsKey(id)||name.isBlank()||name.codePointCount(0,name.length())>120)throw unavailable();
                    result.put(id,new Shop(id,name));
                }
            }
        } catch(StatusRuntimeException|IllegalArgumentException e){throw unavailable();}
        return Map.copyOf(result);
    }
    private CatalogFailure unavailable(){return new CatalogFailure(503,"MERCHANT_UNAVAILABLE","Public shop information is unavailable.");}
}
