package com.agora.payment.adapter.inbound;
import com.agora.payment.application.PaymentNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/payments/vnpay")
public class VnpayController {
    private final PaymentNotifications service;
    public VnpayController(PaymentNotifications service){this.service=service;}
    @GetMapping("/return") public ResponseEntity<Void> returned(HttpServletRequest request){var target=service.returned(request.getQueryString());return ResponseEntity.status(303).location(URI.create("/orders/"+target.orderId()+"?attemptId="+target.id()+"&requestKey="+target.requestKey())).build();}
}
