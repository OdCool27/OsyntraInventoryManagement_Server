package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

/**
 * All quantity/reservation changes, including checkout, pass through this transaction boundary.
 */
@Service
public class StockLedger {
    private final MvpStore db;

    public StockLedger(MvpStore db) {
        this.db = db;
    }

    public Map<String, Object> balance(int business, int location, String product) {
        return db.one("SELECT * FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, product);
    }

    @Transactional
    public Map<String, Object> change(int business, int location, String product, long onHandDelta, long reservedDelta,
                                      String type, String reason, String notes, Integer actor, String actorKind, String reference) {
        int changed = db.update("""
                UPDATE inventory SET quantity_on_hand=quantity_on_hand+?,quantity_reserved=quantity_reserved+?,
                 updated_at=strftime('%Y-%m-%d %H:%M:%f','now')
                WHERE business_id=? AND location_id=? AND product_id=?
                 AND quantity_on_hand+? BETWEEN 0 AND 2147483647
                 AND quantity_reserved+? BETWEEN 0 AND quantity_on_hand+?
                """, onHandDelta, reservedDelta, business, location, product, onHandDelta, reservedDelta, onHandDelta);
        if (changed != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available stock, missing balance, or quantity overflow");
        var balance = balance(business, location, product);
        db.update("""
                        INSERT INTO stock_movements(id,business_id,location_id,product_id,movement_type,reason,notes,delta_on_hand,delta_reserved,
                         on_hand_after,reserved_after,actor_id,actor_kind,reference_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), business, location, product, type, reason, notes, onHandDelta, reservedDelta,
                balance.get("quantity_on_hand"), balance.get("quantity_reserved"), actor, actorKind, reference);
        return balance;
    }

    public void activeProduct(int business, String product) {
        db.one("SELECT id FROM products WHERE business_id=? AND id=? AND active=1", business, product);
    }

    public int threshold(int business, String product) {
        return integer(db.one("SELECT coalesce(p.min_stock_threshold,b.default_threshold) AS threshold FROM products p JOIN businesses b ON b.id=p.business_id WHERE p.id=? AND p.business_id=?", product, business), "threshold");
    }
}
