package com.agora.catalog.adapter.inbound;

import com.agora.catalog.domain.CatalogFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    static String trace(HttpServletRequest request) {
        Object trace = request.getAttribute("agora.traceId");
        if (trace == null) { trace = UUID.randomUUID().toString(); request.setAttribute("agora.traceId", trace); }
        return trace.toString();
    }
    private ResponseEntity<?> response(HttpServletRequest request, int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "traceId", trace(request), "details", List.of()));
    }
    @ExceptionHandler(CatalogFailure.class)
    ResponseEntity<?> business(CatalogFailure e, HttpServletRequest request) {
        return response(request, e.status(), e.code(), e.getMessage());
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(HttpServletRequest request) { return response(request, 409, "SKU_CODE_CONFLICT", "SKU code is already used in this shop."); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> badJson(HttpServletRequest request) { return response(request, 400, "BAD_REQUEST", "Request must contain valid JSON."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse error)
            return response(request, error.getStatusCode().value(), "BAD_REQUEST", "Request cannot be processed.");
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed traceId={} type={}", trace(request), e.getClass().getSimpleName());
        return response(request, 500, "INTERNAL_ERROR", "Request cannot be processed.");
    }
}
