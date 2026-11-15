package org.osyntra.osyntrainventorymanagement.inventory;

import org.springframework.stereotype.Service;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;

import java.time.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class BatchService {
    private final MvpStore db;
    private final Clock clock;

    public BatchService(MvpStore db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public boolean tracked(int business, String product) {
        return db.number("SELECT batch_tracked FROM products WHERE id=? AND business_id=?", product, business) == 1;
    }

    private void change(Map<String, Object> batch, long hand, long held, String ref) {
        if (db.update("UPDATE stock_batches SET quantity_on_hand=quantity_on_hand+?,quantity_reserved=quantity_reserved+? WHERE id=? AND business_id=? AND quantity_on_hand+?>=0 AND quantity_reserved+? BETWEEN 0 AND quantity_on_hand+?",
                hand, held, batch.get("id"), batch.get("business_id"), hand, held, hand) != 1)
            conflict("Insufficient batch stock");
        db.update("INSERT INTO batch_movements VALUES(?,?,?,?,?,?,?)", UUID.randomUUID().toString(), batch.get("business_id"), batch.get("id"), hand, held, ref, clock.millis());
    }

    public void apply(int business, int location, String product, long hand, long held, String type, String reference, String code, LocalDate expires) {
        if (!tracked(business, product)) {
            if (code != null || expires != null)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Enable batch tracking before providing batch metadata");
            return;
        }
        if (expires != null && (expires.getYear() < 1 || expires.getYear() > 9999))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Batch expiry must use years 0001–9999");
        String today = LocalDate.now(clock).toString();
        if (held < 0) {
            var rows = db.rows("SELECT a.*,b.id,b.expires_on,b.business_id FROM order_batch_allocations a JOIN stock_batches b ON b.id=a.batch_id WHERE a.order_id=? AND a.product_id=? AND a.business_id=? AND a.status='HELD' ORDER BY b.expires_on,b.id", reference, product, business);
            long total = 0;
            for (var a : rows) {
                int qty = integer(a, "quantity");
                total += qty;
                if (hand < 0 && a.get("expires_on") != null && a.get("expires_on").toString().compareTo(today) < 0)
                    conflict("A reserved batch expired; cancel safely or reconcile payment before selling");
                change(a, hand < 0 ? -qty : 0, -qty, reference);
                db.update("UPDATE order_batch_allocations SET status=? WHERE order_id=? AND product_id=? AND batch_id=?", hand < 0 ? "CONSUMED" : "RELEASED", reference, product, a.get("id"));
            }
            if (total != -held) conflict("Batch reservations do not reconcile with order");
            return;
        }
        if (hand > 0 && (type.equals("RETURN") || type.equals("CANCELLATION"))) {
            long remaining = hand;
            for (var a : db.rows("SELECT a.*,b.id,b.business_id FROM order_batch_allocations a JOIN stock_batches b ON b.id=a.batch_id WHERE a.order_id=? AND a.product_id=? AND a.business_id=? AND a.status='CONSUMED' ORDER BY b.id", reference, product, business)) {
                long qty = Math.min(remaining, integer(a, "quantity") - integer(a, "returned_quantity"));
                if (qty <= 0) continue;
                change(a, qty, 0, reference);
                db.update("UPDATE order_batch_allocations SET returned_quantity=returned_quantity+? WHERE order_id=? AND product_id=? AND batch_id=?", qty, reference, product, a.get("id"));
                remaining -= qty;
            }
            if (remaining != 0) conflict("Returned batch quantity exceeds originally sold quantity");
            return;
        }
        if (hand > 0) {
            if (code == null || code.isBlank())
                conflict("Batch-tracked receipts require an explicit batch code and optional expiry");
            db.update("INSERT INTO stock_batches(id,business_id,location_id,product_id,code,expires_on) VALUES(?,?,?,?,?,?) ON CONFLICT(business_id,location_id,product_id,code) DO NOTHING",
                    UUID.randomUUID().toString(), business, location, product, code, expires == null ? null : expires.toString());
            var batch = db.one("SELECT * FROM stock_batches WHERE business_id=? AND location_id=? AND product_id=? AND code=?", business, location, product, code);
            if (!Objects.equals(batch.get("expires_on"), expires == null ? null : expires.toString()))
                conflict("Existing batch expiry is immutable; use a distinct batch code");
            change(batch, hand, 0, reference);
            return;
        }
        long remaining = held > 0 ? held : -hand;
        if (remaining == 0) return;
        var candidates = db.rows("SELECT * FROM stock_batches WHERE business_id=? AND location_id=? AND product_id=? AND (? IS NULL OR code=?) AND (?=0 OR expires_on IS NULL OR expires_on>=?) ORDER BY coalesce(expires_on,'9999-12-31'),id",
                business, location, product, code, code, held > 0 ? 1 : 0, today);
        for (var b : candidates) {
            long qty = Math.min(remaining, integer(b, "quantity_on_hand") - integer(b, "quantity_reserved"));
            if (qty <= 0) continue;
            change(b, held > 0 ? 0 : -qty, held > 0 ? qty : 0, reference);
            if (held > 0)
                db.update("INSERT INTO order_batch_allocations(order_id,business_id,product_id,batch_id,quantity,status) VALUES(?,?,?,?,?,'HELD')", reference, business, product, b.get("id"), qty);
            remaining -= qty;
            if (remaining == 0) break;
        }
        if (remaining != 0) conflict("Insufficient eligible unexpired batch stock");
    }

    public void checkSale(int business, String order) {
        if (db.number("SELECT count(*) FROM order_batch_allocations a JOIN stock_batches b ON b.id=a.batch_id WHERE a.business_id=? AND a.order_id=? AND a.status='HELD' AND b.expires_on<?", business, order, LocalDate.now(clock).toString()) > 0)
            conflict("Reserved batch has expired");
    }

    static void conflict(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
}
