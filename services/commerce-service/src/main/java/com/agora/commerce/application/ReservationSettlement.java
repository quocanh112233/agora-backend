package com.agora.commerce.application;

import com.agora.commerce.domain.CaptureApplication.OwnedOrder;
import java.util.UUID;

public interface ReservationSettlement {
    record Result(String state,UUID consumedCapture) {}
    Result get(OwnedOrder order);
    Result consume(OwnedOrder order,UUID capture);
}
