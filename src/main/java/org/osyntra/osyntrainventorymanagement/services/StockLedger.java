package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.inventory.BatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.math.*;
import java.time.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

/**
 * Quantity, reservation, batch allocation and acquisition value change in the same transaction.
 */
@Service
public class StockLedger {
    private final MvpStore db;
    private final BatchService batches;
    private final Clock clock;

    public StockLedger(MvpStore db, BatchService batches, Clock clock) {
        this.db = db;
        this.batches = batches;
        this.clock = clock;
    }

    public Map<String, Object> balance(int business, int location, String product) {
        return db.one("SELECT * FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, product);
    }

    public BigDecimal average(int business, int location, String product) {
        var rows = db.rows("SELECT quantity_on_hand,stock_value FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, product);
        if (!rows.isEmpty() && integer(rows.getFirst(), "quantity_on_hand") > 0)
            return new BigDecimal(rows.getFirst().get("stock_value").toString()).divide(BigDecimal.valueOf(integer(rows.getFirst(), "quantity_on_hand")), 6, RoundingMode.HALF_UP);
        return new BigDecimal(db.one("SELECT cost FROM products WHERE business_id=? AND id=?", business, product).get("cost").toString());
    }

    @Transactional
    public Map<String, Object> change(int business, int location, String product, long hand, long held, String type, String reason, String notes, Integer actor, String actorKind, String reference) {
        return apply(business, location, product, hand, held, type, reason, notes, actor, actorKind, reference, null, null, null);
    }

    @Transactional
    public Map<String, Object> changeValued(int business, int location, String product, long hand, long held, String type, String reason, String notes, Integer actor, String actorKind, String reference, BigDecimal incomingValue, String batchCode, LocalDate expires) {
        return apply(business, location, product, hand, held, type, reason, notes, actor, actorKind, reference, incomingValue, batchCode, expires);
    }

    private Map<String, Object> apply(int business, int location, String product, long hand, long held, String type, String reason, String notes, Integer actor, String actorKind, String reference, BigDecimal incomingValue, String batchCode, LocalDate expires) {
        var old = balance(business, location, product);
        long oldQty = integer(old, "quantity_on_hand");
        BigDecimal oldValue = new BigDecimal(old.get("stock_value").toString());
        BigDecimal delta = BigDecimal.ZERO;
        if (hand > 0) {
            BigDecimal basis = Set.of("OPENING", "RECEIPT").contains(type) ? new BigDecimal(db.one("SELECT cost FROM products WHERE business_id=? AND id=?", business, product).get("cost").toString()) : average(business, location, product);
            delta = incomingValue == null ? basis.multiply(BigDecimal.valueOf(hand)) : incomingValue;
        }
        if (hand < 0) {
            if (-hand > oldQty) BatchServiceConflict("Insufficient stock");
            delta = -hand == oldQty ? oldValue.negate() : oldValue.multiply(BigDecimal.valueOf(hand)).divide(BigDecimal.valueOf(oldQty), 6, RoundingMode.HALF_UP);
        }
        delta = delta.setScale(6, RoundingMode.HALF_UP);
        BigDecimal value = oldValue.add(delta);
        if (value.signum() < 0 || value.precision() > 24)
            BatchServiceConflict("Invalid or overflowing stock valuation");
        batches.apply(business, location, product, hand, held, type, reference, batchCode, expires);
        int changed = db.update("""
                UPDATE inventory SET quantity_on_hand=quantity_on_hand+?,quantity_reserved=quantity_reserved+?,stock_value=?,updated_at=strftime('%Y-%m-%d %H:%M:%f','now')
                WHERE business_id=? AND location_id=? AND product_id=? AND quantity_on_hand+? BETWEEN 0 AND 2147483647
                 AND quantity_reserved+? BETWEEN 0 AND quantity_on_hand+?
                """, hand, held, value.toPlainString(), business, location, product, hand, held, hand);
        if (changed != 1) BatchServiceConflict("Insufficient available stock, missing balance, or quantity overflow");
        var result = balance(business, location, product);
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO stock_movements(id,business_id,location_id,product_id,movement_type,reason,notes,delta_on_hand,delta_reserved,on_hand_after,reserved_after,actor_id,actor_kind,reference_id,value_delta,value_after)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, business, location, product, type, reason, notes, hand, held, result.get("quantity_on_hand"), result.get("quantity_reserved"), actor, actorKind, reference, delta.toPlainString(), value.toPlainString());
        if (hand != 0)
            db.update("INSERT INTO valuation_adjustments VALUES(?,?,?,?,?,?,?,?,?,?)", id, business, location, product, hand, delta.toPlainString(), value.toPlainString(), type, reference, clock.millis());
        result.put("value_delta", delta.toPlainString());
        return result;
    }

    public String today() {
        return LocalDate.now(clock).toString();
    }

    public long sellable(int business, int location, String product) {
        if (batches.tracked(business, product))
            return db.number("SELECT coalesce(sum(quantity_on_hand-quantity_reserved),0) FROM stock_batches WHERE business_id=? AND location_id=? AND product_id=? AND (expires_on IS NULL OR expires_on>=?)", business, location, product, LocalDate.now(clock).toString());
        return db.number("SELECT coalesce(sum(quantity_on_hand-quantity_reserved),0) FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, product);
    }

    public void activeProduct(int business, String product) {
        db.one("SELECT id FROM products WHERE business_id=? AND id=? AND active=1 AND product_kind<>'PARENT'", business, product);
    }

    public void ensure(int business, int location, String product) {
        activeProduct(business, product);
        db.update("INSERT INTO inventory(product_id,business_id,location_id,reorder_level) VALUES(?,?,?,?) ON CONFLICT(product_id,location_id) DO NOTHING", product, business, location, threshold(business, product));
    }

    public int threshold(int business, String product) {
        return integer(db.one("SELECT coalesce(p.min_stock_threshold,b.default_threshold) AS threshold FROM products p JOIN businesses b ON b.id=p.business_id WHERE p.id=? AND p.business_id=?", product, business), "threshold");
    }

    private void BatchServiceConflict(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
}
