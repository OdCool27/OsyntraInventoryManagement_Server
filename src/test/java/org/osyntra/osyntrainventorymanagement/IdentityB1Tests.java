package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.*;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(IdentityB1Tests.TimeConfig.class)
class IdentityB1Tests extends MvpTestSupport {
    static class MutableClock extends Clock {
        private final AtomicLong value = new AtomicLong(System.currentTimeMillis());

        void advance(long millis) {
            value.addAndGet(millis);
        }

        void reset() {
            value.set(System.currentTimeMillis());
        }

        @Override
        public long millis() {
            return value.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock b1Clock() {
            return new MutableClock();
        }
    }

    @Autowired
    MutableClock clock;
    @Autowired
    EmailDispatcher dispatcher;
    @Autowired
    IdentityService identity;
    @Autowired
    SecretProtection secrets;
    @Autowired
    PlatformTransactionManager transactions;
    @MockitoBean
    EmailTransport transport;
    private final Map<String, String> seeds = new HashMap<>();
    private final Map<String, List<String>> recoveryCodes = new HashMap<>();

    @BeforeEach
    void resetClock() {
        clock.reset();
        seeds.clear();
        recoveryCodes.clear();
    }

    JsonNode request(String path, Object body, String token, int expected) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return json(mvc.perform(builder).andExpect(status().is(expected)).andReturn());
    }

    JsonNode read(String path, String token, int expected) throws Exception {
        var builder = get(path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return json(mvc.perform(builder).andExpect(status().is(expected)).andReturn());
    }

    JsonNode edit(String path, Object body, String token, int expected) throws Exception {
        return json(mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn());
    }

    JsonNode login(String email, String password) throws Exception {
        return request("/api/v1/auth/login", Map.of("email", email, "password", password), null, 200);
    }

    String full(String role) throws Exception {
        return fullEmail(role.equals("OTHER") ? "other@test.local" : role.toLowerCase(Locale.ROOT) + "@test.local");
    }

    String fullEmail(String email) throws Exception {
        var login = login(email, PASSWORD);
        String token = login.get("accessToken").asString();
        if (login.get("purpose").asString().equals("MFA_ENROLL")) {
            var setup = request("/api/v1/auth/mfa/setup", Map.of(), token, 200);
            String seed = setup.get("secret").asString();
            seeds.put(email, seed);
            var confirmed = request("/api/v1/auth/mfa/confirm", Map.of("code", Totp.code(seed, clock.millis() / 30000, 6)), token, 200);
            List<String> codes = new ArrayList<>();
            for (var code : confirmed.get("recoveryCodes")) codes.add(code.asString());
            recoveryCodes.put(email, codes);
            return confirmed.get("accessToken").asString();
        }
        if (login.get("purpose").asString().equals("MFA_CHALLENGE")) {
            clock.advance(30000);
            return request("/api/v1/auth/mfa/verify", Map.of("code", Totp.code(seeds.get(email), clock.millis() / 30000, 6)), token, 200).get("accessToken").asString();
        }
        assertEquals("FULL", login.get("purpose").asString());
        return token;
    }

    String previewToken(String viewer, String kind) throws Exception {
        for (var message : read("/api/v1/auth/mail-preview", viewer, 200))
            if (message.get("kind").asString().equals(kind)) {
                var matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(message.get("body").asString());
                assertTrue(matcher.find());
                return matcher.group(1);
            }
        throw new AssertionError("Expected preview message " + kind);
    }

    int membership(String email, int business) {
        return (int) db.number("SELECT m.id FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.email=? AND m.business_id=?", email, business);
    }

    Map<String, Object> invite(String email, String role, boolean all, List<Integer> branches) {
        return Map.of("email", email, "role", role, "allBranches", all, "branchIds", branches);
    }

    Map<String, Object> member(String role, String status, boolean all, List<Integer> branches) {
        return Map.of("role", role, "status", status, "allBranches", all, "branchIds", branches, "jobTitle", role);
    }

    @Test
    void registrationVerificationAndOwnerCreationAreGated() throws Exception {
        var registration = Map.of("firstName", "Global", "lastName", "Identity", "email", "new-global@test.local", "password", PASSWORD);
        assertEquals(202, mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(registration))).andReturn().getResponse().getStatus());
        var pending = login("new-global@test.local", PASSWORD);
        assertEquals("VERIFY_EMAIL", pending.get("purpose").asString());
        String token = pending.get("accessToken").asString();
        request("/api/v1/businesses", Map.of("name", "Blocked", "locationName", "Main"), token, 403);
        String verification = previewToken(token, "VERIFY_EMAIL");
        assertEquals(0, db.number("SELECT count(*) FROM identity_tokens WHERE token_hash=?", verification));
        assertFalse(db.one("SELECT body_encrypted FROM email_outbox WHERE kind='VERIFY_EMAIL'").get("body_encrypted").toString().contains(verification));
        request("/api/v1/auth/email-verification/confirm", Map.of("token", verification), null, 200);
        request("/api/v1/auth/email-verification/confirm", Map.of("token", verification), null, 400);
        read("/api/v1/auth/me", token, 401);
        String verified = login("new-global@test.local", PASSWORD).get("accessToken").asString();
        var created = request("/api/v1/businesses", Map.of("name", "Global business", "locationName", "Main"), verified, 201);
        read("/api/v1/auth/me", verified, 401);
        String owner = fullEmail("new-global@test.local");
        assertEquals(created.get("businessId").asInt(), read("/api/business", owner, 200).get("id").asInt());
        assertEquals(1, db.number("SELECT count(*) FROM users WHERE email='new-global@test.local'"));
    }

    @Test
    void existingIdentityJoinsSecondBusinessAndSwitchesWithDatabaseRoles() throws Exception {
        String owner = full("OWNER");
        String invited = full("OTHER");
        int originalUser = employees.findByEmail("other@test.local").orElseThrow().getId();
        request("/api/v1/invitations", invite("other@test.local", "CASHIER", false, List.of(1)), owner, 201);
        String token = previewToken(invited, "INVITATION");
        request("/api/v1/invitations/accept", Map.of("token", token), null, 401);
        var accepted = request("/api/v1/invitations/accept", Map.of("token", token, "password", "This-must-not-replace-the-old-password"), invited, 200);
        request("/api/v1/invitations/accept", Map.of("token", token), invited, 401);
        String loggedIn = full("OTHER");
        request("/api/v1/invitations/accept", Map.of("token", token), loggedIn, 400);
        assertEquals(2, read("/api/v1/memberships", loggedIn, 200).size());
        assertEquals(1, db.number("SELECT count(*) FROM users WHERE email='other@test.local'"));
        assertEquals(originalUser, db.number("SELECT user_id FROM memberships WHERE id=?", accepted.get("membershipId").asInt()));
        var selected = request("/api/v1/auth/business", Map.of("membershipId", accepted.get("membershipId").asInt()), loggedIn, 200);
        String selectedToken = selected.get("accessToken").asString();
        read("/api/v1/auth/me", loggedIn, 401);
        assertEquals("CASHIER", read("/api/v1/auth/me", selectedToken, 200).get("role").asString());
        request("/api/products", productBody("ROLE-SPOOF"), selectedToken, 403);
        request("/api/v1/auth/business", Map.of("membershipId", membership("owner@test.local", 1)), selectedToken, 404);
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + selectedToken).header("X-Business-Id", otherBusiness)).andExpect(status().isOk());
    }

    @Test
    void newInviteeAcceptsOnceAndCannotEscalateStoredRole() throws Exception {
        String owner = full("OWNER");
        var issued = request("/api/v1/invitations", invite("invitee@test.local", "INVENTORY_CLERK", false, List.of(1)), owner, 201);
        assertFalse(issued.has("token_hash"));
        String token = previewToken(owner, "INVITATION");
        var body = Map.of("token", token, "firstName", "Invited", "lastName", "Worker", "password", PASSWORD);
        var accepted = request("/api/v1/invitations/accept", body, null, 200);
        request("/api/v1/invitations/accept", body, null, 400);
        String member = fullEmail("invitee@test.local");
        assertEquals("INVENTORY_CLERK", read("/api/v1/auth/me", member, 200).get("role").asString());
        assertEquals(1, db.number("SELECT count(*) FROM memberships WHERE id=?", accepted.get("membershipId").asInt()));
        request("/api/v1/invitations", invite("bad@test.local", "OWNER", true, List.of()), member, 403);
    }

    @Test
    void invitationExpiryRevocationAndCrossTenantIdsFailClosed() throws Exception {
        String owner = full("OWNER"), other = full("OTHER");
        var first = request("/api/v1/invitations", invite("expired@test.local", "CASHIER", true, List.of()), owner, 201);
        String expired = previewToken(owner, "INVITATION");
        clock.advance(172800001);
        request("/api/v1/invitations/accept", Map.of("token", expired, "firstName", "A", "lastName", "B", "password", PASSWORD), null, 400);
        owner = full("OWNER");
        other = full("OTHER");
        var second = request("/api/v1/invitations", invite("revoked@test.local", "CASHIER", true, List.of()), owner, 201);
        String revoked = previewToken(owner, "INVITATION");
        mvc.perform(delete("/api/v1/invitations/" + second.get("id").asString()).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/invitations/" + second.get("id").asString()).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        request("/api/v1/invitations/accept", Map.of("token", revoked, "firstName", "A", "lastName", "B", "password", PASSWORD), null, 400);
        assertEquals(0, db.number("SELECT count(*) FROM users WHERE email IN('expired@test.local','revoked@test.local')"));
    }

    @Test
    void roleBoundariesAndLastOwnerAreEnforcedOnActualSessions() throws Exception {
        employee("administrator@test.local", EmployeeRole.ADMINISTRATOR, 1, false);
        String owner = full("OWNER"), admin = fullEmail("administrator@test.local"), manager = full("MANAGER");
        request("/api/v1/invitations", invite("admin-invite@test.local", "MANAGER", true, List.of()), admin, 201);
        request("/api/v1/invitations", invite("admin-owner@test.local", "OWNER", true, List.of()), admin, 403);
        request("/api/v1/invitations", invite("manager-admin@test.local", "ADMINISTRATOR", true, List.of()), manager, 403);
        int ownerMembership = membership("owner@test.local", 1);
        edit("/api/v1/business/memberships/" + ownerMembership, member("CASHIER", "DEACTIVATED", true, List.of()), owner, 409);
        edit("/api/v1/business/memberships/" + ownerMembership, member("OWNER", "ACTIVE", true, List.of()), admin, 403);
        edit("/api/v1/business/memberships/" + membership("other@test.local", otherBusiness), member("CASHIER", "ACTIVE", true, List.of()), owner, 404);
    }

    @Test
    void grantsAndDeactivationRevokeSessionsAndPendingInvitationsWithoutDisablingOtherBusinesses() throws Exception {
        String owner = full("OWNER"), manager = full("MANAGER");
        var invite = request("/api/v1/invitations", invite("pending@test.local", "CASHIER", true, List.of()), manager, 201);
        String token = previewToken(manager, "INVITATION");
        edit("/api/v1/business/memberships/" + membership("manager@test.local", 1), member("MANAGER", "DEACTIVATED", true, List.of()), owner, 200);
        read("/api/products", manager, 401);
        assertEquals("REVOKED", db.one("SELECT status FROM invitations WHERE id=?", invite.get("id").asString()).get("status"));
        request("/api/v1/invitations/accept", Map.of("token", token, "firstName", "A", "lastName", "B", "password", PASSWORD), null, 400);
        assertEquals("ACTIVE", db.one("SELECT status FROM users WHERE email='manager@test.local'").get("status"));
    }

    @Test
    void branchDenialCoversStockOrdersCountsImportsReportsAndAudit() throws Exception {
        String p = stocked("BRANCH-ITEM", 5);
        int second = send("/api/locations", Map.of("name", "Allowed branch"), "OWNER", 201).get("id").asInt();
        send("/api/inventory/" + p + "?locationId=" + second, Map.of("quantityOnHand", 2, "reason", "OPENING_COUNT", "requestId", "b1-opening"), "OWNER", 201);
        String order = order(p, 1);
        send("/api/orders/" + order + "/payment", payment(true, "b1-pay"), "CASHIER", 200);
        String count = send("/api/counts", Map.of(), "CLERK", 201).get("id").asString();
        product("B1-IMPORT", "OWNER");
        String preview = send("/api/imports/opening/preview", Map.of("csv", "sku,quantity,reorderLevel\nB1-IMPORT,2,1\n"), "OWNER", 200).get("id").asString();
        int user = employee("limited@test.local", EmployeeRole.MANAGER, 1, false);
        int membership = membership("limited@test.local", 1);
        db.update("UPDATE memberships SET all_branches=0 WHERE id=?", membership);
        db.update("INSERT INTO membership_branches(membership_id,business_id,location_id) VALUES(?,1,?)", membership, second);
        String token = fullEmail("limited@test.local");
        assertEquals(2, read("/api/inventory/" + p, token, 200).get("quantity_on_hand").asInt());
        read("/api/inventory/" + p + "?locationId=1", token, 404);
        read("/api/inventory/movements?locationId=1", token, 404);
        read("/api/orders/" + order, token, 404);
        assertEquals(0, read("/api/orders", token, 200).size());
        request("/api/orders", Map.of("locationId", 1, "lines", List.of(Map.of("productId", p, "quantity", 1)), "requestId", "denied"), token, 404);
        read("/api/counts/" + count, token, 404);
        assertEquals(0, read("/api/counts", token, 200).size());
        read("/api/imports/opening/" + preview, token, 404);
        request("/api/imports/opening/" + preview + "/commit", Map.of(), token, 404);
        assertEquals(0, read("/api/reports/sales", token, 200).get("gross_revenue_cents").asInt());
        assertEquals(2, read("/api/reports/summary", token, 200).get("total_units").asInt());
        assertEquals(1, read("/api/audit", token, 200).size());
        request("/api/v1/invitations", invite("outside@test.local", "CASHIER", false, List.of(1)), token, 404);
        request("/api/v1/invitations", invite("all@test.local", "CASHIER", true, List.of()), token, 403);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("INSERT INTO membership_branches(membership_id,business_id,location_id) VALUES(?,?,?)", membership, 1, otherLocation));
    }

    @Test
    void ownerAndPlatformMfaCannotBeBypassedByBasicOrPendingSessions() throws Exception {
        var pending = login("owner@test.local", PASSWORD);
        String token = pending.get("accessToken").asString();
        assertEquals("MFA_ENROLL", pending.get("purpose").asString());
        read("/api/products", token, 403);
        request("/api/v1/auth/business", Map.of("membershipId", membership("owner@test.local", 1)), token, 403);
        mvc.perform(get("/api/products").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("owner@test.local", PASSWORD))).andExpect(status().isUnauthorized());
        String platform = full("OPERATOR");
        read("/api/platform/businesses", platform, 200);
        read("/api/products", platform, 403);
        request("/api/v1/auth/business", Map.of("membershipId", membership("owner@test.local", 1)), platform, 403);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("INSERT INTO memberships(user_id,business_id,role,job_title,all_branches) VALUES(?,1,'OWNER','Forbidden',1)", employees.findByEmail("operator@test.local").orElseThrow().getId()));
        db.update("UPDATE platform_staff SET role='SUPPORT' WHERE user_id=(SELECT id FROM users WHERE email='operator@test.local')");
        read("/api/platform/businesses", platform, 403);
    }

    @Test
    void totpReplayRecoveryAndMandatoryMfaPolicyAreEnforced() throws Exception {
        String owner = full("OWNER");
        var login = login("owner@test.local", PASSWORD);
        String challenge = login.get("accessToken").asString();
        assertEquals("MFA_CHALLENGE", login.get("purpose").asString());
        String replay = Totp.code(seeds.get("owner@test.local"), clock.millis() / 30000, 6);
        request("/api/v1/auth/mfa/verify", Map.of("code", replay), challenge, 401);
        clock.advance(30000);
        String fresh = Totp.code(seeds.get("owner@test.local"), clock.millis() / 30000, 6);
        var verified = request("/api/v1/auth/mfa/verify", Map.of("code", fresh), challenge, 200);
        read("/api/v1/auth/me", challenge, 401);
        request("/api/v1/auth/mfa/disable", Map.of("password", PASSWORD, "code", fresh), verified.get("accessToken").asString(), 409);
        var next = login("owner@test.local", PASSWORD);
        String code = recoveryCodes.get("owner@test.local").getFirst();
        request("/api/v1/auth/mfa/verify", Map.of("recoveryCode", code), next.get("accessToken").asString(), 200);
        var third = login("owner@test.local", PASSWORD);
        request("/api/v1/auth/mfa/verify", Map.of("recoveryCode", code), third.get("accessToken").asString(), 401);
        String unused = recoveryCodes.get("owner@test.local").get(1);
        var reset = request("/api/v1/auth/mfa/recover", Map.of("password", PASSWORD, "recoveryCode", unused), third.get("accessToken").asString(), 200);
        assertEquals("MFA_ENROLL", reset.get("purpose").asString());
        read("/api/products", reset.get("accessToken").asString(), 403);
    }

    @Test
    void optionalMfaNeedsPasswordReauthenticationAndItsSeedIsEncrypted() throws Exception {
        String manager = full("MANAGER");
        request("/api/v1/auth/mfa/setup", Map.of(), manager, 401);
        var setup = request("/api/v1/auth/mfa/setup", Map.of("password", PASSWORD), manager, 200);
        String seed = setup.get("secret").asString();
        var user = db.one("SELECT mfa_pending_secret FROM users WHERE email='manager@test.local'");
        assertNotEquals(seed, user.get("mfa_pending_secret"));
        assertFalse(user.get("mfa_pending_secret").toString().contains(seed));
        var confirm = request("/api/v1/auth/mfa/confirm", Map.of("code", Totp.code(seed, clock.millis() / 30000, 6)), manager, 200);
        manager = confirm.get("accessToken").asString();
        clock.advance(30000);
        request("/api/v1/auth/mfa/disable", Map.of("password", PASSWORD, "code", Totp.code(seed, clock.millis() / 30000, 6)), manager, 200);
        assertEquals("FULL", login("manager@test.local", PASSWORD).get("purpose").asString());
    }

    @Test
    void passwordResetIsSingleUseRevokesSessionsAndPreservesMfa() throws Exception {
        String owner = full("OWNER");
        var known = request("/api/v1/auth/password-reset", Map.of("email", "owner@test.local"), null, 202);
        var unknown = request("/api/v1/auth/password-reset", Map.of("email", "absent@test.local"), null, 202);
        assertEquals(known, unknown);
        String token = previewToken(owner, "RESET_PASSWORD");
        String replacement = "New-test-password-456";
        request("/api/v1/auth/password-reset/confirm", Map.of("token", token, "password", replacement), null, 200);
        read("/api/v1/auth/me", owner, 401);
        request("/api/v1/auth/password-reset/confirm", Map.of("token", token, "password", PASSWORD), null, 400);
        request("/api/v1/auth/login", Map.of("email", "owner@test.local", "password", PASSWORD), null, 401);
        assertEquals("MFA_CHALLENGE", login("owner@test.local", replacement).get("purpose").asString());
    }

    @Test
    void resetAndVerificationTokensExpireAndRegistrationDoesNotEnumerate() throws Exception {
        var existing = request("/api/v1/auth/register", Map.of("firstName", "Ignored", "lastName", "Ignored", "email", "manager@test.local", "password", PASSWORD), null, 202);
        var created = request("/api/v1/auth/register", Map.of("firstName", "New", "lastName", "New", "email", "new-expiring@test.local", "password", PASSWORD), null, 202);
        assertEquals(existing, created);
        String user = login("new-expiring@test.local", PASSWORD).get("accessToken").asString();
        String token = previewToken(user, "VERIFY_EMAIL");
        clock.advance(86400001);
        request("/api/v1/auth/email-verification/confirm", Map.of("token", token), null, 400);
        String manager = full("MANAGER");
        request("/api/v1/auth/password-reset", Map.of("email", "manager@test.local"), null, 202);
        String reset = previewToken(manager, "RESET_PASSWORD");
        clock.advance(1800001);
        request("/api/v1/auth/password-reset/confirm", Map.of("token", reset, "password", PASSWORD), null, 400);
    }

    @Test
    void logoutExpiryAndSessionOwnershipAreEnforced() throws Exception {
        String first = full("MANAGER"), second = full("MANAGER"), other = full("OTHER");
        var sessions = read("/api/v1/auth/sessions", first, 200);
        assertEquals(2, sessions.size());
        assertFalse(sessions.get(0).has("token_hash"));
        String id = sessions.get(0).get("id").asString();
        mvc.perform(delete("/api/v1/auth/sessions/" + id).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        request("/api/v1/auth/logout", Map.of(), second, 204);
        read("/api/v1/auth/me", second, 401);
        request("/api/v1/auth/logout-all", Map.of(), first, 204);
        read("/api/v1/auth/me", first, 401);
        String expiring = full("MANAGER");
        clock.advance(1800001);
        read("/api/v1/auth/me", expiring, 401);
    }

    @Test
    void failedAuthenticationConsumesPersistentRateLimitAndIgnoresForwardedIpClaims() throws Exception {
        for (int i = 0; i < 20; i++)
            request("/api/v1/auth/login", Map.of("email", "manager@test.local", "password", "incorrect"), null, 401);
        mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", "203.0.113.100").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "manager@test.local", "password", PASSWORD))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"));
        clock.advance(900001);
        assertEquals("FULL", login("manager@test.local", PASSWORD).get("purpose").asString());
    }

    @Test
    void outboxIsTransactionalEncryptedRetriedAndSentOnlyAfterCommit() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            identity.register(new IdentityDtos.Register("Rollback", "User", "rollback@test.local", PASSWORD));
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOne());
            status.setRollbackOnly();
        });
        assertEquals(0, db.number("SELECT count(*) FROM email_outbox"));
        verifyNoInteractions(transport);
        request("/api/v1/auth/register", Map.of("firstName", "Mail", "lastName", "User", "email", "mail@test.local", "password", PASSWORD), null, 202);
        verifyNoInteractions(transport);
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("sensitive-provider-diagnostic");
        })
                .when(transport).send(anyString(), anyString(), anyString(), anyString());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("FAILED", db.one("SELECT status,last_error_code FROM email_outbox").get("status"));
        assertEquals("DELIVERY_FAILED", db.one("SELECT last_error_code FROM email_outbox").get("last_error_code"));
        clock.advance(60001);
        doNothing().when(transport).send(anyString(), anyString(), anyString(), anyString());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SENT", db.one("SELECT status FROM email_outbox").get("status"));
        assertFalse(dispatcher.dispatchOne());
    }

    @Test
    void localMailPreviewIsLoopbackAndIdentityScopedAndMasterKeyIsBoundToDatabase() throws Exception {
        String manager = full("MANAGER"), cashier = full("CASHIER");
        request("/api/v1/auth/password-reset", Map.of("email", "manager@test.local"), null, 202);
        assertEquals(1, read("/api/v1/auth/mail-preview", manager, 200).size());
        assertEquals(0, read("/api/v1/auth/mail-preview", cashier, 200).size());
        mvc.perform(get("/api/v1/auth/mail-preview").header("Authorization", "Bearer " + manager).with(r -> {
            r.setRemoteAddr("203.0.113.50");
            return r;
        })).andExpect(status().isNotFound());
        var row = db.one("SELECT id,body_encrypted FROM email_outbox");
        assertThrows(IllegalStateException.class, () -> secrets.decrypt("mail:other", row.get("body_encrypted").toString()));
        String wrong = Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalStateException.class, () -> new SecretProtection(db, wrong, "unused"));
        var missing = java.nio.file.Path.of("target", "missing-identity-key-" + UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> new SecretProtection(db, "", missing.toString()));
        assertFalse(java.nio.file.Files.exists(missing));
    }

    @Test
    void membershipAndBranchMetadataCannotBeSpoofedInRequestBodies() throws Exception {
        String cashier = full("CASHIER"), owner = full("OWNER");
        request("/api/v1/auth/business", Map.of("membershipId", membership("cashier@test.local", 1), "role", "OWNER", "businessId", otherBusiness), cashier, 400);
        request("/api/v1/invitations", invite("wrongbranch@test.local", "CASHIER", false, List.of(otherLocation)), owner, 404);
        request("/api/v1/invitations", invite("empty@test.local", "CASHIER", false, List.of()), owner, 400);
        request("/api/v1/invitations", invite("badowner@test.local", "OWNER", false, List.of(1)), owner, 400);
    }

    @Test
    void branchManagementProtectsPendingWorkAndKeepsHistoricalAccess() throws Exception {
        String owner = full("OWNER"), manager = full("MANAGER");
        int branch = request("/api/v1/branches", Map.of("name", "Secondary"), owner, 201).get("id").asInt();
        request("/api/v1/branches", Map.of("name", "Manager cannot create"), manager, 403);
        String count = request("/api/counts", Map.of("locationId", branch), owner, 201).get("id").asString();
        edit("/api/v1/branches/" + branch, Map.of("name", "Blocked disable", "active", false), owner, 409);
        String emptyBranch = request("/api/v1/branches", Map.of("name", "Inactive"), owner, 201).get("id").asString();
        edit("/api/v1/branches/" + emptyBranch, Map.of("name", "Inactive", "active", false), owner, 200);
        edit("/api/v1/branches/" + emptyBranch, Map.of("name", "Renamed inactive", "active", false), owner, 200);
        request("/api/orders/cart", Map.of("locationId", Integer.parseInt(emptyBranch), "lines", List.of(Map.of("productId", UUID.randomUUID().toString(), "quantity", 1))), owner, 404);
        read("/api/counts/" + count, owner, 200);
    }

    @Test
    void memberDeactivationLeavesOtherBusinessMembershipUsableAfterReauthentication() throws Exception {
        String owner = full("OWNER"), recipient = full("OTHER");
        request("/api/v1/invitations", invite("other@test.local", "CASHIER", true, List.of()), owner, 201);
        var accepted = request("/api/v1/invitations/accept", Map.of("token", previewToken(recipient, "INVITATION")), recipient, 200);
        recipient = full("OTHER");
        edit("/api/v1/business/memberships/" + accepted.get("membershipId").asInt(), member("CASHIER", "DEACTIVATED", true, List.of()), owner, 200);
        read("/api/v1/auth/me", recipient, 401);
        String surviving = full("OTHER");
        assertEquals(otherBusiness, read("/api/business", surviving, 200).get("id").asInt());
        assertEquals("ACTIVE", db.one("SELECT status FROM users WHERE email='other@test.local'").get("status"));
    }

    @Test
    void expiredOutboxLinksAreCancelledWithoutExternalDelivery() throws Exception {
        request("/api/v1/auth/password-reset", Map.of("email", "manager@test.local"), null, 202);
        clock.advance(1800001);
        assertFalse(dispatcher.dispatchOne());
        verifyNoInteractions(transport);
        assertEquals("CANCELLED", db.one("SELECT status FROM email_outbox").get("status"));
    }

    @Test
    void auditOfPrivilegeChangesIsTenantScopedAndReadableByAuthorizedOwners() throws Exception {
        String owner = full("OWNER"), other = full("OTHER"), manager = full("MANAGER");
        edit("/api/v1/business/memberships/" + membership("cashier@test.local", 1), member("INVENTORY_CLERK", "ACTIVE", true, List.of()), owner, 200);
        var events = read("/api/v1/business/security-audit", owner, 200);
        assertTrue(events.toString().contains("MEMBERSHIP_UPDATED"));
        for (var event : events) assertEquals(1, event.get("business_id").asInt());
        assertEquals(0, read("/api/v1/business/security-audit", other, 200).size());
        read("/api/v1/business/security-audit", manager, 403);
        assertTrue(read("/api/v1/auth/security-events", owner, 200).size() > 0);
    }

    @Test
    void concurrentAcceptanceConsumesAnInvitationExactlyOnce() throws Exception {
        String owner = full("OWNER");
        request("/api/v1/invitations", invite("concurrent@test.local", "CASHIER", true, List.of()), owner, 201);
        String token = previewToken(owner, "INVITATION");
        String body = mapper.writeValueAsString(Map.of("token", token, "firstName", "Concurrent", "lastName", "Invitee", "password", PASSWORD));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> accept = () -> {
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(accept);
            var second = executor.submit(accept);
            start.countDown();
            var statuses = new HashSet<>(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(Set.of(200, 400), statuses);
        }
        assertEquals(1, db.number("SELECT count(*) FROM users WHERE email='concurrent@test.local'"));
        assertEquals(1, db.number("SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.email='concurrent@test.local'"));
    }
}
