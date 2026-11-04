package com.agora.identity;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "agora.email.delivery-enabled=false", "agora.email.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "agora.grpc.enabled=false", "agora.auth.rate-limit.attempts=1000" })
class AdminAccountApiTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void reset() { jdbc.execute("TRUNCATE identity.accounts, identity.spring_session CASCADE"); }

    @Test void onlyCurrentAdminCanReadAndRoleChangesApplyToExistingSession() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(401);
        UUID actor = browser.loginBuyer();
        assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(403);
        assertThat(browser.get("/admin/accounts/not-a-uuid").statusCode()).isEqualTo(403);
        grant(actor);
        var response = browser.get("/admin/accounts");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(tree(response).path("items").size()).isEqualTo(1);
        assertThat(tree(response).path("items").get(0).path("roles").size()).isEqualTo(2);
        jdbc.update("DELETE FROM identity.account_roles WHERE account_id = ? AND role = 'ADMIN'", actor);
        assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(403);
    }

    @Test void pagesAreStableAndSearchIsLiteralWithExplicitTypeStatusAndRoleFilters() throws Exception {
        Browser browser = new Browser(); grant(browser.loginBuyer());
        for (int i = 0; i < 22; i++) seed("Farm " + i, i % 2 == 0 ? "BUYER" : "SELLER", "ACTIVE");
        UUID literal = seed("Farm_100% Fish", "SELLER", "SUSPENDED");
        seed("FarmX100x Fish", "SELLER", "ACTIVE");
        var first = browser.get("/admin/accounts");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var page = tree(first);
        assertThat(page.path("items").size()).isEqualTo(20);
        String cursor = page.path("nextCursor").asString();
        assertThat(cursor).isNotBlank();
        var ids = new HashSet<String>();
        for (var item : page.path("items")) assertThat(ids.add(item.path("id").asString())).isTrue();
        var next = tree(browser.get("/admin/accounts?cursor=" + cursor));
        assertThat(next.path("items").size()).isEqualTo(5);
        for (var item : next.path("items")) assertThat(ids.add(item.path("id").asString())).isTrue();
        assertThat(ids).hasSize(25);
        assertThat(next.path("nextCursor").isNull()).isTrue();
        var filtered = tree(browser.get("/admin/accounts?q=100%25&status=SUSPENDED&accountType=SELLER"));
        assertThat(filtered.path("items").size()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("id").asString()).isEqualTo(literal.toString());
        assertThat(tree(browser.get("/admin/accounts?role=ADMIN")).path("items").size()).isEqualTo(1);
        assertThat(browser.get("/admin/accounts?accountType=SELLER&cursor=" + cursor).statusCode()).isEqualTo(422);
        assertThat(first.body()).doesNotContain("password", "$argon2", "AGORA_", "sessionId");
    }

    @Test void detailIncludesUnclassifiedLegacyWithoutLeakingCredentialsAndValidatesInputs() throws Exception {
        Browser browser = new Browser(); grant(browser.loginBuyer());
        UUID legacy = seed("Legacy account", null, "ACTIVE");
        var response = browser.get("/admin/accounts/" + legacy);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var item = tree(response);
        assertThat(item.path("accountType").isNull()).isTrue();
        assertThat(item.path("version").asLong()).isZero();
        assertThat(item.has("updatedAt")).isTrue();
        assertThat(response.body()).doesNotContain("synthetic-existing-hash", "password");
        assertThat(browser.get("/admin/accounts/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        for (String invalid : List.of("/admin/accounts/not-a-uuid", "/admin/accounts?status=DELETED",
                "/admin/accounts?role=SELLER", "/admin/accounts?accountType=ADMIN", "/admin/accounts?q=" + "a".repeat(101),
                "/admin/accounts?q=a&q=b", "/admin/accounts?unknown=1", "/admin/accounts?cursor=not-a-cursor")) {
            assertThat(browser.get(invalid).statusCode()).as(invalid).isEqualTo(422);
        }
    }

    @Test void unsafeStoredVersionFailsClosedInsteadOfRoundingForJavascript() throws Exception {
        Browser browser = new Browser(); grant(browser.loginBuyer());
        UUID target = seed("Unsafe version", "SELLER", "ACTIVE");
        jdbc.update("UPDATE identity.accounts SET version = 9007199254740992 WHERE id = ?", target);
        var response = browser.get("/admin/accounts/" + target);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("password_hash", "SELECT", "9007199254740992");
    }

    @Test void unavailableIdentityStorageIs503AndRecoversWithoutLeakingSql() throws Exception {
        Browser browser = new Browser(); grant(browser.loginBuyer());
        jdbc.execute("ALTER TABLE identity.accounts RENAME TO accounts_unavailable");
        try {
            var response = browser.get("/admin/accounts");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("SELECT", "password_hash", "accounts_unavailable");
        } finally { jdbc.execute("ALTER TABLE identity.accounts_unavailable RENAME TO accounts"); }
        assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(200);
    }

    @Test void filteredCursorKeepsMicrosecondsAndBindsEveryFilter() throws Exception {
        Browser browser = new Browser(); grant(browser.loginBuyer());
        for (int i = 0; i < 21; i++) {
            UUID id = seed("Delta Store " + i, "SELLER", "SUSPENDED");
            jdbc.update("UPDATE identity.accounts SET created_at = '2026-02-01T00:00:00.123456Z' WHERE id = ?", id);
        }
        String filter = "q=Delta&status=SUSPENDED&role=USER&accountType=SELLER";
        var first = tree(browser.get("/admin/accounts?" + filter));
        assertThat(first.path("items").size()).isEqualTo(20);
        String cursor = first.path("nextCursor").asString();
        var second = tree(browser.get("/admin/accounts?" + filter + "&cursor=" + cursor));
        assertThat(second.path("items").size()).isEqualTo(1);
        var ids = new HashSet<String>();
        for (var item : first.path("items")) assertThat(ids.add(item.path("id").asString())).isTrue();
        assertThat(ids.add(second.path("items").get(0).path("id").asString())).isTrue();
        for (String changed : List.of(filter.replace("q=Delta", "q=Other"), filter.replace("SUSPENDED", "ACTIVE"),
                filter.replace("role=USER", "role=ADMIN"), filter.replace("SELLER", "BUYER")))
            assertThat(browser.get("/admin/accounts?" + changed + "&cursor=" + cursor).statusCode()).isEqualTo(422);
    }

    UUID seed(String name, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.accounts(id,email,password_hash,display_name,account_type,status,created_at,updated_at) "
                + "VALUES (?, ?, 'synthetic-existing-hash', ?, ?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id, "seed-" + id + "@example.test", name, type, status);
        jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'USER')", id);
        return id;
    }
    void grant(UUID id) { jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'ADMIN')", id); }
    JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, null); }
        HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json");
            if (token != null) b.header("X-CSRF-TOKEN", token);
            return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception {
            var response = request("POST", path, body, tree(get("/auth/csrf")).path("token").asString());
            if (response.statusCode()==201 && path.equals("/auth/registrations"))
                jdbc.update("UPDATE identity.accounts SET email_verified_at=clock_timestamp() WHERE id=?",UUID.fromString(tree(response).path("id").asString()));
            return response;
        }
        UUID loginBuyer() throws Exception {
            String email = "admin-fixture-" + UUID.randomUUID() + "@example.test";
            assertThat(post("/auth/registrations", "{\"email\":\"" + email + "\",\"displayName\":\"Operator\",\"password\":\"synthetic-long-password\"}").statusCode()).isEqualTo(201);
            var response = post("/auth/sessions", "{\"email\":\"" + email + "\",\"password\":\"synthetic-long-password\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            return UUID.fromString(tree(response).path("id").asString());
        }
    }
}
