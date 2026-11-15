package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.http.MediaType;

@Import(TaxB2Tests.TimeConfig.class)
class TaxB2Tests extends MvpTestSupport {
    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        TaxClock b2Clock() {
            return new TaxClock();
        }
    }

    static class TaxClock extends Clock {
        Instant now = Instant.parse("2030-01-01T12:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @Autowired
    TaxClock clock;

    @BeforeEach
    void resetTime() {
        clock.now = Instant.parse("2030-01-01T12:00:00Z");
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    String before() {
        return clock.instant().minusSeconds(60).toString();
    }

    JsonNode rate(String code, String classification, String percent, String at, boolean verified) throws Exception {
        return send("/api/v1/tax/profiles/" + code + "/versions",
                Map.of("effectiveFrom", at, "label", "Reviewed fixture " + code, "classification", classification, "ratePercent", percent,
                        "illustrative", true, "verified", verified, "verificationNote", verified ? "Test accountant review reference" : "Pending review", "requestId", key()), "OWNER", 201);
    }

    JsonNode policy(boolean registered, String mode, String at, boolean verified) throws Exception {
        return send("/api/v1/tax/policies", Map.of("effectiveFrom", at, "registrationStatus", registered ? "REGISTERED" : "NOT_REGISTERED",
                "registrationNumber", registered ? "Test-registration" : "", "priceMode", mode, "verified", verified, "verificationNote", verified ? "Owner reviewed fixture" : "Pending review", "requestId", key()), "OWNER", 201);
    }

    void assign(String product, String code, String at) throws Exception {
        send("/api/v1/products/" + product + "/tax", Map.of("code", code, "effectiveFrom", at, "requestId", key()), "OWNER", 201);
    }

    String priced(String sku, String price, int quantity) throws Exception {
        var body = productBody(sku);
        body.put("unitPrice", price);
        String id = send("/api/products", body, "OWNER", 201).get("itemId").asString();
        open(id, quantity, "OWNER", 201);
        return id;
    }

    JsonNode cart(String product, int qty) throws Exception {
        return send("/api/orders/cart", Map.of("lines", List.of(Map.of("productId", product, "quantity", qty))), "CASHIER", 200);
    }

    @Test
    void nonregisteredSellerDoesNotCollectTaxEvenForAssignedTaxableProduct() throws Exception {
        String p = stocked("NONREG", 10);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var preview = cart(p, 2);
        assertEquals(5100, preview.get("total_cents").asLong());
        assertEquals(0, preview.get("tax_cents").asLong());
        assertEquals("NON_REGISTERED", preview.get("lines").get(0).get("tax_classification").asString());
        assertEquals(0, preview.get("seller_tax_verified").asInt());
        assertEquals(0, reserved(p));
        var order = send("/api/orders", checkout(p, 2, key()), "CASHIER", 201);
        assertEquals(5100, order.get("net_cents").asLong());
        assertEquals(0, order.get("tax_cents").asLong());
        var templates = getJson("/api/v1/tax/templates", "OWNER", 200);
        assertEquals(3, templates.size());
        for (var template : templates) {
            assertEquals(1, template.get("illustrative").asInt());
            assertEquals(1, template.get("requires_verification").asInt());
        }
    }

    @Test
    void exclusiveTaxRoundsOncePerExtendedLineAndPreviewMatchesCheckoutPaymentReceipt() throws Exception {
        String p = stocked("TAXED", 10);
        policy(true, "EXCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var preview = cart(p, 3);
        assertEquals(7650, preview.get("net_cents").asLong());
        assertEquals(1148, preview.get("tax_cents").asLong());
        assertEquals(8798, preview.get("total_cents").asLong());
        var order = send("/api/orders", checkout(p, 3, key()), "CASHIER", 201);
        String id = order.get("id").asString();
        assertEquals(preview.get("total_cents"), order.get("total_cents"));
        var paid = send("/api/orders/" + id + "/payment", payment(true, key()), "CASHIER", 200);
        assertEquals(8798, paid.get("payments").get(0).get("amount_cents").asLong());
        assertEquals(1, paid.get("lines").get(0).get("tax_illustrative").asInt());
        String receipt = mvc.perform(get("/api/orders/" + id + "/receipt").with(as("CASHIER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(receipt.contains("Sales tax: JMD 11.48"));
        assertTrue(receipt.contains("Total: JMD 87.98"));
        var sales = getJson("/api/reports/sales", "OWNER", 200);
        assertEquals(8798, sales.get("gross_revenue_cents").asLong());
        assertEquals(1148, sales.get("sales_tax_cents").asLong());
        assertEquals(7650, sales.get("byProduct").get(0).get("sales_net_before_tax_cents").asLong());
        assertEquals(4650, sales.get("byProduct").get(0).get("gross_profit_estimate_cents").asLong());
    }

    @Test
    void inclusiveTaxExtractsTaxWithoutAddingToDisplayedPrice() throws Exception {
        String p = priced("INCLUSIVE", "115.00", 5);
        policy(true, "INCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var order = send("/api/orders", checkout(p, 1, key()), "CASHIER", 201);
        assertEquals(11500, order.get("total_cents").asLong());
        assertEquals(10000, order.get("net_cents").asLong());
        assertEquals(1500, order.get("tax_cents").asLong());
    }

    @Test
    void zeroRatedAndExemptRemainDifferentClassifications() throws Exception {
        String zero = priced("ZERO", "100.00", 5), exempt = priced("EXEMPT", "100.00", 5);
        policy(true, "EXCLUSIVE", before(), true);
        rate("ZERO", "ZERO_RATED", "0", before(), true);
        rate("EXEMPT", "EXEMPT", "0", before(), true);
        assign(zero, "ZERO", before());
        assign(exempt, "EXEMPT", before());
        var order = send("/api/orders", Map.of("requestId", key(), "lines", List.of(Map.of("productId", zero, "quantity", 1), Map.of("productId", exempt, "quantity", 1))), "CASHIER", 201);
        assertEquals(0, order.get("tax_cents").asLong());
        assertEquals(20000, order.get("total_cents").asLong());
        Set<String> classes = new HashSet<>();
        for (var line : order.get("lines")) classes.add(line.get("tax_classification").asString());
        assertEquals(Set.of("ZERO_RATED", "EXEMPT"), classes);
    }

    @Test
    void registeredSellerCannotCheckoutWithoutReviewedPolicyAndEffectiveProductRule() throws Exception {
        String p = stocked("BLOCKED", 10);
        policy(true, "EXCLUSIVE", before(), false);
        send("/api/orders", checkout(p, 1, key()), "CASHIER", 409);
        clock.now = clock.now.plusSeconds(1);
        policy(true, "EXCLUSIVE", clock.instant().toString(), true);
        send("/api/orders", checkout(p, 1, key()), "CASHIER", 409);
        rate("UNREVIEWED", "TAXABLE", "15", before(), false);
        assign(p, "UNREVIEWED", before());
        send("/api/orders", checkout(p, 1, key()), "CASHIER", 409);
        assertEquals(0, reserved(p));
        assertEquals(0, db.number("SELECT count(*) FROM orders"));
        assertEquals(10, quantity(p));
    }

    @Test
    void futureRateChangesDoNotRepriceExistingOrdersOrTheirRetriesAndRefunds() throws Exception {
        String p = priced("HISTORY", "100.00", 10);
        policy(true, "EXCLUSIVE", before(), true);
        var oldRate = rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        String request = key();
        var old = send("/api/orders", checkout(p, 1, request), "CASHIER", 201);
        String id = old.get("id").asString();
        var newRate = rate("STANDARD", "TAXABLE", "20", "2030-01-02T00:00:00Z", true);
        assertEquals(11500, cart(p, 1).get("total_cents").asLong());
        clock.now = Instant.parse("2030-01-03T00:00:00Z");
        assertEquals(12000, cart(p, 1).get("total_cents").asLong());
        var replay = send("/api/orders", checkout(p, 1, request), "CASHIER", 201);
        assertEquals(11500, replay.get("total_cents").asLong());
        assertEquals(oldRate.get("id"), replay.get("lines").get(0).get("tax_rate_version_id"));
        var next = send("/api/orders", checkout(p, 1, key()), "CASHIER", 201);
        assertEquals(newRate.get("id"), next.get("lines").get(0).get("tax_rate_version_id"));
        var paid = send("/api/orders/" + id + "/payment", payment(true, key()), "CASHIER", 200);
        assertEquals(11500, paid.get("total_cents").asLong());
        var refund = send("/api/orders/" + id + "/refund", action(key()), "OWNER", 200);
        assertEquals(11500, refund.get("refunds").get(0).get("amount_cents").asLong());
    }

    @Test
    void futureRegistrationAndClassificationChangesSelectOnlyEffectiveVersions() throws Exception {
        String p = priced("DATES", "100.00", 10);
        policy(true, "EXCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        rate("EXEMPT", "EXEMPT", "0", before(), true);
        assign(p, "EXEMPT", "2030-01-02T00:00:00Z");
        policy(false, "EXCLUSIVE", "2030-01-03T00:00:00Z", true);
        assertEquals(1500, cart(p, 1).get("tax_cents").asLong());
        clock.now = Instant.parse("2030-01-02T12:00:00Z");
        assertEquals("EXEMPT", cart(p, 1).get("lines").get(0).get("tax_classification").asString());
        clock.now = Instant.parse("2030-01-03T12:00:00Z");
        assertEquals("NON_REGISTERED", cart(p, 1).get("lines").get(0).get("tax_classification").asString());
    }

    @Test
    void branchOverridesRequireBusinessPermissionAndCanReturnToInheritance() throws Exception {
        String p = priced("BRANCH", "115.00", 10);
        policy(true, "EXCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var branch = new LinkedHashMap<String, Object>();
        branch.put("revision", 0);
        branch.put("receiptName", "Branch receipt");
        branch.put("priceMode", "INCLUSIVE");
        putJson("/api/v1/branches/1/settings", branch, "OWNER", 409);
        var preferences = Map.of("revision", 0, "receiptName", "Business receipt", "receiptFooter", "Thanks", "lowStockNotifications", true, "allowBranchPriceMode", true);
        putJson("/api/v1/settings", preferences, "OWNER", 200);
        putJson("/api/v1/settings", preferences, "OWNER", 409);
        putJson("/api/v1/branches/1/settings", branch, "OWNER", 200);
        var order = send("/api/orders", checkout(p, 1, key()), "CASHIER", 201);
        assertEquals(11500, order.get("total_cents").asLong());
        assertEquals("Branch receipt", order.get("receipt_name").asString());
        putJson("/api/v1/branches/1/settings", Map.of("revision", 1), "OWNER", 200);
        assertEquals(13225, cart(p, 1).get("total_cents").asLong());
        assertEquals("EXCLUSIVE", getJson("/api/v1/branches/1/settings", "OWNER", 200).get("effective").get("price_mode").asString());
        var historical = getJson("/api/orders/" + order.get("id").asString(), "OWNER", 200);
        assertEquals("Branch receipt", historical.get("receipt_name").asString());
        assertEquals("INCLUSIVE", historical.get("price_mode").asString());
    }

    @Test
    void ownerAttestationRequiredAndTenantOrBranchSpoofingIsDenied() throws Exception {
        String p = stocked("SECURE", 5);
        var body = Map.of("effectiveFrom", before(), "label", "Test", "classification", "TAXABLE", "ratePercent", "15", "illustrative", true, "verified", true, "verificationNote", "Test review", "requestId", key());
        send("/api/v1/tax/profiles/TEST/versions", body, "MANAGER", 403);
        send("/api/v1/tax/profiles/TEST/versions", body, "CASHIER", 403);
        getJson("/api/v1/products/" + p + "/tax", "OTHER", 404);
        putJson("/api/v1/branches/" + otherLocation + "/settings", Map.of("revision", 0), "OWNER", 404);
        var spoof = new LinkedHashMap<String, Object>(body);
        spoof.put("businessId", otherBusiness);
        send("/api/v1/tax/profiles/TEST/versions", spoof, "OWNER", 400);
        var totals = new LinkedHashMap<>(checkout(p, 1, key()));
        totals.put("tax_cents", 0);
        send("/api/orders", totals, "CASHIER", 400);
        int admin = employee("admin@test.local", org.osyntra.osyntrainventorymanagement.models.EmployeeRole.ADMINISTRATOR, 1, false);
        var login = send("/api/v1/auth/login", Map.of("email", "admin@test.local", "password", PASSWORD), "OWNER", 200);
        mvc.perform(post("/api/v1/tax/profiles/TEST/versions").header("Authorization", "Bearer " + login.get("accessToken").asString()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andExpect(status().isForbidden());
        db.update("UPDATE memberships SET all_branches=0 WHERE user_id=?", admin);
        db.update("INSERT INTO membership_branches SELECT id,business_id,1 FROM memberships WHERE user_id=?", admin);
        int hidden = org.osyntra.osyntrainventorymanagement.repositories.MvpStore.integer(db.one("INSERT INTO locations(business_id,name) VALUES(1,'Restricted sibling') RETURNING id"), "id");
        mvc.perform(get("/api/v1/branches/" + hidden + "/settings").header("Authorization", "Bearer " + login.get("accessToken").asString())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/branches/" + hidden + "/settings").header("Authorization", "Bearer " + login.get("accessToken").asString()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("revision", 0)))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/settings").header("Authorization", "Bearer " + login.get("accessToken").asString()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("revision", 0, "receiptName", "", "receiptFooter", "", "lowStockNotifications", false, "allowBranchPriceMode", false)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/branches/" + otherLocation + "/settings").header("Authorization", "Bearer " + login.get("accessToken").asString())).andExpect(status().isNotFound());
    }

    @Test
    void versionCreationRetriesAreIdempotentAndChangedPayloadConflicts() throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("effectiveFrom", before(), "registrationStatus", "NOT_REGISTERED", "registrationNumber", "", "priceMode", "EXCLUSIVE", "verified", true, "verificationNote", "Test reviewed", "requestId", key()));
        var first = send("/api/v1/tax/policies", body, "OWNER", 201);
        var repeat = send("/api/v1/tax/policies", body, "OWNER", 201);
        assertEquals(first.get("id"), repeat.get("id"));
        body.put("priceMode", "INCLUSIVE");
        send("/api/v1/tax/policies", body, "OWNER", 409);
        assertEquals(2, db.number("SELECT count(*) FROM tax_policy_versions WHERE business_id=1"));
    }

    @Test
    void onboardingResumesChecksActualStateAndDoesNotBlockDashboard() throws Exception {
        var steps = getJson("/api/v1/onboarding", "OWNER", 200);
        assertEquals(7, steps.size());
        putJson("/api/v1/onboarding/TAX_REVIEW", Map.of("revision", 0, "status", "IN_PROGRESS", "notes", "Waiting for accountant"), "OWNER", 200);
        putJson("/api/v1/onboarding/TAX_REVIEW", Map.of("revision", 1, "status", "COMPLETE", "notes", "Reviewed"), "OWNER", 409);
        policy(false, "EXCLUSIVE", before(), true);
        putJson("/api/v1/onboarding/TAX_REVIEW", Map.of("revision", 1, "status", "COMPLETE", "notes", "Reviewed"), "OWNER", 200);
        putJson("/api/v1/onboarding/TAX_REVIEW", Map.of("revision", 1, "status", "NOT_STARTED", "notes", "Stale"), "OWNER", 409);
        assertEquals("COMPLETE", getJson("/api/v1/onboarding", "OWNER", 200).get(2).get("status").asString());
        getJson("/api/v1/onboarding", "MANAGER", 403);
        getJson("/api/products", "CASHIER", 200);
        getJson("/api/v1/onboarding", "OTHER", 200);
        assertEquals(1, db.number("SELECT count(*) FROM onboarding_steps"));
    }

    @Test
    void immutableSnapshotsRejectDirectAmountOrRuleMutation() throws Exception {
        String p = stocked("IMMUTABLE", 5);
        String id = order(p, 1);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE orders SET tax_cents=1 WHERE id=?", id));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE order_lines SET price_cents=0 WHERE order_id=?", id));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE tax_policy_versions SET verified=1 WHERE business_id=1"));
        assertEquals(2550, getJson("/api/orders/" + id, "OWNER", 200).get("total_cents").asLong());
    }

    @Test
    void invalidZeroRatesUnreviewedReferencesAndDuplicateDatesAreRejected() throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("effectiveFrom", before(), "label", "Invalid", "classification", "EXEMPT", "ratePercent", "15", "illustrative", true, "verified", true, "verificationNote", "review", "requestId", key()));
        send("/api/v1/tax/profiles/INVALID/versions", body, "OWNER", 400);
        body.put("classification", "TAXABLE");
        body.put("verificationNote", "");
        send("/api/v1/tax/profiles/INVALID/versions", body, "OWNER", 400);
        body.put("verificationNote", "review");
        body.put("ratePercent", "100.00001");
        send("/api/v1/tax/profiles/INVALID/versions", body, "OWNER", 400);
        rate("SAME", "TAXABLE", "15", before(), true);
        send("/api/v1/tax/profiles/SAME/versions", Map.of("effectiveFrom", before(), "label", "Duplicate", "classification", "TAXABLE", "ratePercent", "20", "illustrative", true, "verified", true, "verificationNote", "review", "requestId", key()), "OWNER", 409);
    }

    @Test
    void publicCheckoutUsesSameServerTaxAndNeverExposesCost() throws Exception {
        String p = priced("PUBLIC_TAX", "100.00", 5);
        policy(true, "EXCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var request = checkout(p, 1, key());
        var response = json(mvc.perform(post("/api/storefront/1/orders").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn());
        assertEquals(11500, response.get("total_cents").asLong());
        assertEquals(1500, response.get("tax_cents").asLong());
        assertFalse(response.get("lines").get(0).has("cost_cents"));
        assertFalse(response.has("events"));
        mvc.perform(get("/api/storefront/1/orders/" + response.get("id").asString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/storefront/1/orders/" + response.get("id").asString()).header("X-Order-Token", response.get("accessToken").asString())).andExpect(status().isOk());
    }

    @Test
    void costChangesCannotChangeCheckoutSalesTax() throws Exception {
        String p = priced("COST", "100.00", 5);
        policy(true, "EXCLUSIVE", before(), true);
        rate("STANDARD", "TAXABLE", "15", before(), true);
        assign(p, "STANDARD", before());
        var first = cart(p, 1);
        db.update("UPDATE products SET cost=9999 WHERE id=?", p);
        var next = cart(p, 1);
        assertEquals(first.get("tax_cents"), next.get("tax_cents"));
        assertEquals(first.get("total_cents"), next.get("total_cents"));
    }

    @Test
    void onboardingFlagsACompletedTaxReviewWhenRulesBecomeUnverified() throws Exception {
        policy(false, "EXCLUSIVE", before(), true);
        putJson("/api/v1/onboarding/TAX_REVIEW", Map.of("revision", 0, "status", "COMPLETE", "notes", "Reviewed"), "OWNER", 200);
        policy(true, "EXCLUSIVE", "2030-01-02T00:00:00Z", false);
        clock.now = Instant.parse("2030-01-03T00:00:00Z");
        var step = getJson("/api/v1/onboarding", "OWNER", 200).get(2);
        assertEquals("COMPLETE", step.get("status").asString());
        assertTrue(step.get("needsReview").asBoolean());
        assertFalse(step.get("ready").asBoolean());
        getJson("/api/products", "CASHIER", 200);
    }
}
