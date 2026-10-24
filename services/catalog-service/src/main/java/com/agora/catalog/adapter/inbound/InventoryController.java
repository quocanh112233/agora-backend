package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.*;
import com.agora.catalog.application.InventoryStore.*;
import com.agora.catalog.application.SellerAccess.Seller;
import com.agora.catalog.domain.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/seller/products/{productId}/lots")
public class InventoryController {
    private final SellerAccess access;private final InventoryService inventory;private final JsonMapper json;
    public InventoryController(SellerAccess access,InventoryService inventory,JsonMapper json){
        this.access=access;this.inventory=inventory;this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }
    private Seller seller(HttpServletRequest request,boolean unsafe){
        String session=null;
        if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("AGORA_SESSION")){
            if(session!=null)throw ProductInput.invalid("Ambiguous session cookie.");session=cookie.getValue();
        }
        return access.resolve(session,request.getHeader("X-CSRF-TOKEN"),unsafe);
    }
    @GetMapping
    Page list(@PathVariable String productId,@RequestParam(defaultValue="0")String page,@RequestParam(defaultValue="20")String pageSize,HttpServletRequest request){
        Seller s=seller(request,false);
        return inventory.list(s.shopId(),ProductInput.uuid(productId),ProductInput.page(page,0,1000000),ProductInput.page(pageSize,1,100));
    }
    @PostMapping(consumes="application/json")
    ResponseEntity<InventoryLot.View> create(@PathVariable String productId,HttpServletRequest request)throws IOException{
        Seller s=seller(request,true);UUID product=ProductInput.uuid(productId);JsonNode body=body(request);
        Set<String> allowed=Set.of("skuId","code","quantity","producedOn","expiresOn");
        if(!allowed.containsAll(body.propertyNames())||!body.path("skuId").isString()||!body.path("code").isString())throw invalid();
        JsonNode q=body.path("quantity");if(!q.isIntegralNumber()||!q.canConvertToLong()||q.longValue()<1||q.longValue()>1000000)throw invalid();
        String code=body.path("code").asString();if(!code.matches("[A-Z0-9][A-Z0-9_-]{0,39}"))throw invalid();
        var input=new InventoryLot.Input(ProductInput.uuid(body.path("skuId").asString()),code,q.longValue(),
                body.hasNonNull("producedOn")?date(body.path("producedOn")):null,date(body.path("expiresOn")));
        Result result=inventory.create(key(s,product,null,"CREATE_LOT",body,request),input,ApiErrors.trace(request));
        return ResponseEntity.status(result.status()).body(result.lot());
    }
    @PutMapping(value="/{lotId}/status",consumes="application/json")
    InventoryLot.View change(@PathVariable String productId,@PathVariable String lotId,HttpServletRequest request)throws IOException{
        Seller s=seller(request,true);UUID product=ProductInput.uuid(productId),lot=ProductInput.uuid(lotId);JsonNode body=body(request);
        if(!body.propertyNames().equals(Set.of("status","expectedVersion"))||!body.path("status").isString())throw invalid();
        String target=body.path("status").asString();if(!Set.of("ACTIVE","BLOCKED").contains(target))throw invalid();
        return inventory.changeStatus(key(s,product,lot,"CHANGE_LOT_STATUS",body,request),lot,ProductInput.version(body),target,ApiErrors.trace(request)).lot();
    }
    private Key key(Seller s,UUID product,UUID lot,String operation,JsonNode body,HttpServletRequest request){
        var canonical=json.createObjectNode().set("body",body).put("lotId",lot==null?"":lot.toString());
        return new Key(s.accountId(),s.shopId(),product,operation,ProductInput.uuid(request.getHeader("Idempotency-Key")),ProductInput.hash(json,canonical,s.shopId(),product));
    }
    private JsonNode body(HttpServletRequest request)throws IOException{
        byte[] bytes=request.getInputStream().readNBytes(2049);
        if(bytes.length>2048)throw badJson();JsonNode body;
        try{body=json.readTree(bytes);}catch(RuntimeException e){throw badJson();}
        if(body==null||body.isMissingNode())throw badJson();if(!body.isObject())throw invalid();return body;
    }
    private LocalDate date(JsonNode node){
        if(!node.isString()||!node.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
        try{LocalDate date=LocalDate.parse(node.asString());if(date.getYear()<1)throw invalid();return date;}catch(DateTimeException e){throw invalid();}
    }
    private CatalogFailure badJson(){return new CatalogFailure(400,"BAD_REQUEST","Provide one valid JSON object within 2048 bytes.");}
    private CatalogFailure invalid(){return ProductInput.invalid("Provide only documented lot fields with valid dates and integer package counts.");}
}
