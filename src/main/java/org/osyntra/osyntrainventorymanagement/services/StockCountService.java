package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.StockDtos.*;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class StockCountService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final StockLedger ledger;
    private final AuditService audit;

    public StockCountService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> start(CountStart r) {
        tenant.requireRole("OWNER", "MANAGER", "CLERK");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO stock_counts(id,business_id,location_id,status,created_by) VALUES(?,?,?,'DRAFT',?)", id, tenant.businessId(), tenancy.locationId(r.locationId()), tenant.actor().getId());
        audit.log("COUNT_STARTED", "COUNT", id, null, r);
        return get(id);
    }

    public List<Map<String, Object>> list() {
        return db.rows("SELECT * FROM stock_counts WHERE business_id=? ORDER BY created_at DESC LIMIT 200", tenant.businessId());
    }

    public Map<String, Object> get(String id) {
        var count = header(id);
        var result = new LinkedHashMap<>(count);
        result.put("lines", db.rows("""
                SELECT l.product_id,p.sku,p.barcode,l.expected_quantity,l.counted_quantity,l.counted_quantity-l.expected_quantity AS variance
                FROM stock_count_lines l JOIN products p ON p.id=l.product_id AND p.business_id=l.business_id
                WHERE l.count_id=? AND l.business_id=? ORDER BY p.sku
                """, id, tenant.businessId()));
        return result;
    }

    private Map<String, Object> header(String id) {
        return db.one("SELECT * FROM stock_counts WHERE id=? AND business_id=?", id, tenant.businessId());
    }

    private void editable(Map<String, Object> count) {
        tenant.requireRole("OWNER", "MANAGER", "CLERK");
        if (!count.get("status").equals("DRAFT")) conflict("Only draft counts can be changed");
    }

    @Transactional
    public Map<String, Object> line(String id, CountLine r) {
        var count = header(id);
        editable(count);
        int business = tenant.businessId();
        int location = integer(count, "location_id");
        var product = db.one("SELECT id FROM products WHERE business_id=? AND active=1 AND (sku=? OR barcode=?)", business, r.code().trim(), r.code().trim());
        String productId = string(product, "id");
        var balances = db.rows("SELECT quantity_on_hand FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, productId);
        int expected = balances.isEmpty() ? 0 : integer(balances.getFirst(), "quantity_on_hand");
        db.update("""
                INSERT INTO stock_count_lines(count_id,business_id,product_id,expected_quantity,counted_quantity) VALUES(?,?,?,?,?)
                ON CONFLICT(count_id,product_id) DO UPDATE SET expected_quantity=excluded.expected_quantity,counted_quantity=excluded.counted_quantity
                """, id, business, productId, expected, r.quantity());
        audit.log("COUNT_LINE_SAVED", "COUNT", id, null, r);
        return get(id);
    }

    @Transactional
    public Map<String, Object> submit(String id) {
        var count = header(id);
        editable(count);
        if (db.number("SELECT count(*) FROM stock_count_lines WHERE count_id=? AND business_id=?", id, tenant.businessId()) == 0)
            conflict("Count has no lines");
        db.update("UPDATE stock_counts SET status='SUBMITTED' WHERE id=? AND business_id=?", id, tenant.businessId());
        audit.log("COUNT_SUBMITTED", "COUNT", id, null, "SUBMITTED");
        return get(id);
    }

    @Transactional
    public Map<String, Object> reopen(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        var count = header(id);
        if (!count.get("status").equals("SUBMITTED")) conflict("Only submitted counts can be reopened");
        db.update("UPDATE stock_counts SET status='DRAFT' WHERE id=? AND business_id=?", id, tenant.businessId());
        audit.log("COUNT_REOPENED", "COUNT", id, null, "DRAFT");
        return get(id);
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        var count = header(id);
        if (count.get("status").equals("APPROVED")) return get(id);
        if (!count.get("status").equals("SUBMITTED")) conflict("Submit the count before approval");
        int business = tenant.businessId();
        int location = integer(count, "location_id");
        for (var line : db.rows("SELECT * FROM stock_count_lines WHERE count_id=? AND business_id=? ORDER BY product_id", id, business)) {
            String product = string(line, "product_id");
            ledger.activeProduct(business, product);
            var balances = db.rows("SELECT quantity_on_hand FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, product);
            int expected = integer(line, "expected_quantity");
            int current = balances.isEmpty() ? 0 : integer(balances.getFirst(), "quantity_on_hand");
            // Stock stays sellable during counting. A changed balance needs review, never a silent overwrite.
            if (current != expected) conflict("Stock changed during counting; reopen, recount and resubmit");
            if (balances.isEmpty())
                db.update("INSERT INTO inventory(product_id,business_id,location_id,reorder_level) VALUES(?,?,?,?)", product, business, location, ledger.threshold(business, product));
            ledger.change(business, location, product, (long) integer(line, "counted_quantity") - current, 0, "COUNT", "COUNT_APPROVAL", null, tenant.actor().getId(), "EMPLOYEE", id);
        }
        db.update("UPDATE stock_counts SET status='APPROVED',approved_by=? WHERE id=? AND business_id=?", tenant.actor().getId(), id, business);
        audit.log("COUNT_APPROVED", "COUNT", id, null, "APPROVED");
        return get(id);
    }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
