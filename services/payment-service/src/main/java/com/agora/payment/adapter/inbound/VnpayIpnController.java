package com.agora.payment.adapter.inbound;
import com.agora.payment.application.PaymentNotifications;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/payments/vnpay")
@ConditionalOnProperty(name="agora.vnpay.ipn-enabled",havingValue="true")
public class VnpayIpnController {
    private final PaymentNotifications service;
    public VnpayIpnController(PaymentNotifications service){this.service=service;}
    @GetMapping("/ipn") public Map<String,String> ipn(HttpServletRequest request){String code=service.ipn(request.getQueryString());return Map.of("RspCode",code,"Message",code.equals("00")?"Confirmed":code.equals("02")?"Already confirmed":"Not confirmed");}
}
