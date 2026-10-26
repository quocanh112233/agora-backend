package com.agora.commerce.application;

import com.agora.commerce.domain.Cart;
import java.util.*;

public interface CartStore {
    record Key(UUID actor,UUID key,String operation,String hash) {}
    record Mutation(long version) {}
    Cart read(UUID actor);
    Optional<Mutation> replay(Key key);
    Mutation put(Key key,UUID product,UUID sku,int quantity,long expectedVersion);
    Mutation remove(Key key,UUID sku,long expectedVersion);
}
