package com.agora.commerce.application;

import com.agora.commerce.domain.CaptureApplication.*;
import java.util.*;

public interface PaymentStore {
    OwnedOrder order(UUID buyer,UUID order);
    Receipt receipt(UUID buyer,UUID order,UUID capture);
    Receipt record(Request request);
    Optional<Work> acquire(UUID capture);
    void complete(Work work,String catalogState,boolean reconciliation);
    void observe(OwnedOrder order,String catalogState,UUID consumedCapture);
    List<UUID> pending();
    List<OwnedOrder> due();
}
