package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.StockDtos.*;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class InventoryService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final StockLedger ledger;
    private final CommandService commands;
    private final AuditService audit;

    public InventoryService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, CommandService commands, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.commands = commands;
        this.audit = audit;
    }

    String availabilitySql() {
        // Only the server clock's ISO date enters this SQL fragment; all resource inputs remain bound parameters.
        return "CASE WHEN p.batch_tracked=1 THEN (SELECT coalesce(sum(b.quantity_on_hand-b.quantity_reserved),0) FROM stock_batches b WHERE b.business_id=i.business_id AND b.location_id=i.location_id AND b.product_id=i.product_id AND (b.expires_on IS NULL OR b.expires_on>='" + ledger.today() + "')) ELSE i.quantity_on_hand-i.quantity_reserved END";
    }

    public Map<String, Object> get(UUID product, Integer location) {
        return get(tenant.businessId(), tenancy.locationId(location), product.toString());
    }

    public Map<String, Object> get(int business, int location, String product) {
        return db.one("""
                SELECT i.*,p.sku,p.item_name,i.quantity_on_hand-i.quantity_reserved AS available_quantity,
                 (i.quantity_on_hand-i.quantity_reserved<=i.reorder_level) AS low_stock
                FROM inventory i JOIN products p ON p.id=i.product_id AND p.business_id=i.business_id
                WHERE i.business_id=? AND i.location_id=? AND i.product_id=?
                """.replace("i.quantity_on_hand-i.quantity_reserved", availabilitySql()), business, location, product);
    }

    public List<Map<String, Object>> list(Integer location, String search, String category, boolean low, boolean out, int limit, int offset) {
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        return db.rows("""
                        SELECT i.*,p.sku,p.item_name,p.unit_price,p.cost,
                         i.quantity_on_hand-i.quantity_reserved AS available_quantity,
                         (i.quantity_on_hand-i.quantity_reserved<=i.reorder_level) AS low_stock
                        FROM inventory i JOIN products p ON p.id=i.product_id AND p.business_id=i.business_id
                        LEFT JOIN categories c ON c.id=p.category_id AND c.business_id=p.business_id
                        WHERE i.business_id=? AND i.location_id=? AND p.active=1
                         AND (lower(p.item_name) LIKE lower(?) OR lower(p.sku) LIKE lower(?))
                         AND (? IS NULL OR lower(c.name)=lower(?))
                         AND (?=0 OR i.quantity_on_hand-i.quantity_reserved<=i.reorder_level)
                         AND (?=0 OR i.quantity_on_hand-i.quantity_reserved=0)
                        ORDER BY p.item_name,p.id LIMIT ? OFFSET ?
                        """.replace("i.quantity_on_hand-i.quantity_reserved", availabilitySql()), business, place, "%" + Objects.toString(search, "") + "%", "%" + Objects.toString(search, "") + "%",
                category, category, low ? 1 : 0, out ? 1 : 0, limit, offset);
    }

    @Transactional
    public Map<String, Object> open(UUID product, Integer location, Opening r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        String command = "OPEN|" + product + "|" + place + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return get(product, place);
        reason(r.reason());
        ledger.activeProduct(business, product.toString());
        db.update("INSERT INTO inventory(product_id,business_id,location_id,reorder_level) VALUES(?,?,?,?)",
                product.toString(), business, place, r.reorderLevel() == null ? ledger.threshold(business, product.toString()) : r.reorderLevel());
        ledger.change(business, place, product.toString(), r.quantityOnHand(), 0, "OPENING", r.reason(), r.notes(), tenant.actor().getId(), "EMPLOYEE", r.requestId());
        commands.save(business, r.requestId(), command, product);
        audit.logLocation("OPENING_COUNT", "INVENTORY", product, null, r, place);
        return get(product, place);
    }

    @Transactional
    public Map<String, Object> move(UUID product, Integer location, Movement r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        String command = "MOVE|" + product + "|" + place + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return get(product, place);
        if (r.quantity() == 0 || (!r.type().equals("CORRECTION") && r.quantity() < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipts/damage need a positive quantity; corrections need a nonzero signed quantity");
        ledger.activeProduct(business, product.toString());
        reason(r.reason());
        long delta = r.type().equals("DAMAGE") ? -(long) r.quantity() : r.quantity();
        ledger.change(business, place, product.toString(), delta, 0, r.type(), r.reason(), r.notes(), tenant.actor().getId(), "EMPLOYEE", r.requestId());
        commands.save(business, r.requestId(), command, product);
        audit.logLocation("STOCK_" + r.type(), "INVENTORY", product, null, r, place);
        return get(product, place);
    }

    @Transactional
    public Map<String, Object> threshold(UUID product, Integer location, int level) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        ledger.activeProduct(business, product.toString());
        var old = get(product, place);
        db.update("UPDATE products SET min_stock_threshold=? WHERE business_id=? AND id=?", level, business, product.toString());
        db.update("UPDATE inventory SET reorder_level=? WHERE business_id=? AND product_id=?", level, business, product.toString());
        audit.logLocation("REORDER_LEVEL_CHANGED", "INVENTORY", product, old.get("reorder_level"), level, place);
        return get(product, place);
    }

    public List<Map<String, Object>> movements(Integer location, UUID product, int limit, int offset) {
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        return db.rows("SELECT * FROM stock_movements WHERE business_id=? AND location_id=? AND (? IS NULL OR product_id=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                business, place, product == null ? null : product.toString(), product == null ? null : product.toString(), limit, offset);
    }

    public void reason(String reason) {
        db.one("SELECT code FROM movement_reasons WHERE business_id=? AND code=?", tenant.businessId(), reason);
    }

    public List<Map<String, Object>> reasons() {
        return db.rows("SELECT code,label FROM movement_reasons WHERE business_id=? ORDER BY code", tenant.businessId());
    }

    @Transactional
    public void reason(Reason r) {
        tenant.requireRole("OWNER", "MANAGER");
        db.update("INSERT INTO movement_reasons(business_id,code,label) VALUES(?,?,?)", tenant.businessId(), r.code(), r.label());
        audit.log("REASON_CREATED", "REASON", r.code(), null, r.label());
    }
}
