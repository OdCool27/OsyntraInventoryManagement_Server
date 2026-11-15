package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PurchasingB4Tests extends MvpTestSupport {
    String key() {
        return UUID.randomUUID().toString();
    }

    String supplier() throws Exception {
        return send("/api/v1/purchasing/suppliers", Map.of("name", "Supplier " + key(), "email", "supplier@example.com", "requestId", key()), "OWNER", 201).get("id").asString();
    }

    Map<String, Object> adjustment(String p, int qty, String type, String cost, String batch, String expiry) {
        var r = new LinkedHashMap<String, Object>(Map.of("locationId", 1, "productId", p, "unit", "EACH", "quantity", qty, "unitCost", cost, "type", type, "reason", "Test acquisition", "requestId", key()));
        if (batch != null) r.put("batchCode", batch);
        if (expiry != null) r.put("expiresOn", expiry);
        return r;
    }

    BigDecimal value(String p, int location) {
        return new BigDecimal(db.one("SELECT stock_value FROM inventory WHERE product_id=? AND location_id=?", p, location).get("stock_value").toString());
    }

    @Test
    void weightedAverageIsPerLocationAndSalesReturnsPreserveAcquisitionValue() throws Exception {
        String p = stocked("AVERAGE", 10);
        send("/api/v1/inventory/adjustments", adjustment(p, 10, "RECEIPT", "30", null, null), "OWNER", 200);
        assertEquals(0, value(p, 1).compareTo(new BigDecimal("400")));
        String id = order(p, 2);
        send("/api/orders/" + id + "/payment", payment(true, key()), "CASHIER", 200);
        assertEquals(0, value(p, 1).compareTo(new BigDecimal("360")));
        assertEquals("40.000000", db.one("SELECT value FROM sale_cost_snapshots WHERE order_id=?", id).get("value"));
        send("/api/orders/" + id + "/cancel", action(key()), "OWNER", 200);
        assertEquals(0, value(p, 1).compareTo(new BigDecimal("400")));
        assertEquals(0, db.number("SELECT count(*) FROM inventory WHERE product_id=? AND location_id=?", p, otherLocation));
    }

    @Test
    void purchaseUnitSnapshotAndPartialReceiptCannotOverReceiveOrDuplicate() throws Exception {
        String p = product("PURCHASE", "OWNER");
        send("/api/v1/catalogue/products/" + p + "/units", Map.of("code", "BOX", "factor", "12", "purchaseAllowed", true, "saleAllowed", true, "requestId", key()), "OWNER", 200);
        String id = send("/api/v1/purchasing/orders", Map.of("supplierId", supplier(), "locationId", 1, "lines", List.of(Map.of("productId", p, "unit", "BOX", "quantity", "2", "unitCost", "120")), "requestId", key()), "OWNER", 201).get("id").asString();
        var receipt = Map.of("lines", List.of(Map.of("productId", p, "quantity", "0.5")), "requestId", key());
        assertEquals("PARTIAL", send("/api/v1/purchasing/orders/" + id + "/receive", receipt, "OWNER", 200).get("status").asString());
        send("/api/v1/purchasing/orders/" + id + "/receive", receipt, "OWNER", 200);
        assertEquals(6, quantity(p));
        assertEquals(0, value(p, 1).compareTo(new BigDecimal("60")));
        send("/api/v1/purchasing/orders/" + id + "/receive", Map.of("lines", List.of(Map.of("productId", p, "quantity", "2")), "requestId", key()), "OWNER", 409);
        assertEquals("RECEIVED", send("/api/v1/purchasing/orders/" + id + "/receive", Map.of("lines", List.of(Map.of("productId", p, "quantity", "1.5")), "requestId", key()), "OWNER", 200).get("status").asString());
        assertEquals(24, quantity(p));
        assertEquals(0, value(p, 1).compareTo(new BigDecimal("240")));
        getJson("/api/v1/purchasing/orders/" + id, "OTHER", 404);
    }

    @Test
    void transfersPreserveValueAcrossPartialReceivingAndHoldInTransit() throws Exception {
        String p = stocked("TRANSFER", 10);
        int destination = ((Number) db.one("INSERT INTO locations(business_id,name) VALUES(1,'Second') RETURNING id").get("id")).intValue();
        String id = send("/api/v1/inventory/transfers", Map.of("sourceId", 1, "destinationId", destination, "lines", List.of(Map.of("productId", p, "quantity", 4)), "requestId", key()), "OWNER", 201).get("id").asString();
        var dispatch = Map.of("requestId", key());
        var sent = send("/api/v1/inventory/transfers/" + id + "/dispatch", dispatch, "OWNER", 200);
        send("/api/v1/inventory/transfers/" + id + "/dispatch", dispatch, "OWNER", 200);
        assertEquals(6, quantity(p));
        assertEquals(4, sent.get("lines").get(0).get("in_transit_quantity").asInt());
        send("/api/v1/inventory/transfers/" + id + "/cancel", Map.of("requestId", key()), "OWNER", 409);
        send("/api/v1/inventory/transfers/" + id + "/receive", Map.of("quantities", Map.of(p, 1), "requestId", key()), "OWNER", 200);
        assertEquals(0, value(p, 1).add(value(p, destination)).compareTo(new BigDecimal("70")));
        var complete = send("/api/v1/inventory/transfers/" + id + "/receive", Map.of("quantities", Map.of(p, 3), "requestId", key()), "OWNER", 200);
        assertEquals("RECEIVED", complete.get("status").asString());
        assertEquals(0, value(p, 1).add(value(p, destination)).compareTo(new BigDecimal("100")));
    }

    @Test
    void expiredTrackedStockCannotBeReservedAndCountsRequireApprovalAndFreshSnapshot() throws Exception {
        String p = product("BATCH", "OWNER");
        send("/api/v1/inventory/products/" + p + "/tracking", Map.of("enabled", true, "requestId", key()), "OWNER", 200);
        send("/api/v1/inventory/adjustments", adjustment(p, 5, "RECEIPT", "10", "OLD", "2000-01-01"), "OWNER", 200);
        send("/api/orders", checkout(p, 1, key()), "CASHIER", 409);
        assertEquals(0, reserved(p));
        send("/api/v1/inventory/adjustments", adjustment(p, 3, "RECEIPT", "20", "FRESH", "2099-01-01"), "OWNER", 200);
        String order = order(p, 2);
        send("/api/orders/" + order + "/payment", payment(true, key()), "CASHIER", 200);
        var batches = getJson("/api/v1/inventory/batches?locationId=1&expiring=true&days=0", "OWNER", 200);
        assertEquals(1, batches.size());
        String batch = batches.get(0).get("id").asString();
        var count = Map.of("expectedQuantity", 5, "countedQuantity", 4, "reason", "Approved batch count", "requestId", key());
        send("/api/v1/inventory/batches/" + batch + "/count", count, "CLERK", 403);
        send("/api/v1/inventory/batches/" + batch + "/count", count, "OWNER", 200);
        send("/api/v1/inventory/batches/" + batch + "/count", Map.of("expectedQuantity", 5, "countedQuantity", 3, "reason", "Stale count", "requestId", key()), "OWNER", 409);
    }

    @Test
    void expiryChangingWhileReservedBlocksSaleButPermitsSafeRelease() throws Exception {
        String p = product("EXPIRES", "OWNER");
        send("/api/v1/inventory/products/" + p + "/tracking", Map.of("enabled", true, "requestId", key()), "OWNER", 200);
        send("/api/v1/inventory/adjustments", adjustment(p, 2, "RECEIPT", "10", "BATCH", "2099-01-01"), "OWNER", 200);
        String id = order(p, 1);
        db.update("UPDATE stock_batches SET expires_on='2000-01-01' WHERE product_id=?", p);
        send("/api/orders/" + id + "/payment", payment(true, key()), "CASHIER", 409);
        assertEquals(1, reserved(p));
        send("/api/orders/" + id + "/cancel", action(key()), "OWNER", 200);
        assertEquals(0, reserved(p));
        assertEquals(2, quantity(p));
    }

    @Test
    void concurrentReceiptCommandsOnlyReceiveTheOutstandingQuantityOnce() throws Exception {
        String p = product("RACE_RECEIPT", "OWNER");
        String id = send("/api/v1/purchasing/orders", Map.of("supplierId", supplier(), "locationId", 1, "lines", List.of(Map.of("productId", p, "unit", "EACH", "quantity", "5", "unitCost", "10")), "requestId", key()), "OWNER", 201).get("id").asString();
        var command = Map.of("lines", List.of(Map.of("productId", p, "quantity", "5")), "requestId", key());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = List.of(pool.submit(() -> {
                start.await();
                return send("/api/v1/purchasing/orders/" + id + "/receive", command, "OWNER", 200);
            }), pool.submit(() -> {
                start.await();
                return send("/api/v1/purchasing/orders/" + id + "/receive", command, "OWNER", 200);
            }));
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(5, quantity(p));
        assertEquals(1, db.number("SELECT count(*) FROM goods_receipts"));
    }

    @Test
    void negativeAdjustmentsCannotConsumeReservedStockOrTamperWithOtherBranches() throws Exception {
        String p = stocked("RESERVED_ADJUST", 3);
        order(p, 3);
        send("/api/v1/inventory/adjustments", adjustment(p, 1, "DAMAGE", "0", null, null), "OWNER", 409);
        assertEquals(3, quantity(p));
        assertEquals(3, reserved(p));
        var other = adjustment(p, 1, "RECEIPT", "10", null, null);
        other.put("locationId", otherLocation);
        send("/api/v1/inventory/adjustments", other, "OWNER", 404);
        send("/api/v1/inventory/adjustments", adjustment(p, 1, "RECEIPT", "10", null, null), "CASHIER", 403);
    }
}
