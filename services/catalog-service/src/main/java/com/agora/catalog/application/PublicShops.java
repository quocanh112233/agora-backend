package com.agora.catalog.application;

import java.util.*;

public interface PublicShops {
    record Shop(UUID id,String name) {}
    Map<UUID,Shop> find(List<UUID> ids);
}
