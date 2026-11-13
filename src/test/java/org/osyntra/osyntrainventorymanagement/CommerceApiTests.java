package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.osyntra.osyntrainventorymanagement.services.CommerceService;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.http.MediaType;

class CommerceApiTests extends MvpTestSupport {
    @Autowired
    CommerceService commerce;

    @Test
    void cartPreviewNeverReservesStockAndCurrencyCannotChangeAfterOrders() throws Exception {
        String p = stocked("CART", 2);
        var cart = send("/api/orders/cart", Map.of("lines", List.of(Map.of("productId", p, "quantity", 3))), "CASHIER", 200);
        assertFalse(cart.get("sufficientStock").asBoolean());
        assertEquals(0, reserved(p));
        order(p, 1);
        putJson("/api/business", Map.of("name", "Updated business", "currency", "USD", "defaultThreshold", 3), "OWNER", 409);
        assertEquals("JMD", getJson("/api/business", "OWNER", 200).get("currency").asString());
    }

    @Test
    void checkoutPaymentAndCancellationAreIdempotentAndPreserveSnapshots() throws Exception {
        String p = stocked("SALE", 3);
        var checkout = checkout(p, 2, "checkout-once");
        String order = send("/api/orders", checkout, "CASHIER", 201).get("id").asString();
        send("/api/orders", checkout, "CASHIER", 201);
        assertEquals(3, quantity(p));
        assertEquals(2, reserved(p));
        var edit = productBody("SALE-RENAMED");
        edit.put("unitPrice", 99);
        edit.put("cost", 50);
        putJson("/api/products/" + p, edit, "OWNER", 200);
        var paid = send("/api/orders/" + order + "/payment", payment(true, "pay-once"), "CASHIER", 200);
        assertEquals("COMPLETED", paid.get("status").asString());
        assertEquals(5100, paid.get("total_cents").asInt());
        assertEquals(2550, paid.get("lines").get(0).get("price_cents").asInt());
        assertEquals(1, quantity(p));
        assertEquals(0, reserved(p));
        send("/api/orders/" + order + "/payment", payment(true, "pay-once"), "CASHIER", 200);
        send("/api/orders/" + order + "/payment", payment(false, "pay-once"), "CASHIER", 409);
        send("/api/orders/" + order + "/cancel", action("cancel-once"), "CASHIER", 200);
        send("/api/orders/" + order + "/cancel", action("cancel-once"), "CASHIER", 200);
        assertEquals(3, quantity(p));
        assertEquals(1, db.number("SELECT count(*) FROM simulated_refunds WHERE order_id=?", order));
        var sales = getJson("/api/reports/sales", "OWNER", 200);
        assertEquals(5100, sales.get("gross_revenue_cents").asInt());
        assertEquals(0, sales.get("net_revenue_cents").asInt());
    }

    @Test
    void failedPaymentsAndExpiryReleaseReservations() throws Exception {
        String p = stocked("FAILED", 2);
        String first = order(p, 2);
        var failure = send("/api/orders/" + first + "/payment", payment(false, "fail"), "CASHIER", 200);
        assertEquals("PAYMENT_FAILED", failure.get("status").asString());
        assertEquals(2, quantity(p));
        assertEquals(0, reserved(p));
        String second = order(p, 2);
        db.update("UPDATE orders SET expires_at_ms=0 WHERE id=?", second);
        commerce.expire();
        assertEquals(0, reserved(p));
        assertEquals("EXPIRED", getJson("/api/orders/" + second, "OWNER", 200).get("status").asString());
        send("/api/orders/" + second + "/payment", payment(true, "too-late"), "CASHIER", 409);
    }

    @Test
    void latePaymentExpiresAndCommitsRelease() throws Exception {
        String p = stocked("LATE", 1);
        String id = order(p, 1);
        db.update("UPDATE orders SET expires_at_ms=0 WHERE id=?", id);
        assertEquals("EXPIRED", send("/api/orders/" + id + "/payment", payment(true, "late"), "CASHIER", 200).get("status").asString());
        assertEquals(1, quantity(p));
        assertEquals(0, reserved(p));
    }

    @Test
    void insufficientAndDuplicateLinesRollbackEntireCheckout() throws Exception {
        String a = stocked("FIRST", 2), b = stocked("SECOND", 0);
        send("/api/orders", Map.of("lines", List.of(Map.of("productId", a, "quantity", 1), Map.of("productId", b, "quantity", 1)), "requestId", "insufficient"), "CASHIER", 409);
        assertEquals(0, reserved(a));
        assertEquals(0, db.number("SELECT count(*) FROM orders"));
        send("/api/orders", Map.of("lines", List.of(Map.of("productId", a, "quantity", 1), Map.of("productId", a, "quantity", 1)), "requestId", "duplicate-lines"), "CASHIER", 400);
        assertEquals(0, reserved(a));
    }

    @Test
    void refundDoesNotRestockAndPhysicalReturnsRestoreOnlyResellableStock() throws Exception {
        String p = stocked("RETURNS", 3);
        String id = order(p, 1);
        send("/api/orders/" + id + "/payment", payment(true, "return-pay"), "CASHIER", 200);
        send("/api/orders/" + id + "/refund", action("refund-only"), "OWNER", 200);
        assertEquals(2, quantity(p));
        send("/api/orders/" + id + "/fulfill", action("fulfill"), "CLERK", 200);
        send("/api/orders/" + id + "/cancel", action("cannot-cancel"), "CASHIER", 409);
        var request = Map.of("resellable", false, "reason", "Damaged return", "requestId", "return-damaged");
        send("/api/orders/" + id + "/return", request, "OWNER", 200);
        send("/api/orders/" + id + "/return", request, "OWNER", 200);
        assertEquals(2, quantity(p));
        assertEquals(1, db.number("SELECT count(*) FROM simulated_refunds WHERE order_id=?", id));
        String second = order(p, 1);
        send("/api/orders/" + second + "/payment", payment(true, "pay2"), "CASHIER", 200);
        send("/api/orders/" + second + "/fulfill", action("fulfill2"), "CLERK", 200);
        send("/api/orders/" + second + "/return", Map.of("resellable", true, "reason", "Unopened", "requestId", "return2"), "OWNER", 200);
        assertEquals(2, quantity(p));
    }

    @Test
    void onlineOrdersNeedCapabilityAndNeverExposeCosts() throws Exception {
        String p = stocked("ONLINE", 2);
        var request = checkout(p, 1, "online-create");
        var created = mvc.perform(post("/api/storefront/1/orders").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].cost_cents").doesNotExist()).andReturn();
        var body = json(created);
        String id = body.get("id").asString(), token = body.get("accessToken").asString();
        mvc.perform(get("/api/storefront/1/orders/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/storefront/1/orders/" + id).header("X-Order-Token", "wrong")).andExpect(status().isNotFound());
        mvc.perform(post("/api/storefront/1/orders").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isNotFound());
        mvc.perform(post("/api/storefront/1/orders").header("X-Order-Token", token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/storefront/1/orders/" + id + "/payment").header("X-Order-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payment(true, "online-pay")))).andExpect(status().isOk());
        mvc.perform(get("/api/storefront/1/products")).andExpect(status().isOk()).andExpect(jsonPath("$[0].available").value(1)).andExpect(jsonPath("$[0].cost").doesNotExist());
        getJson("/api/orders/" + id, "OTHER", 404);
        assertEquals(1, db.number("SELECT count(*) FROM orders WHERE channel='ONLINE'"));
    }

    @Test
    void tenantTamperingCannotReserveOtherBusinessProductOrLocation() throws Exception {
        String other = product("OTHER-STOCK", "OTHER");
        open(other, 2, "OTHER", 201);
        send("/api/orders", checkout(other, 1, "wrong-tenant"), "CASHIER", 404);
        String own = stocked("OUR-STOCK", 2);
        var request = new LinkedHashMap<>(checkout(own, 1, "wrong-location"));
        request.put("locationId", otherLocation);
        send("/api/orders", request, "CASHIER", 404);
        send("/api/orders", checkout(own, 1, "clerk-checkout"), "CLERK", 403);
    }

    @Test
    void reportsAndLedgerReconcileAndReceiptsAreExplicitlySimulated() throws Exception {
        String p = stocked("REPORT", 4);
        String id = order(p, 2);
        send("/api/orders/" + id + "/payment", payment(true, "report-pay"), "CASHIER", 200);
        assertEquals(quantity(p), db.number("SELECT sum(delta_on_hand) FROM stock_movements WHERE product_id=?", p));
        assertEquals(reserved(p), db.number("SELECT sum(delta_reserved) FROM stock_movements WHERE product_id=?", p));
        var report = getJson("/api/reports/sales?channel=POS", "MANAGER", 200);
        assertEquals(5100, report.get("gross_revenue_cents").asInt());
        assertEquals(2000, report.get("byProduct").get(0).get("sale_cost_estimate_cents").asInt());
        assertEquals(0, getJson("/api/reports/sales", "OTHER", 200).get("gross_revenue_cents").asInt());
        mvc.perform(get("/api/orders/" + id + "/receipt").with(as("CASHIER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SIMULATED")));
        mvc.perform(get("/api/reports/stock.csv").with(as("MANAGER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REPORT")));
    }
}
