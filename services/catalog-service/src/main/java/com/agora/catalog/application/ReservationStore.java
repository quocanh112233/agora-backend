package com.agora.catalog.application;
import com.agora.catalog.domain.Reservation;
import com.agora.catalog.domain.Reservation.Request;
import java.util.*;
public interface ReservationStore {
    Optional<Reservation> replay(Request request);
    Reservation create(Request request);
    Reservation get(UUID buyer,UUID checkout);
    Reservation release(UUID buyer,UUID checkout);
    int expire();
}
