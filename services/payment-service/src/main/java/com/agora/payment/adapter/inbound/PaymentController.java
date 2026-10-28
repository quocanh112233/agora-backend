package com.agora.payment.adapter.inbound;
import com.agora.payment.application.*;
import com.agora.payment.domain.*;
import com.agora.payment.domain.Payment.Attempt;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
@RestController
@RequestMapping("/api/v1/me")
public class PaymentController {
    private final PaymentService service;private final IdentityAccess access;private final JsonMapper json;
    public PaymentController(PaymentService service,IdentityAccess access,JsonMapper json){this.service=service;this.access=access;this.json=json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();}
    @PostMapping(value="/orders/{orderId}/payment-attempts",consumes="application/json")
    public ResponseEntity<Attempt> create(@PathVariable String orderId,HttpServletRequest request)throws IOException {
        UUID actor=actor(request,true);query(request);byte[] bytes=request.getInputStream().readNBytes(4097);if(bytes.length>4096)throw malformed();JsonNode body;
        try{body=json.readTree(bytes);}catch(RuntimeException e){throw malformed();}
        if(body==null||body.isMissingNode())throw malformed();if(!body.isObject()||!body.isEmpty())throw invalid();
        var result=service.create(actor,uuid(request.getHeader("Idempotency-Key")),uuid(orderId),request.getRemoteAddr());return ResponseEntity.status(result.created()?201:200).body(result.attempt());
    }
    @GetMapping("/payment-attempts/{attemptId}") public Attempt byId(@PathVariable String attemptId,HttpServletRequest request){UUID actor=actor(request,false);query(request);return service.byId(actor,uuid(attemptId));}
    @GetMapping("/payment-attempts/by-request-key/{requestKey}") public Attempt byKey(@PathVariable String requestKey,HttpServletRequest request){UUID actor=actor(request,false);query(request);return service.byKey(actor,uuid(requestKey));}
    private UUID actor(HttpServletRequest request,boolean unsafe){String session=null;if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("AGORA_SESSION")){if(session!=null)throw new PaymentFailure(401,"UNAUTHENTICATED","Ambiguous session cookie.");session=cookie.getValue();}return access.resolve(session,request.getHeader("X-CSRF-TOKEN"),unsafe);}
    private UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw invalid();return id;}catch(IllegalArgumentException|NullPointerException e){throw invalid();}}
    private void query(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    private PaymentFailure invalid(){return new PaymentFailure(422,"VALIDATION_ERROR","Provide only documented payment fields and valid IDs.");}
    private PaymentFailure malformed(){return new PaymentFailure(400,"BAD_REQUEST","Provide one valid JSON object within 4096 bytes.");}
}
