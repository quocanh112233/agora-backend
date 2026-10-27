package com.agora.commerce.adapter.inbound;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.CartFailure;
import com.agora.commerce.domain.Checkout.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/me")
public class CheckoutController {
    private final CheckoutService service;private final IdentityAccess access;private final JsonMapper json;
    public CheckoutController(CheckoutService service,IdentityAccess access,JsonMapper json){this.service=service;this.access=access;this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();}
    @PostMapping(value="/checkout-previews",consumes="application/json")
    public Preview preview(HttpServletRequest request)throws IOException{
        UUID actor=actor(request,true);query(request);var body=body(request,Set.of("cartId","cartVersion","shopId","skuIds"));
        UUID cart=fieldUuid(body,"cartId"),shop=fieldUuid(body,"shopId"),key=uuid(request.getHeader("Idempotency-Key"));long version=integer(body,"cartVersion",0,9007199254740991L);
        var list=body.path("skuIds");if(!list.isArray()||list.size()<1||list.size()>20)throw invalid();var skus=new ArrayList<UUID>();
        for(var node:list){if(!node.isString())throw invalid();skus.add(uuid(node.asString()));}if(new HashSet<>(skus).size()!=skus.size())throw invalid();
        var canonical=json.createObjectNode().put("cartId",cart.toString()).put("cartVersion",version).put("shopId",shop.toString());var ids=canonical.putArray("skuIds");skus.stream().sorted().forEach(id->ids.add(id.toString()));
        return service.preview(actor,key,hash(canonical.toString()),cart,version,shop,skus);
    }
    @PostMapping(value="/checkouts",consumes="application/json")
    public ResponseEntity<Attempt> confirm(HttpServletRequest request)throws IOException{
        UUID actor=actor(request,true);query(request);var body=body(request,Set.of("previewId"));
        var result=service.confirm(actor,uuid(request.getHeader("Idempotency-Key")),fieldUuid(body,"previewId"));return ResponseEntity.status(result.state().equals("PROCESSING")?202:200).body(result);
    }
    @GetMapping("/checkouts/{requestKey}")
    public Attempt attempt(@PathVariable String requestKey,HttpServletRequest request){UUID actor=actor(request,false);query(request);return service.attempt(actor,uuid(requestKey));}
    @GetMapping("/orders/{orderId}")
    public Order order(@PathVariable String orderId,HttpServletRequest request){UUID actor=actor(request,false);query(request);return service.order(actor,uuid(orderId));}
    @GetMapping("/orders")
    public Page orders(HttpServletRequest request){
        UUID actor=actor(request,false);var parameters=request.getParameterMap();
        if(!parameters.isEmpty()&&(!parameters.keySet().equals(Set.of("cursor"))||parameters.get("cursor").length!=1||parameters.get("cursor")[0].isEmpty()))throw invalid();
        return service.orders(actor,request.getParameter("cursor"));
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
    private UUID fieldUuid(JsonNode body,String field){if(!body.path(field).isString())throw invalid();return uuid(body.path(field).asString());}
    private UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw invalid();return id;}catch(IllegalArgumentException|NullPointerException e){throw invalid();}}
    private long integer(JsonNode body,String field,long min,long max){var node=body.path(field);if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<min||node.longValue()>max)throw invalid();return node.longValue();}
    private String hash(String canonical){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private CartFailure invalid(){return new CartFailure(422,"VALIDATION_ERROR","Provide only documented checkout fields with valid IDs and versions.");}
    private CartFailure malformed(){return new CartFailure(400,"BAD_REQUEST","Provide one valid JSON object within 4096 bytes.");}
}
