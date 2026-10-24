package com.agora.catalog.application;

import com.agora.catalog.domain.InventoryLot;
import java.util.*;

public interface InventoryStore {
    record Key(UUID actor,UUID shopId,UUID productId,String operation,UUID key,String hash){}
    record Result(int status,InventoryLot.View lot){}
    record Page(List<InventoryLot.View> items,int page,int pageSize,long totalItems){}
    Optional<Result> claim(Key key);
    Optional<InventoryLot> lockOwned(UUID shopId,UUID productId,UUID lotId);
    List<InventoryLot> list(UUID shopId,UUID productId,int page,int pageSize);
    long count(UUID shopId,UUID productId);
    void insert(InventoryLot lot);
    void update(InventoryLot lot,long expectedVersion);
    void audit(InventoryLot lot,UUID actor,String action,String traceId);
    void complete(Key key,Result result);
}
