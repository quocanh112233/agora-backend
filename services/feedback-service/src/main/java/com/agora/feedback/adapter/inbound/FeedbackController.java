package com.agora.feedback.adapter.inbound;
import com.agora.feedback.application.*;
import com.agora.feedback.domain.*;
import com.agora.feedback.domain.Feedback.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
@RestController
@RequestMapping("/api/v1")
public class FeedbackController {
    private final FeedbackService service;private final FeedbackAccess access;private final JsonMapper json;
    public FeedbackController(FeedbackService service,FeedbackAccess access,JsonMapper json){this.service=service;this.access=access;this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();}
    @PostMapping(value="/me/orders/{orderId}/items/{skuId}/review",consumes="application/json")
    public ResponseEntity<Review> create(@PathVariable String orderId,@PathVariable String skuId,HttpServletRequest request)throws IOException{
        Actor actor=actor(request,true);var body=body(request,Set.of("rating","content"));var result=service.create(actor,uuid(orderId),uuid(skuId),content(body));return ResponseEntity.status(result.created()?201:200).body(result.review());
    }
    @GetMapping("/me/orders/{orderId}/items/{skuId}/review")
    public Review owned(@PathVariable String orderId,@PathVariable String skuId,HttpServletRequest request){Actor actor=actor(request,false);query(request,Set.of());return service.owned(actor,uuid(orderId),uuid(skuId));}
    @PatchMapping(value="/me/reviews/{id}",consumes="application/json")
    public Review edit(@PathVariable String id,HttpServletRequest request)throws IOException{Actor actor=actor(request,true);var body=body(request,Set.of("rating","content","expectedVersion"));return service.edit(actor,uuid(id),version(body),content(body));}
    @PutMapping(value="/seller/reviews/{id}/reply",consumes="application/json")
    public PublicReview reply(@PathVariable String id,HttpServletRequest request)throws IOException{Actor actor=actor(request,true);var body=body(request,Set.of("content","expectedVersion"));return service.reply(actor,uuid(id),version(body),text(body,"content")).publicView();}
    @PutMapping(value="/admin/reviews/{id}/visibility",consumes="application/json")
    public Review moderate(@PathVariable String id,HttpServletRequest request)throws IOException{Actor actor=actor(request,true);var body=body(request,Set.of("hidden","reason","expectedVersion"));if(!body.path("hidden").isBoolean())throw Feedback.invalid();return service.moderate(actor,uuid(id),version(body),body.path("hidden").asBoolean(),text(body,"reason"));}
    @GetMapping("/seller/reviews") public ManagedPage sellerList(HttpServletRequest request){var actor=actor(request,false);query(request,Set.of("cursor"));return service.managedList(actor,false,request.getParameter("cursor"));}
    @GetMapping("/admin/reviews") public ManagedPage adminList(HttpServletRequest request){var actor=actor(request,false);query(request,Set.of("cursor"));return service.managedList(actor,true,request.getParameter("cursor"));}
    @GetMapping("/seller/reviews/{id}") public ManagedReview sellerRead(@PathVariable String id,HttpServletRequest request){var actor=actor(request,false);query(request,Set.of());return service.managed(actor,false,uuid(id));}
    @GetMapping("/admin/reviews/{id}") public ManagedReview adminRead(@PathVariable String id,HttpServletRequest request){var actor=actor(request,false);query(request,Set.of());return service.managed(actor,true,uuid(id));}
    @GetMapping("/products/{productId}/reviews")
    public Page list(@PathVariable String productId,HttpServletRequest request){query(request,Set.of("cursor"));return service.list(uuid(productId),request.getParameter("cursor"));}
    @GetMapping("/products/{productId}/review-summary")
    public Summary summary(@PathVariable String productId,HttpServletRequest request){query(request,Set.of());return service.summary(uuid(productId));}
    private Actor actor(HttpServletRequest request,boolean unsafe){String session=null;if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("AGORA_SESSION")){if(session!=null)throw new FeedbackFailure(401,"UNAUTHENTICATED","Ambiguous session cookie.");session=cookie.getValue();}return access.resolve(session,request.getHeader("X-CSRF-TOKEN"),unsafe);}
    private JsonNode body(HttpServletRequest request,Set<String> fields)throws IOException{query(request,Set.of());byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw malformed();JsonNode body;try{body=json.readTree(bytes);}catch(RuntimeException e){throw malformed();}if(body==null||body.isMissingNode())throw malformed();if(!body.isObject()||body.size()!=fields.size())throw Feedback.invalid();for(String field:fields)if(!body.has(field))throw Feedback.invalid();return body;}
    private Content content(JsonNode body){var value=body.path("rating");if(!value.isIntegralNumber()||!value.canConvertToInt())throw Feedback.invalid();return new Content(value.intValue(),text(body,"content"));}
    private String text(JsonNode body,String field){if(!body.path(field).isString())throw Feedback.invalid();return body.path(field).asString();}
    private long version(JsonNode body){var value=body.path("expectedVersion");if(!value.isIntegralNumber()||!value.canConvertToLong())throw Feedback.invalid();long v=value.longValue();Feedback.version(v);return v;}
    private UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw Feedback.invalid();return id;}catch(IllegalArgumentException|NullPointerException e){throw Feedback.invalid();}}
    private void query(HttpServletRequest request,Set<String> allowed){for(var entry:request.getParameterMap().entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().length!=1)throw Feedback.invalid();}
    private FeedbackFailure malformed(){return new FeedbackFailure(400,"BAD_REQUEST","Provide one valid JSON object within 16384 bytes.");}
}
