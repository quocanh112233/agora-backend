package com.agora.commerce.adapter.inbound;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.CartFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/me/cart")
public class CartController {
    private final CartService carts;private final IdentityAccess access;private final JsonMapper json;
    public CartController(CartService carts,IdentityAccess access,JsonMapper json){this.carts=carts;this.access=access;this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();}
    @GetMapping public CartService.View read(HttpServletRequest request){UUID actor=actor(request,false);query(request);return carts.read(actor);}
    @PutMapping(value="/items/{skuId}",consumes="application/json")
    public CartStore.Mutation put(@PathVariable String skuId,HttpServletRequest request)throws IOException{
        UUID actor=actor(request,true);query(request);UUID sku=uuid(skuId);
        JsonNode body=body(request,Set.of("cartId","productId","quantity","expectedVersion"));
        return carts.put(key(actor,"PUT_ITEM",sku,body,request),fieldUuid(body,"cartId"),fieldUuid(body,"productId"),sku,(int)integer(body,"quantity",1,99),integer(body,"expectedVersion",0,9007199254740991L));
    }
    @DeleteMapping(value="/items/{skuId}",consumes="application/json")
    public CartStore.Mutation remove(@PathVariable String skuId,HttpServletRequest request)throws IOException{
        UUID actor=actor(request,true);query(request);UUID sku=uuid(skuId);JsonNode body=body(request,Set.of("cartId","expectedVersion"));
        return carts.remove(key(actor,"DELETE_ITEM",sku,body,request),fieldUuid(body,"cartId"),sku,integer(body,"expectedVersion",0,9007199254740991L));
    }
    private UUID actor(HttpServletRequest request,boolean unsafe){
        String session=null;if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("AGORA_SESSION")){
            if(session!=null)throw new CartFailure(401,"UNAUTHENTICATED","Ambiguous session cookie.");session=cookie.getValue();
        }
        return access.resolve(session,request.getHeader("X-CSRF-TOKEN"),unsafe);
    }
    private void query(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    private JsonNode body(HttpServletRequest request,Set<String> fields)throws IOException{
        byte[] bytes=request.getInputStream().readNBytes(4097);if(bytes.length>4096)throw malformed();JsonNode body;
        try{body=json.readTree(bytes);}catch(RuntimeException e){throw malformed();}
        if(body==null||body.isMissingNode())throw malformed();if(!body.isObject()||!body.propertyNames().equals(fields))throw invalid();return body;
    }
    private CartStore.Key key(UUID actor,String operation,UUID sku,JsonNode body,HttpServletRequest request){
        var normalized=json.createObjectNode();body.propertyNames().stream().sorted().forEach(name->normalized.set(name,body.path(name)));
        var canonical=json.createObjectNode().put("operation",operation).put("skuId",sku.toString()).set("body",normalized);
        try{return new CartStore.Key(actor,uuid(request.getHeader("Idempotency-Key")),operation,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private UUID fieldUuid(JsonNode body,String field){if(!body.path(field).isString())throw invalid();return uuid(body.path(field).asString());}
    private UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw invalid();return id;}catch(IllegalArgumentException|NullPointerException e){throw invalid();}}
    private long integer(JsonNode body,String field,long min,long max){var node=body.path(field);if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<min||node.longValue()>max)throw invalid();return node.longValue();}
    private CartFailure invalid(){return new CartFailure(422,"VALIDATION_ERROR","Provide only documented cart fields with valid IDs, versions and quantities.");}
    private CartFailure malformed(){return new CartFailure(400,"BAD_REQUEST","Provide one valid JSON object within 4096 bytes.");}
}
