package com.agora.identity.adapter.outbound;

import com.agora.identity.application.AdminAccountStore;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminAccounts implements AdminAccountStore {
    private static final String COLUMNS = "a.id,a.email,a.display_name,a.account_type,a.status,a.version,a.created_at,a.updated_at";
    private final JdbcTemplate jdbc;
    public JdbcAdminAccounts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<View> list(Filter filter, Position position, int limit) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM identity.accounts a WHERE true");
        var args = new ArrayList<Object>();
        if (filter.q() != null) {
            sql.append(" AND (a.email ILIKE ? ESCAPE '!' OR a.display_name ILIKE ? ESCAPE '!')");
            String query = "%" + filter.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(query); args.add(query);
        }
        if (filter.status() != null) { sql.append(" AND a.status = ?"); args.add(filter.status()); }
        if (filter.accountType() != null) { sql.append(" AND a.account_type = ?"); args.add(filter.accountType()); }
        if (filter.role() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM identity.account_roles r WHERE r.account_id = a.id AND r.role = ?)");
            args.add(filter.role());
        }
        if (position != null) { sql.append(" AND (a.created_at,a.id) < (?,?)"); args.add(Timestamp.from(position.createdAt())); args.add(position.id()); }
        sql.append(" ORDER BY a.created_at DESC,a.id DESC LIMIT ?"); args.add(limit);
        return roles(jdbc.query(sql.toString(), this::map, args.toArray()));
    }
    @Override public Optional<View> find(UUID id) {
        return roles(jdbc.query("SELECT " + COLUMNS + " FROM identity.accounts a WHERE a.id = ?", this::map, id)).stream().findFirst();
    }
    @Override
    @org.springframework.transaction.annotation.Transactional
    public View change(Actor actor, UUID target, Change change) {
        // Stable lock order also serializes the supported operator ADMIN-grant script.
        jdbc.query("SELECT id FROM identity.accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), actor.id(), target);
        var currentActor = find(actor.id()).orElseThrow(com.agora.identity.application.IdentityService.Unauthenticated::new);
        if (!currentActor.status().equals("ACTIVE") || !Objects.equals(currentActor.accountType(), actor.accountType())
                || currentActor.version() != actor.version()) throw new com.agora.identity.application.IdentityService.Unauthenticated();
        if (!currentActor.roles().contains("ADMIN")) throw failure(403, "FORBIDDEN", "Yêu cầu quyền quản trị hiện hành.");
        var current = find(target).orElseThrow(() -> failure(404, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản."));
        if (actor.id().equals(target) || current.roles().contains("ADMIN"))
            throw failure(403, "PROTECTED_ACCOUNT", "Không thể thay đổi trạng thái tài khoản quản trị.");
        if (current.version() != change.expectedVersion() || current.status().equals(change.status()) || current.version() >= 9007199254740991L)
            throw failure(409, "ACCOUNT_CONFLICT", "Trạng thái hoặc phiên bản tài khoản đã thay đổi.");
        int updated = jdbc.update("""
                UPDATE identity.accounts SET status = ?, version = version + 1, updated_at = clock_timestamp()
                WHERE id = ? AND version = ? AND status = ?
                """, change.status(), target, change.expectedVersion(), current.status());
        if (updated != 1) throw failure(409, "ACCOUNT_CONFLICT", "Phiên bản tài khoản đã thay đổi.");
        jdbc.update("""
                INSERT INTO identity.account_status_audit
                    (id,target_id,actor_id,actor_display_name,previous_status,status,reason,version_after)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), target, actor.id(), currentActor.displayName(), current.status(), change.status(),
                change.reason(), current.version() + 1);
        jdbc.update("DELETE FROM identity.spring_session WHERE principal_name = ?", target.toString());
        return find(target).orElseThrow();
    }
    @Override public List<History> history(UUID target, Position position, int limit) {
        var args = new ArrayList<Object>(); args.add(target);
        String where = "target_id = ?";
        if (position != null) { where += " AND (created_at,id) < (?,?)"; args.add(Timestamp.from(position.createdAt())); args.add(position.id()); }
        args.add(limit);
        return jdbc.query("SELECT id,target_id,actor_id,actor_display_name,previous_status,status,reason,version_after,created_at "
                + "FROM identity.account_status_audit WHERE " + where + " ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, row) -> new History(rs.getObject("id", UUID.class), rs.getObject("target_id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_display_name"), rs.getString("previous_status"),
                        rs.getString("status"), rs.getString("reason"), rs.getLong("version_after"), rs.getTimestamp("created_at").toInstant()), args.toArray());
    }
    private static com.agora.identity.domain.AdminAccountFailure failure(int status, String code, String message) {
        return new com.agora.identity.domain.AdminAccountFailure(status, code, message);
    }
    private View map(ResultSet rs, int row) throws SQLException {
        long version = rs.getLong("version");
        if (version < 0 || version > 9007199254740991L)
            throw new org.springframework.dao.DataIntegrityViolationException("Invalid stored account version.");
        return new View(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("account_type"), Set.of(), rs.getString("status"), version,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private List<View> roles(List<View> accounts) {
        if (accounts.isEmpty()) return accounts;
        var roles = new HashMap<UUID, Set<String>>();
        String placeholders = String.join(",", Collections.nCopies(accounts.size(), "?"));
        jdbc.query("SELECT account_id,role FROM identity.account_roles WHERE account_id IN (" + placeholders + ")",
                rs -> { roles.computeIfAbsent(rs.getObject("account_id", UUID.class), key -> new HashSet<>()).add(rs.getString("role")); },
                accounts.stream().map(View::id).toArray());
        return accounts.stream().map(a -> new View(a.id(), a.email(), a.displayName(), a.accountType(),
                roles.getOrDefault(a.id(), Set.of()), a.status(), a.version(), a.createdAt(), a.updatedAt())).toList();
    }
}
