package com.agora.identity.adapter.inbound;

import com.agora.identity.application.IdentityService;
import com.agora.identity.domain.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identity;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;

    public IdentityController(IdentityService identity, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository tokens) {
        this.identity = identity; this.contexts = contexts; this.tokens = tokens;
    }

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/auth/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    Account register(@Valid @RequestBody Registration request) {
        return identity.register(request.email(), request.password(), request.displayName());
    }

    @PostMapping("/auth/sessions")
    Account login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        Account account = identity.login(input.email(), input.password());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(account.id().toString(), null,
                account.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(tokens).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return account;
    }

    @GetMapping("/me")
    Account me(Principal principal) { return identity.current(UUID.fromString(principal.getName())); }

    @DeleteMapping("/auth/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        var handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(contexts);
        tokens.saveToken(null, request, response);
        handler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        // SessionRepositoryFilter expires the same configured cookie when the session is invalidated.
    }

    record Registration(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(min = 12, max = 128) String password,
                        @NotBlank @Size(min = 2, max = 100) String displayName) {
        Registration { email = Account.normalizeEmail(email); displayName = displayName == null ? null : displayName.strip(); }
        @Override public String toString() { return "Registration[redacted]"; }
    }

    record Login(@NotBlank @Email @Size(max = 254) String email,
                 @NotBlank @Size(min = 1, max = 128) String password) {
        Login { email = Account.normalizeEmail(email); }
        @Override public String toString() { return "Login[redacted]"; }
    }
}
