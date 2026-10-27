package com.agora.catalog.application;

import com.agora.catalog.domain.Checkout.*;
import com.agora.catalog.domain.Reservation.Request;
import java.util.Optional;

public interface CheckoutStore {
    Quote quote(QuoteRequest request);
    Optional<Result> replayCheckout(Request request);
    Result holdCheckout(Request request,boolean shopActive);
}
