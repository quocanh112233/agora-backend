package com.agora.payment.adapter.inbound;
import com.agora.payment.application.PaymentNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/payments/vnpay")
public class VnpayController {
    private final PaymentNotifications service;
    public VnpayController(PaymentNotifications service){this.service=service;}
    @GetMapping("/ipn") public Map<String,String> ipn(HttpServletRequest request){String code=service.ipn(request.getQueryString());return Map.of("RspCode",code,"Message",code.equals("00")?"Confirmed":code.equals("02")?"Already confirmed":"Not confirmed");}
    @GetMapping("/return") public ResponseEntity<Void> returned(HttpServletRequest request){var target=service.returned(request.getQueryString());return ResponseEntity.status(303).location(URI.create("/orders/"+target.orderId()+"?attemptId="+target.id()+"&requestKey="+target.requestKey())).build();}
}
