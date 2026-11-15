package org.osyntra.osyntrainventorymanagement.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.osyntra.osyntrainventorymanagement.catalogue.UnitService;

import java.time.*;
import java.math.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class PurchasingService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final StockLedger ledger;
    private final CommandService commands;
    private final UnitService units;
    private final AuditService audit;
    private final Clock clock;

    public PurchasingService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, CommandService commands, UnitService units, AuditService audit, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.commands = commands;
        this.units = units;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Map<String, Object>> suppliers(int limit, int offset) {
        tenant.requireRole("OWNER", "MANAGER");
        return db.rows("SELECT id,name,email,active FROM suppliers WHERE business_id=? ORDER BY name,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    @Transactional
    public Map<String, Object> supplier(InventoryDtos.Supplier r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        String command = "SUPPLIER|" + r, previous = commands.replay(business, r.requestId(), command);
        if (previous != null) return db.one("SELECT * FROM suppliers WHERE id=? AND business_id=?", previous, business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO suppliers(id,business_id,name,email) VALUES(?,?,?,?)", id, business, r.name().trim(), r.email());
        commands.save(business, r.requestId(), command, id);
        audit.log("SUPPLIER_CREATED", "SUPPLIER", id, null, r);
        return db.one("SELECT * FROM suppliers WHERE id=? AND business_id=?", id, business);
    }

    public List<Map<String, Object>> orders(int limit, int offset) {
        tenant.requireRole("OWNER", "MANAGER");
        return db.rows("SELECT * FROM purchase_orders WHERE business_id=?" + tenant.locationPredicate("location_id") + " ORDER BY created_ms DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public Map<String, Object> get(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var result = db.one("SELECT * FROM purchase_orders WHERE id=? AND business_id=?", id, business);
        tenant.requireLocation(integer(result, "location_id"));
        result.put("lines", db.rows("SELECT * FROM purchase_order_lines WHERE purchase_order_id=? AND business_id=? ORDER BY product_id", id, business));
        result.put("receipts", db.rows("SELECT * FROM goods_receipts WHERE purchase_order_id=? AND business_id=? ORDER BY created_ms,id", id, business));
        return result;
    }

    @Transactional
    public Map<String, Object> order(InventoryDtos.Purchase r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int location = tenancy.locationId(r.locationId());
        String command = "PURCHASE|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return get(prior);
        db.one("SELECT id FROM suppliers WHERE id=? AND business_id=? AND active=1", r.supplierId().toString(), business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO purchase_orders VALUES(?,?,?,?,?,'OPEN',?,?)", id, business, r.supplierId().toString(), location, db.one("SELECT currency FROM businesses WHERE id=?", business).get("currency"), tenant.actor().getId(), clock.millis());
        Set<UUID> seen = new HashSet<>();
        for (var line : r.lines()) {
            if (!seen.add(line.productId())) bad("Purchase products must be unique");
            String product = line.productId().toString();
            ledger.activeProduct(business, product);
            int base = units.convert(business, product, line.unit(), line.quantity(), "PURCHASE");
            String factor = string(db.one("SELECT factor FROM product_units WHERE business_id=? AND product_id=? AND code=?", business, product, line.unit()), "factor");
            BigDecimal total = line.quantity().multiply(line.unitCost()).setScale(6, RoundingMode.HALF_UP);
            db.update("INSERT INTO purchase_order_lines(purchase_order_id,business_id,product_id,unit_code,unit_quantity,factor,unit_cost,ordered_quantity,total_value) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, business, product, line.unit(), line.quantity().toPlainString(), factor, line.unitCost().toPlainString(), base, total.toPlainString());
        }
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", id, null, r, location);
        return get(id);
    }

    @Transactional
    public Map<String, Object> receive(String id, InventoryDtos.Receive r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var order = get(id);
        int location = tenancy.locationId(integer(order, "location_id"));
        String command = "PO_RECEIPT|" + id + "|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return get(id);
        if (!Set.of("OPEN", "PARTIAL").contains(order.get("status")))
            conflict("Purchase order is not open for receiving");
        String receipt = UUID.randomUUID().toString();
        db.update("INSERT INTO goods_receipts VALUES(?,?,?,?,?)", receipt, business, id, tenant.actor().getId(), clock.millis());
        Set<UUID> seen = new HashSet<>();
        for (var line : r.lines()) {
            if (!seen.add(line.productId())) bad("Receiving products must be unique");
            String product = line.productId().toString();
            var po = db.one("SELECT * FROM purchase_order_lines WHERE purchase_order_id=? AND business_id=? AND product_id=?", id, business, product);
            int base;
            try {
                base = line.quantity().multiply(new BigDecimal(po.get("factor").toString())).intValueExact();
            } catch (ArithmeticException e) {
                bad("Receipt must resolve to whole base units");
                return Map.of();
            }
            int remaining = integer(po, "ordered_quantity") - integer(po, "received_quantity");
            if (base <= 0 || base > remaining) conflict("Received quantity exceeds the purchase-order remainder");
            BigDecimal value = base == remaining ? new BigDecimal(po.get("total_value").toString()).subtract(new BigDecimal(po.get("received_value").toString())) : line.quantity().multiply(new BigDecimal(po.get("unit_cost").toString())).setScale(6, RoundingMode.HALF_UP);
            ledger.ensure(business, location, product);
            ledger.changeValued(business, location, product, base, 0, "RECEIPT", "PURCHASE_RECEIPT", null, tenant.actor().getId(), "EMPLOYEE", receipt, value, line.batchCode(), line.expiresOn());
            db.update("INSERT INTO goods_receipt_lines VALUES(?,?,?,?,?,?,?)", receipt, business, product, base, value.toPlainString(), line.batchCode(), line.expiresOn() == null ? null : line.expiresOn().toString());
            db.update("UPDATE purchase_order_lines SET received_quantity=received_quantity+?,received_value=? WHERE purchase_order_id=? AND business_id=? AND product_id=?", base, new BigDecimal(po.get("received_value").toString()).add(value).toPlainString(), id, business, product);
        }
        boolean done = db.number("SELECT count(*) FROM purchase_order_lines WHERE purchase_order_id=? AND received_quantity<ordered_quantity", id) == 0;
        db.update("UPDATE purchase_orders SET status=? WHERE id=? AND business_id=?", done ? "RECEIVED" : "PARTIAL", id, business);
        commands.save(business, r.requestId(), command, receipt);
        audit.logLocation("GOODS_RECEIVED", "PURCHASE_ORDER", id, null, r, location);
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id, InventoryDtos.Command r) {
        tenant.requireRole("OWNER", "MANAGER");
        var order = get(id);
        int business = tenant.businessId();
        String command = "PO_CANCEL|" + id;
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (!order.get("status").equals("OPEN")) conflict("Only unreceived open purchase orders can be cancelled");
        db.update("UPDATE purchase_orders SET status='CANCELLED' WHERE id=? AND business_id=?", id, business);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("PURCHASE_CANCELLED", "PURCHASE_ORDER", id, null, r, integer(order, "location_id"));
        return get(id);
    }

    @Transactional
    public Map<String, Object> adjust(InventoryDtos.Adjustment r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId(), location = tenancy.locationId(r.locationId());
        String product = r.productId().toString();
        String command = "VALUED_ADJUSTMENT|" + r;
        if (commands.replay(business, r.requestId(), command) != null)
            return ledger.balance(business, location, product);
        int qty = units.convert(business, product, r.unit(), r.quantity(), "PURCHASE");
        boolean incoming = Set.of("RECEIPT", "CORRECTION_IN").contains(r.type());
        ledger.ensure(business, location, product);
        BigDecimal value = r.quantity().multiply(r.unitCost()).setScale(6, RoundingMode.HALF_UP);
        var balance = ledger.changeValued(business, location, product, incoming ? qty : -qty, 0, r.type(), r.reason(), null, tenant.actor().getId(), "EMPLOYEE", r.requestId(), incoming ? value : null, r.batchCode(), r.expiresOn());
        commands.save(business, r.requestId(), command, product);
        audit.logLocation("VALUED_" + r.type(), "INVENTORY", product, null, r, location);
        return balance;
    }

    @Transactional
    public Map<String, Object> tracking(String product, InventoryDtos.Tracking r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        ledger.activeProduct(business, product);
        String command = "BATCH_TRACKING|" + product + "|" + r;
        if (commands.replay(business, r.requestId(), command) == null) {
            if (!tenant.actor().hasAllBranches())
                throw new org.springframework.security.access.AccessDeniedException("All-branch access required to change product tracking");
            boolean current = db.number("SELECT batch_tracked FROM products WHERE business_id=? AND id=?", business, product) == 1;
            if (current != r.enabled() && (db.number("SELECT count(*) FROM stock_movements WHERE business_id=? AND product_id=?", business, product) > 0 ||
                    db.number("SELECT count(*) FROM order_lines WHERE business_id=? AND product_id=?", business, product) > 0))
                conflict("Tracking mode is locked once stock/sale history exists; create a distinct SKU for a different tracking mode");
            if (db.number("SELECT coalesce(sum(quantity_on_hand+quantity_reserved),0) FROM inventory WHERE business_id=? AND product_id=?", business, product) != 0)
                conflict("Change tracking only when all location stock and reservations are zero");
            if (!tenant.actor().hasAllBranches())
                throw new org.springframework.security.access.AccessDeniedException("All-branch access required to change product tracking");
            db.update("UPDATE products SET batch_tracked=? WHERE id=? AND business_id=?", r.enabled() ? 1 : 0, product, business);
            commands.save(business, r.requestId(), command, product);
            audit.log("BATCH_TRACKING_CHANGED", "PRODUCT", product, null, r);
        }
        return db.one("SELECT id,batch_tracked FROM products WHERE id=? AND business_id=?", product, business);
    }

    @Transactional
    public Map<String, Object> countBatch(String id, InventoryDtos.BatchCount r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var batch = db.one("SELECT * FROM stock_batches WHERE id=? AND business_id=?", id, business);
        int location = tenancy.locationId(integer(batch, "location_id"));
        String product = string(batch, "product_id"), command = "BATCH_COUNT|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null)
            return db.one("SELECT * FROM stock_batches WHERE id=? AND business_id=?", id, business);
        if (integer(batch, "quantity_on_hand") != r.expectedQuantity())
            conflict("Batch stock changed; recount before approving");
        int delta = r.countedQuantity() - r.expectedQuantity();
        ledger.changeValued(business, location, product, delta, 0, "COUNT", "BATCH_COUNT_APPROVAL", r.reason(), tenant.actor().getId(), "EMPLOYEE", r.requestId(), null, string(batch, "code"), batch.get("expires_on") == null ? null : LocalDate.parse(string(batch, "expires_on")));
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("BATCH_COUNT_APPROVED", "BATCH", id, batch, r, location);
        return db.one("SELECT * FROM stock_batches WHERE id=? AND business_id=?", id, business);
    }

    public List<Map<String, Object>> batches(int location, boolean expired, int days, int limit, int offset) {
        tenant.requireLocation(location);
        return db.rows("SELECT *,quantity_on_hand-quantity_reserved AS available FROM stock_batches WHERE business_id=? AND location_id=? AND quantity_on_hand>0 AND (?=0 OR expires_on<=?) ORDER BY coalesce(expires_on,'9999-12-31'),id LIMIT ? OFFSET ?",
                tenant.businessId(), location, expired ? 1 : 0, LocalDate.now(clock).plusDays(days).toString(), limit, offset);
    }

    static void bad(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    static void conflict(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
}
