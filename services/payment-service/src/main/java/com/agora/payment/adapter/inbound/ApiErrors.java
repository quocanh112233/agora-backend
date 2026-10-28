package com.agora.payment.adapter.inbound;

import com.agora.payment.domain.PaymentFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    static String trace(HttpServletRequest request){Object id=request.getAttribute("agora.traceId");if(id==null){id=UUID.randomUUID().toString();request.setAttribute("agora.traceId",id);}return id.toString();}
    private ResponseEntity<?> response(HttpServletRequest request,int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"traceId",trace(request),"details",List.of()));}
    @ExceptionHandler(PaymentFailure.class) ResponseEntity<?> business(PaymentFailure e,HttpServletRequest request){return response(request,e.status(),e.code(),e.getMessage());}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e,HttpServletRequest request){
        if(e instanceof ErrorResponse error)return response(request,error.getStatusCode().value(),"BAD_REQUEST","Request cannot be processed.");
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed traceId={} type={}",trace(request),e.getClass().getSimpleName());
        return response(request,500,"INTERNAL_ERROR","Request cannot be processed.");
    }
}
