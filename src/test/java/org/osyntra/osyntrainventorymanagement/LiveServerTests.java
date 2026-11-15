package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.identity.Totp;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import tools.jackson.databind.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveServerTests {
    private static final String EMAIL = "bootstrap-test@example.com", PASSWORD = UUID.randomUUID().toString();
    @Value("${local.server.port}")
    int port;
    @Autowired
    ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("OSYNTRA_OPERATOR_EMAIL", () -> EMAIL);
        registry.add("OSYNTRA_OPERATOR_PASSWORD", () -> PASSWORD);
        registry.add("osyntra.bootstrap.enabled", () -> "true");
    }

    @Test
    void bootsFreshSqliteVerifiesBootstrapEmailAndEnforcesOperatorMfaOnRealHttp() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var login = post(client, "/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD), null, 200);
            assertEquals("VERIFY_EMAIL", login.get("purpose").asString());
            String token = login.get("accessToken").asString();
            var preview = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/mail-preview")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, preview.statusCode());
            String body = mapper.readTree(preview.body()).get(0).get("body").asString();
            var matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
            assertTrue(matcher.find());
            post(client, "/api/v1/auth/email-verification/confirm", Map.of("token", matcher.group(1)), null, 200);
            login = post(client, "/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD), null, 200);
            assertEquals("MFA_ENROLL", login.get("purpose").asString());
            token = login.get("accessToken").asString();
            var setup = post(client, "/api/v1/auth/mfa/setup", Map.of(), token, 200);
            var full = post(client, "/api/v1/auth/mfa/confirm", Map.of("code", Totp.code(setup.get("secret").asString(), System.currentTimeMillis() / 30000, 6)), token, 200);
            var me = client.send(HttpRequest.newBuilder(uri("/api/auth/me")).header("Authorization", "Bearer " + full.get("accessToken").asString()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            assertEquals("SUPER_ADMIN", mapper.readTree(me.body()).get("platformRole").asString());
            var health = client.send(HttpRequest.newBuilder(uri("/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            var docs = client.send(HttpRequest.newBuilder(uri("/v3/api-docs")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, docs.statusCode());
            assertTrue(docs.body().contains("bearerAuth"));
            var ui = client.send(HttpRequest.newBuilder(uri("/swagger-ui/index.html")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ui.statusCode());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode post(HttpClient client, String path, Object body, String token, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode());
        return mapper.readTree(response.body());
    }
}
