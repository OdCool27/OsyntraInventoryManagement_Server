package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.identity.Totp;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FunctionalMvpTests extends MvpTestSupport {
    String key() {
        return UUID.randomUUID().toString();
    }

    JsonNode api(String path, Object body, String token, int status) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json(mvc.perform(request).andExpect(status().is(status)).andReturn());
    }

    JsonNode read(String path, String token) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void ownerMfaThroughTaxPurchaseBatchCheckoutTerminalReceiptReturnAndReport() throws Exception {
        String pending = api("/api/v1/auth/login", Map.of("email", "owner@test.local", "password", PASSWORD), null, 200).get("accessToken").asString();
        var setup = api("/api/v1/auth/mfa/setup", Map.of(), pending, 200);
        String token = api("/api/v1/auth/mfa/confirm", Map.of("code", Totp.code(setup.get("secret").asString(), System.currentTimeMillis() / 30000, 6)), pending, 200).get("accessToken").asString();
        var product = productBody("VERTICAL_VARIANT");
        product.put("unitPrice", "100.00");
        product.put("cost", "0");
        var parent = productBody("VERTICAL_PARENT");
        parent.put("productKind", "PARENT");
        String parentId = api("/api/products", parent, token, 201).get("itemId").asString();
        product.putAll(Map.of("productKind", "VARIANT", "parentId", parentId, "variantKey", "BLUE"));
        String p = api("/api/products", product, token, 201).get("itemId").asString();
        api("/api/v1/catalogue/products/" + p + "/units", Map.of("code", "BOX", "factor", "12", "purchaseAllowed", true, "saleAllowed", true, "requestId", key()), token, 200);
        api("/api/v1/inventory/products/" + p + "/tracking", Map.of("enabled", true, "requestId", key()), token, 200);
        String date = Instant.now().minusSeconds(60).toString();
        date = Instant.ofEpochMilli(Instant.parse(date).toEpochMilli()).toString();
        api("/api/v1/tax/policies", Map.of("effectiveFrom", date, "registrationStatus", "REGISTERED", "registrationNumber", "TEST-ONLY", "priceMode", "EXCLUSIVE", "verified", true, "verificationNote", "Illustrative test accountant reference", "requestId", key()), token, 201);
        api("/api/v1/tax/profiles/TEST_STANDARD/versions", Map.of("effectiveFrom", date, "label", "Illustrative test GCT", "classification", "TAXABLE", "ratePercent", "15", "illustrative", true, "verified", true, "verificationNote", "Test-only review", "requestId", key()), token, 201);
        api("/api/v1/products/" + p + "/tax", Map.of("code", "TEST_STANDARD", "effectiveFrom", date, "requestId", key()), token, 201);
        String supplier = api("/api/v1/purchasing/suppliers", Map.of("name", "Vertical test supplier", "email", "supplier@example.com", "requestId", key()), token, 201).get("id").asString();
        String po = api("/api/v1/purchasing/orders", Map.of("supplierId", supplier, "locationId", 1, "lines", List.of(Map.of("productId", p, "unit", "BOX", "quantity", "1", "unitCost", "120.00")), "requestId", key()), token, 201).get("id").asString();
        api("/api/v1/purchasing/orders/" + po + "/receive", Map.of("lines", List.of(Map.of("productId", p, "quantity", "1", "batchCode", "TEST-LOT", "expiresOn", "2099-12-31")), "requestId", key()), token, 200);
        var order = api("/api/orders", checkout(p, 3, key()), token, 201);
        String id = order.get("id").asString();
        assertEquals(34500, order.get("total_cents").asLong());
        assertEquals(4500, order.get("tax_cents").asLong());
        String payment = api("/api/v1/checkout/orders/" + id + "/payments", Map.of("provider", "MANUAL_TERMINAL", "requestId", key()), token, 201).get("id").asString();
        api("/api/v1/checkout/payments/" + payment + "/reconcile", Map.of("outcome", "CONFIRMED", "externalReference", "test-terminal-confirmation", "eventId", key(), "requestId", key()), token, 200);
        assertEquals(9, quantity(p));
        assertEquals(0, reserved(p));
        api("/api/v1/checkout/orders/" + id + "/receipt-email", Map.of("email", "customer@example.com", "requestId", key()), token, 202);
        api("/api/orders/" + id + "/fulfill", action(key()), token, 200);
        String reversal = api("/api/v1/checkout/orders/" + id + "/reversals", Map.of("type", "RETURN", "resellable", true, "reason", "Test return", "lines", List.of(Map.of("productId", p, "quantity", 1)), "requestId", key()), token, 201).get("id").asString();
        api("/api/v1/checkout/reversals/" + reversal + "/review", Map.of("approve", true, "requestId", key()), token, 200);
        var refunded = api("/api/v1/checkout/reversals/" + reversal + "/complete", Map.of("confirmed", true, "method", "EXTERNAL_MANUAL", "externalReference", "test-terminal-refund", "requestId", key()), token, 200);
        assertEquals(11500, refunded.get("amount_cents").asLong());
        assertEquals(1500, refunded.get("tax_cents").asLong());
        assertEquals(10, quantity(p));
        var report = read("/api/reports/sales", token);
        assertEquals(34500, report.get("gross_revenue_cents").asLong());
        assertEquals(11500, report.get("refunded_cents").asLong());
        assertEquals(23000, report.get("net_revenue_cents").asLong());
        assertEquals(1, db.number("SELECT count(*) FROM email_outbox WHERE kind='SALE_RECEIPT'"));
        assertEquals(1, db.number("SELECT count(*) FROM sale_receipts"));
    }
}
