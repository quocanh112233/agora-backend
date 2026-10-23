package com.agora.identity.adapter.inbound;

import com.agora.identity.application.IdentityService.Unauthenticated;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
public class ApiErrors {
    private final JsonMapper json;
    public ApiErrors(JsonMapper json) { this.json = json; }

    public static String trace(HttpServletRequest request) {
        Object value = request.getAttribute("agora.traceId");
        if (value == null) {
            value = UUID.randomUUID().toString();
            request.setAttribute("agora.traceId", value);
        }
        return value.toString();
    }

    public void send(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Trace-Id", trace(request));
        json.writeValue(response.getOutputStream(), body(request, code, message));
    }

    private Map<String, Object> body(HttpServletRequest request, String code, String message) {
        return Map.of("code", code, "message", message, "traceId", trace(request));
    }

    @ExceptionHandler(Unauthenticated.class)
    ResponseEntity<?> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(401).body(body(request, "UNAUTHENTICATED", "Thông tin đăng nhập hoặc phiên không hợp lệ."));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(body(request, "EMAIL_IN_USE", "Email đã được sử dụng."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<?> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return ResponseEntity.unprocessableContent().body(Map.of("code", "VALIDATION_ERROR", "message", "Dữ liệu không hợp lệ.",
                "traceId", trace(request), "details", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidJson(HttpMessageNotReadableException exception, HttpServletRequest request) {
        boolean unknownField = exception.getMostSpecificCause() instanceof UnrecognizedPropertyException;
        return ResponseEntity.status(unknownField ? 422 : 400).body(body(request,
                unknownField ? "VALIDATION_ERROR" : "BAD_REQUEST", "Nội dung JSON không hợp lệ."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed traceId={} type={}",
                trace(request), exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body(request, "INTERNAL_ERROR", "Không thể xử lý yêu cầu."));
    }
}
