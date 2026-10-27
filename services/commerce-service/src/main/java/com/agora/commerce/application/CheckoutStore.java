package com.agora.commerce.application;

import com.agora.commerce.domain.Checkout.*;
import java.util.*;

public interface CheckoutStore {
    Optional<Preview> replayPreview(UUID actor,UUID key,String hash);
    Preview savePreview(UUID actor,UUID key,String hash,Snapshot snapshot);
    Attempt claim(UUID actor,UUID key,UUID preview);
    Attempt attempt(UUID actor,UUID key);
    Optional<Work> acquire(UUID actor,UUID key);
    void complete(Work work,CheckoutCatalog.Decision decision);
    List<Map.Entry<UUID,UUID>> pending();
    Order order(UUID actor,UUID id);
    Page orders(UUID actor,String cursor);
}
