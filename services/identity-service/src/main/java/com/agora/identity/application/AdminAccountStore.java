package com.agora.identity.application;

import java.time.Instant;
import java.util.*;

public interface AdminAccountStore {
    record View(UUID id, String email, String displayName, String accountType, Set<String> roles,
                String status, long version, Instant createdAt, Instant updatedAt) {
        public View { roles = Set.copyOf(roles); }
    }
    record Filter(String q, String status, String role, String accountType) {}
    record Position(Instant createdAt, UUID id) {}
    record Page(List<View> items, String nextCursor) { public Page { items = List.copyOf(items); } }
    record Actor(UUID id, String accountType, long version) {}
    record Change(String status, long expectedVersion, String reason) {}
    record History(UUID id, UUID targetId, UUID actorId, String actorDisplayName, String previousStatus,
                   String status, String reason, long version, Instant createdAt) {}
    record HistoryPage(List<History> items, String nextCursor) { public HistoryPage { items = List.copyOf(items); } }
    View change(Actor actor, UUID target, Change change);
    List<History> history(UUID target, Position position, int limit);
    List<View> list(Filter filter, Position position, int limit);
    Optional<View> find(UUID id);
}
