package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.CatalogOffers;
import com.agora.commerce.domain.CartFailure;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GrpcCatalogOffers implements CatalogOffers {
    private final CatalogOffersGrpc.CatalogOffersBlockingStub stub;
    public GrpcCatalogOffers(@Qualifier("catalogChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        stub=CatalogOffersGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public Map<UUID,Offer> find(List<UUID> skus){
        if(skus.isEmpty())return Map.of();
        if(skus.size()>20||new HashSet<>(skus).size()!=skus.size())throw new IllegalArgumentException("Invalid offer window.");
        var result=new HashMap<UUID,Offer>();
        try{
            var request=ResolveOffersRequest.newBuilder();skus.forEach(id->request.addSkuIds(id.toString()));
            var response=stub.withDeadlineAfter(10,TimeUnit.SECONDS).resolveOffers(request.build());
            if(response.getOffersCount()>skus.size())throw unavailable();
            for(var offer:response.getOffersList()){
                UUID sku=uuid(offer.getSkuId()),product=uuid(offer.getProductId()),shop=uuid(offer.getShopId());
                if(!skus.contains(sku)||result.containsKey(sku)||!text(offer.getProductName(),3,160)||!text(offer.getShopName(),1,120)||!text(offer.getSkuLabel(),1,100)||offer.getPriceVnd()<1||offer.getPriceVnd()>1000000000||offer.getNetWeightGrams()<1||offer.getNetWeightGrams()>50000||!offer.hasThumbnail())throw unavailable();
                var image=offer.getThumbnail();String url=image.getUrl();
                if(image.getWidth()<1||image.getHeight()<1||(long)image.getWidth()*image.getHeight()>20000000||url.length()>512||!url.matches("https://res\\.cloudinary\\.com/[A-Za-z0-9_-]+/image/upload/v[1-9][0-9]*/agora/products/"+product+"/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)"))throw unavailable();
                result.put(sku,new Offer(product,sku,shop,offer.getShopName(),offer.getProductName(),offer.getSkuLabel(),offer.getPriceVnd(),offer.getNetWeightGrams(),offer.getInStock(),new Image(url,image.getWidth(),image.getHeight())));
            }
        }catch(StatusRuntimeException|IllegalArgumentException e){throw unavailable();}
        return Map.copyOf(result);
    }
    private boolean text(String s,int min,int max){int count=s.codePointCount(0,s.length());return !s.isBlank()&&count>=min&&count<=max;}
    private UUID uuid(String s){UUID id=UUID.fromString(s);if(!id.toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return id;}
    private CartFailure unavailable(){return new CartFailure(503,"CATALOG_UNAVAILABLE","Product information is unavailable.");}
}
