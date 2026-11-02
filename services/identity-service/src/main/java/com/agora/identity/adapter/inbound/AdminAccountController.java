package com.agora.identity.adapter.inbound;

import com.agora.identity.application.*;
import com.agora.identity.domain.AdminAccountFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {
    private final IdentityService identity;
    private final AdminAccountService accounts;
    public AdminAccountController(IdentityService identity, AdminAccountService accounts) { this.identity = identity; this.accounts = accounts; }
    private AdminAccountStore.Actor admin(Principal principal, HttpServletRequest request) {
        try {
            var actor = identity.authenticated(UUID.fromString(principal.getName()), request.getSession().getAttribute("AGORA_ACCOUNT_TYPE"), request.getSession().getAttribute("AGORA_ACCOUNT_VERSION"));
            if (!actor.roles().contains("ADMIN")) throw new AdminAccountFailure(403, "FORBIDDEN", "Yêu cầu quyền quản trị hiện hành.");
            return new AdminAccountStore.Actor(actor.id(), actor.accountType().name(), (Long) request.getSession().getAttribute("AGORA_ACCOUNT_VERSION"));
        } catch (DataAccessException e) { throw AdminAccountService.unavailable(); }
    }
    @GetMapping
    AdminAccountStore.Page list(Principal principal, HttpServletRequest request) {
        admin(principal, request);
        var filter = AdminAccountInput.filter(request);
        return accounts.list(filter, request.getParameter("cursor"));
    }
    @PutMapping("/{accountId}/status")
    AdminAccountStore.View change(@PathVariable String accountId, @RequestBody tools.jackson.databind.JsonNode body,
            Principal principal, HttpServletRequest request) {
        var actor = admin(principal, request);
        if (!request.getParameterMap().isEmpty()) throw AdminAccountInput.invalid();
        return accounts.change(actor, AdminAccountInput.id(accountId), AdminAccountInput.change(body));
    }
    @GetMapping("/{accountId}/status-history")
    AdminAccountStore.HistoryPage history(@PathVariable String accountId, Principal principal, HttpServletRequest request) {
        admin(principal, request);
        return accounts.history(AdminAccountInput.id(accountId), AdminAccountInput.historyCursor(request));
    }
    @GetMapping("/{accountId}")
    AdminAccountStore.View detail(@PathVariable String accountId, Principal principal, HttpServletRequest request) {
        admin(principal, request);
        if (!request.getParameterMap().isEmpty()) throw AdminAccountInput.invalid();
        return accounts.find(AdminAccountInput.id(accountId));
    }
}
