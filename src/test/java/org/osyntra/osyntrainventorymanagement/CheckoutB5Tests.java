package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.osyntra.osyntrainventorymanagement.services.CommerceService;

import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;

import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CheckoutB5Tests extends MvpTestSupport {
    @Autowired
    CommerceService commerce;

    String key() {
        return UUID.randomUUID().toString();
    }

    JsonNode pay(String order, String provider) throws Exception {
        return send("/api/v1/checkout/orders/" + order + "/payments", Map.of("provider", provider, "requestId", key()), "CASHIER", 201);
    }

    JsonNode reverse(String order, String product, int quantity, String type, boolean resellable) throws Exception {
        return send("/api/v1/checkout/orders/" + order + "/reversals", Map.of("type", type, "resellable", resellable, "reason", "Customer request", "lines", List.of(Map.of("productId", product, "quantity", quantity)), "requestId", key()), "CASHIER", 201);
    }

    void approve(String id) throws Exception {
        send("/api/v1/checkout/reversals/" + id + "/review", Map.of("approve", true, "requestId", key()), "OWNER", 200);
    }

    JsonNode complete(String id, String method) throws Exception {
        return send("/api/v1/checkout/reversals/" + id + "/complete", Map.of("confirmed", true, "method", method, "externalReference", "refund-" + key(), "requestId", key()), "OWNER", 200);
    }

    @Test
    void cashCommitsOnceCapturesRealReceiptAndHistoricalCost() throws Exception {
        String p = stocked("CASH_SALE", 5), id = order(p, 2);
        var command = Map.of("provider", "CASH", "requestId", key());
        var first = send("/api/v1/checkout/orders/" + id + "/payments", command, "CASHIER", 201);
        assertEquals("CONFIRMED", first.get("status").asString());
        send("/api/v1/checkout/orders/" + id + "/payments", command, "CASHIER", 201);
        assertEquals(3, quantity(p));
        assertEquals(0, reserved(p));
        assertEquals(1, db.number("SELECT count(*) FROM payment_records"));
        assertEquals(0, db.number("SELECT count(*) FROM simulated_payments"));
        String body = mvc.perform(get("/api/v1/checkout/orders/" + id + "/receipt").with(as("CASHIER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("SALE RECEIPT"));
        assertFalse(body.contains("No real funds"));
        send("/api/orders/" + id + "/cancel", action(key()), "OWNER", 409);
        getJson("/api/v1/checkout/orders/" + id + "/payments", "OTHER", 404);
        var sales = getJson("/api/reports/sales", "OWNER", 200);
        assertFalse(sales.get("simulated").asBoolean());
        assertEquals(1, sales.get("completed_payments").asInt());
    }

    @Test
    void unknownTerminalPaymentSurvivesExpiryAndReconciliationReplaysExactlyOnce() throws Exception {
        String p = stocked("TERMINAL", 2), id = order(p, 1);
        var attempt = pay(id, "MANUAL_TERMINAL");
        String payment = attempt.get("id").asString();
        assertEquals("UNKNOWN", attempt.get("status").asString());
        db.update("UPDATE orders SET expires_at_ms=0 WHERE id=?", id);
        commerce.expire();
        assertEquals(1, reserved(p));
        assertEquals(2, quantity(p));
        send("/api/orders/" + id + "/cancel", action(key()), "OWNER", 409);
        send("/api/orders/" + id + "/payment", payment(true, key()), "CASHIER", 409);
        send("/api/v1/checkout/orders/" + id + "/payments", Map.of("provider", "CASH", "requestId", key()), "CASHIER", 409);
        var event = new LinkedHashMap<String, Object>(Map.of("outcome", "CONFIRMED", "externalReference", "terminal-" + key(), "eventId", key(), "requestId", key()));
        send("/api/v1/checkout/payments/" + payment + "/reconcile", event, "CASHIER", 403);
        send("/api/v1/checkout/payments/" + payment + "/reconcile", event, "OWNER", 200);
        send("/api/v1/checkout/payments/" + payment + "/reconcile", event, "OWNER", 200);
        assertEquals(1, quantity(p));
        assertEquals(0, reserved(p));
        assertEquals(1, db.number("SELECT count(*) FROM payment_records"));
        assertEquals(1, db.number("SELECT count(*) FROM sale_cost_snapshots"));
        event.put("outcome", "DECLINED");
        send("/api/v1/checkout/payments/" + payment + "/reconcile", event, "OWNER", 409);
    }

    @Test
    void verifiedTerminalVoidReleasesHoldWithoutCreatingSale() throws Exception {
        String p = stocked("VOID", 2), id = order(p, 1);
        String attempt = pay(id, "MANUAL_TERMINAL").get("id").asString();
        send("/api/v1/checkout/payments/" + attempt + "/reconcile", Map.of("outcome", "VOIDED", "externalReference", "verified-terminal-void", "eventId", key(), "requestId", key()), "OWNER", 200);
        assertEquals(2, quantity(p));
        assertEquals(0, reserved(p));
        assertEquals(0, db.number("SELECT count(*) FROM sale_cost_snapshots"));
    }

    @Test
    void saleUnitsAndManagerDiscountsUseServerPricesAndTaxSnapshots() throws Exception {
        String p = stocked("BOX_SALE", 24);
        send("/api/v1/catalogue/products/" + p + "/units", Map.of("code", "BOX", "factor", "12", "purchaseAllowed", true, "saleAllowed", true, "requestId", key()), "OWNER", 200);
        send("/api/v1/checkout/prices", Map.of("locationId", 1, "productId", p, "price", "20.00", "effectiveFrom", "2020-01-01T00:00:00Z", "requestId", key()), "OWNER", 201);
        var command = Map.of("lines", List.of(Map.of("productId", p, "unitCode", "BOX", "unitQuantity", "0.5")), "discountPercent", "10", "discountReason", "Approved adjustment", "requestId", key());
        send("/api/orders", command, "CASHIER", 403);
        var order = send("/api/orders", command, "OWNER", 201);
        assertEquals(10800, order.get("total_cents").asLong());
        assertEquals(1200, order.get("discount_cents").asLong());
        var line = order.get("lines").get(0);
        assertEquals(6, line.get("quantity").asInt());
        assertEquals("BOX", line.get("unit_code").asString());
        assertEquals("0.5", line.get("unit_quantity").asString());
        assertEquals("12", line.get("unit_factor").asString());
        assertEquals(18, db.number("SELECT quantity_on_hand-quantity_reserved FROM inventory WHERE product_id=?", p));
        var publicDiscount = Map.of("lines", List.of(Map.of("productId", p, "quantity", 1)), "discountPercent", "10", "discountReason", "Spoof", "requestId", key());
        mvc.perform(post("/api/storefront/1/orders").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(publicDiscount))).andExpect(status().isBadRequest());
    }

    @Test
    void automaticPromotionIsBoundedAndNeverStacksWithManualDiscount() throws Exception {
        String p = stocked("PROMO", 5);
        send("/api/v1/checkout/promotions", Map.of("productId", p, "name", "Intro", "percent", "20", "startsAt", "2020-01-01T00:00:00Z", "endsAt", "2099-01-01T00:00:00Z", "requestId", key()), "OWNER", 201);
        var body = new LinkedHashMap<>(checkout(p, 1, key()));
        body.put("discountPercent", "10");
        body.put("discountReason", "Smaller manual adjustment");
        var order = send("/api/orders", body, "OWNER", 201);
        assertEquals(2040, order.get("total_cents").asLong());
        assertEquals("20", order.get("lines").get(0).get("discount_percent").asString());
        send("/api/v1/checkout/promotions/" + order.get("lines").get(0).get("promotion_id").asString() + "/revoke", action(key()), "OWNER", 200);
        assertEquals(2550, send("/api/orders/cart", Map.of("lines", List.of(Map.of("productId", p, "quantity", 1))), "CASHIER", 200).get("total_cents").asLong());
        send("/api/v1/checkout/promotions", Map.of("name", "Bad", "percent", "101", "startsAt", "2020-01-01T00:00:00Z", "endsAt", "2099-01-01T00:00:00Z", "requestId", key()), "OWNER", 400);
    }

    @Test
    void partialReturnRefundsHistoricalAmountsAndRestocksOnlyOnceAfterApproval() throws Exception {
        String p = stocked("PARTIAL", 5), id = order(p, 3);
        pay(id, "CASH");
        send("/api/orders/" + id + "/fulfill", action(key()), "CLERK", 200);
        var request = reverse(id, p, 1, "RETURN", true);
        String reversal = request.get("id").asString();
        assertEquals(2550, request.get("amount_cents").asLong());
        assertEquals(2, quantity(p));
        send("/api/v1/checkout/reversals/" + reversal + "/complete", Map.of("confirmed", true, "method", "CASH", "requestId", key()), "OWNER", 409);
        send("/api/v1/checkout/reversals/" + reversal + "/review", Map.of("approve", true, "requestId", key()), "CASHIER", 403);
        approve(reversal);
        var completed = Map.of("confirmed", true, "method", "CASH", "requestId", key());
        send("/api/v1/checkout/reversals/" + reversal + "/complete", completed, "OWNER", 200);
        send("/api/v1/checkout/reversals/" + reversal + "/complete", completed, "OWNER", 200);
        assertEquals(3, quantity(p));
        assertEquals("FULFILLED", getJson("/api/orders/" + id, "OWNER", 200).get("status").asString());
        send("/api/orders/" + id + "/refund", action(key()), "OWNER", 409);
        send("/api/v1/checkout/orders/" + id + "/reversals", Map.of("type", "RETURN", "resellable", true, "reason", "Too many", "lines", List.of(Map.of("productId", p, "quantity", 3)), "requestId", key()), "CASHIER", 409);
    }

    @Test
    void refundOnlyDoesNotRestockAndTerminalRefundRequiresExternalConfirmation() throws Exception {
        String p = stocked("TERMINAL_REFUND", 5), id = order(p, 2);
        String attempt = pay(id, "MANUAL_TERMINAL").get("id").asString();
        send("/api/v1/checkout/payments/" + attempt + "/reconcile", Map.of("outcome", "CONFIRMED", "externalReference", "external-sale-" + key(), "eventId", key(), "requestId", key()), "OWNER", 200);
        String reversal = reverse(id, p, 1, "REFUND", false).get("id").asString();
        approve(reversal);
        send("/api/v1/checkout/reversals/" + reversal + "/complete", Map.of("confirmed", true, "method", "EXTERNAL_MANUAL", "requestId", key()), "OWNER", 400);
        complete(reversal, "EXTERNAL_MANUAL");
        assertEquals(3, quantity(p));
        assertEquals(2550, getJson("/api/reports/sales", "OWNER", 200).get("refunded_cents").asLong());
    }

    @Test
    void receiptPayloadCannotChangeAfterSaleAndHtmlPdfEmailUseSavedSnapshot() throws Exception {
        String p = stocked("RECEIPT", 5), id = order(p, 1);
        pay(id, "CASH");
        String before = db.one("SELECT body FROM sale_receipts WHERE order_id=?", id).get("body").toString();
        db.update("UPDATE products SET item_name='<script>new</script>',unit_price=999 WHERE id=?", p);
        send("/api/orders/" + id + "/fulfill", action(key()), "CLERK", 200);
        for (String format : List.of("html", "pdf")) {
            var response = mvc.perform(get("/api/v1/checkout/orders/" + id + "/receipt?format=" + format).with(as("CASHIER"))).andExpect(status().isOk()).andReturn().getResponse();
            assertTrue(response.getContentAsByteArray().length > 100);
            assertFalse(response.getContentAsString().contains("<script>new</script>"));
        }
        assertEquals(before, db.one("SELECT body FROM sale_receipts WHERE order_id=?", id).get("body"));
        var email = Map.of("email", "customer@example.com", "requestId", key());
        send("/api/v1/checkout/orders/" + id + "/receipt-email", email, "CASHIER", 202);
        send("/api/v1/checkout/orders/" + id + "/receipt-email", email, "CASHIER", 202);
        assertEquals(1, db.number("SELECT count(*) FROM email_outbox WHERE kind='SALE_RECEIPT'"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE sale_receipts SET body='forged' WHERE order_id=?", id));
    }

    @Test
    void simultaneousCashRetriesCreateOneSaleAndOneCostCommit() throws Exception {
        String p = stocked("CASH_RACE", 2), id = order(p, 1);
        var command = Map.of("provider", "CASH", "requestId", key());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var futures = List.of(pool.submit(() -> {
                gate.await();
                return send("/api/v1/checkout/orders/" + id + "/payments", command, "CASHIER", 201);
            }), pool.submit(() -> {
                gate.await();
                return send("/api/v1/checkout/orders/" + id + "/payments", command, "CASHIER", 201);
            }));
            gate.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, quantity(p));
        assertEquals(1, db.number("SELECT count(*) FROM payment_attempts"));
        assertEquals(1, db.number("SELECT count(*) FROM sale_cost_snapshots"));
    }

    @Test
    void rejectedRequestsReleaseRefundBudgetAndFinalPartialCentsReconcile() throws Exception {
        String p = stocked("ROUND_REFUND", 5);
        db.update("UPDATE products SET unit_price=0.01 WHERE id=?", p);
        String id = order(p, 3);
        pay(id, "CASH");
        String rejected = reverse(id, p, 1, "REFUND", false).get("id").asString();
        send("/api/v1/checkout/reversals/" + rejected + "/review", Map.of("approve", false, "requestId", key()), "OWNER", 200);
        for (int i = 0; i < 3; i++) {
            String reversal = reverse(id, p, 1, "REFUND", false).get("id").asString();
            approve(reversal);
            complete(reversal, "CASH");
        }
        assertEquals(3, db.number("SELECT sum(amount_cents) FROM sale_reversals WHERE status='COMPLETED'"));
        assertEquals(2, quantity(p));
    }

    @Test
    void preB5CheckoutCommandCanReplayWithoutRepricingOrDuplicatingStock() throws Exception {
        String p = stocked("LEGACY_RETRY", 5), request = key();
        var original = send("/api/orders", checkout(p, 1, request), "CASHIER", 201);
        String old = "CHECKOUT|POS|1|[Line[productId=" + p + ", quantity=1]]";
        db.update("UPDATE command_receipts SET fingerprint=? WHERE business_id=1 AND request_id=?", org.osyntra.osyntrainventorymanagement.services.CommandService.hash(old), request);
        var replay = send("/api/orders", checkout(p, 1, request), "CASHIER", 201);
        assertEquals(original.get("id"), replay.get("id"));
        assertEquals(1, reserved(p));
    }

    @Test
    void checkoutLookupIsExactAndReportsBranchPricesAndEligibleBatchQuantity() throws Exception {
        String p = stocked("SCAN-EXACT", 5);
        send("/api/v1/checkout/prices", Map.of("locationId", 1, "productId", p, "price", "12.00", "effectiveFrom", "2020-01-01T00:00:00Z", "requestId", key()), "OWNER", 201);
        var rows = getJson("/api/v1/checkout/catalogue?locationId=1&code=SCAN-EXACT", "CASHIER", 200);
        assertEquals(1, rows.size());
        assertEquals(1200, rows.get(0).get("price_cents").asLong());
        assertEquals(5, rows.get(0).get("available").asLong());
        assertEquals(0, getJson("/api/v1/checkout/catalogue?locationId=1&code=scan-exact", "CASHIER", 200).size());
        getJson("/api/v1/checkout/catalogue?locationId=" + otherLocation, "CASHIER", 404);
    }

    @Test
    void zeroValuePartialReturnsDoNotCloseTheRemainingPhysicalReturnBudget() throws Exception {
        String p = stocked("FREE_SALE", 5);
        db.update("UPDATE products SET unit_price=0 WHERE id=?", p);
        String id = order(p, 2);
        pay(id, "CASH");
        send("/api/orders/" + id + "/fulfill", action(key()), "CLERK", 200);
        for (int n = 0; n < 2; n++) {
            String reversal = reverse(id, p, 1, "RETURN", true).get("id").asString();
            approve(reversal);
            complete(reversal, "CASH");
            if (n == 0) assertEquals("FULFILLED", getJson("/api/orders/" + id, "OWNER", 200).get("status").asString());
        }
        assertEquals("RETURNED", getJson("/api/orders/" + id, "OWNER", 200).get("status").asString());
        assertEquals(5, quantity(p));
    }
}
