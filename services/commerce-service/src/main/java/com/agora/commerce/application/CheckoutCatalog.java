package com.agora.commerce.application;

import com.agora.commerce.domain.Cart;
import com.agora.commerce.domain.Checkout.*;
import java.util.*;

public interface CheckoutCatalog {
    Snapshot quote(UUID previewId,UUID cartId,long version,UUID shop,List<Cart.Item> items);
    record Decision(String failure,java.time.Instant expiresAt) {}
    Decision hold(Work work);
}
