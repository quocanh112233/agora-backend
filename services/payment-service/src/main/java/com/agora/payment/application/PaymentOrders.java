package com.agora.payment.application;
import com.agora.payment.domain.Payment.*;
import java.util.UUID;
public interface PaymentOrders {Payable payable(UUID buyer,UUID order); Application apply(Capture capture);}
