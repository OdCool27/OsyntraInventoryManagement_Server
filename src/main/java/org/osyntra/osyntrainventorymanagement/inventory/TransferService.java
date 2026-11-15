package org.osyntra.osyntrainventorymanagement.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.time.*;
import java.math.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class TransferService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final StockLedger ledger;
    private final CommandService commands;
    private final AuditService audit;
    private final Clock clock;
    private final BatchService batches;

    public TransferService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, CommandService commands, AuditService audit, Clock clock, BatchService batches) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
        this.batches = batches;
    }

    public List<Map<String, Object>> list(int limit, int offset) {
        tenant.requireRole("OWNER", "MANAGER");
        return db.rows("SELECT * FROM stock_transfers WHERE business_id=?" + tenant.locationPredicate("source_id") + tenant.locationPredicate("destination_id") + " ORDER BY created_ms DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public Map<String, Object> get(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var transfer = db.one("SELECT * FROM stock_transfers WHERE id=? AND business_id=?", id, business);
        tenant.requireLocation(integer(transfer, "source_id"));
        tenant.requireLocation(integer(transfer, "destination_id"));
        var lines = db.rows("SELECT * FROM stock_transfer_lines WHERE transfer_id=? AND business_id=? ORDER BY product_id", id, business);
        for (var line : lines) {
            line.put("in_transit_quantity", Set.of("DISPATCHED", "PARTIAL").contains(transfer.get("status")) ? integer(line, "quantity") - integer(line, "received_quantity") : 0);
            line.put("in_transit_value", new BigDecimal(line.get("dispatched_value").toString()).subtract(new BigDecimal(line.get("received_value").toString())).toPlainString());
        }
        transfer.put("lines", lines);
        return transfer;
    }

    @Transactional
    public Map<String, Object> request(InventoryDtos.Transfer r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int source = tenancy.locationId(r.sourceId()), destination = tenancy.locationId(r.destinationId());
        if (source == destination) PurchasingService.bad("Transfer branches must differ");
        String command = "TRANSFER|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return get(prior);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO stock_transfers VALUES(?,?,?,?,'REQUESTED',?,?)", id, business, source, destination, tenant.actor().getId(), clock.millis());
        Set<UUID> seen = new HashSet<>();
        for (var line : r.lines()) {
            if (!seen.add(line.productId())) PurchasingService.bad("Transfer products must be unique");
            String product = line.productId().toString();
            ledger.activeProduct(business, product);
            String expiry = null;
            if (batches.tracked(business, product)) {
                if (line.batchCode() == null)
                    PurchasingService.bad("Tracked transfers require one explicit batch per product");
                expiry = string(db.one("SELECT expires_on FROM stock_batches WHERE business_id=? AND location_id=? AND product_id=? AND code=?", business, source, product, line.batchCode()), "expires_on");
            }
            db.update("INSERT INTO stock_transfer_lines(transfer_id,business_id,product_id,quantity,batch_code,expires_on) VALUES(?,?,?,?,?,?)", id, business, product, line.quantity(), line.batchCode(), expiry);
        }
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("TRANSFER_REQUESTED", "TRANSFER", id, null, r, source);
        return get(id);
    }

    @Transactional
    public Map<String, Object> dispatch(String id, InventoryDtos.Command r) {
        var transfer = get(id);
        int business = tenant.businessId(), source = tenancy.locationId(integer(transfer, "source_id"));
        String command = "TRANSFER_DISPATCH|" + id;
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (!transfer.get("status").equals("REQUESTED"))
            PurchasingService.conflict("Only a requested transfer may be dispatched");
        for (var line : db.rows("SELECT * FROM stock_transfer_lines WHERE transfer_id=? AND business_id=?", id, business)) {
            var changed = ledger.changeValued(business, source, string(line, "product_id"), -integer(line, "quantity"), 0, "TRANSFER_OUT", "TRANSFER_DISPATCH", null, tenant.actor().getId(), "EMPLOYEE", id, null, string(line, "batch_code"), line.get("expires_on") == null ? null : LocalDate.parse(string(line, "expires_on")));
            db.update("UPDATE stock_transfer_lines SET dispatched_value=? WHERE transfer_id=? AND product_id=?", new BigDecimal(changed.get("value_delta").toString()).negate().toPlainString(), id, line.get("product_id"));
        }
        db.update("UPDATE stock_transfers SET status='DISPATCHED' WHERE id=?", id);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("TRANSFER_DISPATCHED", "TRANSFER", id, null, r, source);
        return get(id);
    }

    @Transactional
    public Map<String, Object> receive(String id, InventoryDtos.TransferReceipt r) {
        var transfer = get(id);
        int business = tenant.businessId(), destination = tenancy.locationId(integer(transfer, "destination_id"));
        String command = "TRANSFER_RECEIVE|" + id + "|" + new TreeMap<>(r.quantities());
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (!Set.of("DISPATCHED", "PARTIAL").contains(transfer.get("status")))
            PurchasingService.conflict("Transfer is not in transit");
        if (r.quantities().isEmpty()) PurchasingService.bad("Select at least one transfer product");
        for (var entry : r.quantities().entrySet()) {
            var line = db.one("SELECT * FROM stock_transfer_lines WHERE transfer_id=? AND business_id=? AND product_id=?", id, business, entry.getKey().toString());
            int remaining = integer(line, "quantity") - integer(line, "received_quantity"), qty = entry.getValue();
            if (qty > remaining) PurchasingService.conflict("Receipt exceeds in-transit quantity");
            BigDecimal value = qty == remaining ? new BigDecimal(line.get("dispatched_value").toString()).subtract(new BigDecimal(line.get("received_value").toString())) : new BigDecimal(line.get("dispatched_value").toString()).multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(integer(line, "quantity")), 6, RoundingMode.HALF_UP);
            ledger.ensure(business, destination, entry.getKey().toString());
            ledger.changeValued(business, destination, entry.getKey().toString(), qty, 0, "TRANSFER_IN", "TRANSFER_RECEIVED", null, tenant.actor().getId(), "EMPLOYEE", id, value, string(line, "batch_code"), line.get("expires_on") == null ? null : LocalDate.parse(string(line, "expires_on")));
            db.update("UPDATE stock_transfer_lines SET received_quantity=received_quantity+?,received_value=? WHERE transfer_id=? AND product_id=?", qty, new BigDecimal(line.get("received_value").toString()).add(value).toPlainString(), id, entry.getKey().toString());
        }
        boolean done = db.number("SELECT count(*) FROM stock_transfer_lines WHERE transfer_id=? AND received_quantity<quantity", id) == 0;
        db.update("UPDATE stock_transfers SET status=? WHERE id=?", done ? "RECEIVED" : "PARTIAL", id);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("TRANSFER_RECEIVED", "TRANSFER", id, null, r, destination);
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id, InventoryDtos.Command r) {
        var transfer = get(id);
        int business = tenant.businessId();
        String command = "TRANSFER_CANCEL|" + id;
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (!transfer.get("status").equals("REQUESTED"))
            PurchasingService.conflict("Dispatched transfers must be received/reconciled, not silently cancelled");
        db.update("UPDATE stock_transfers SET status='CANCELLED' WHERE id=?", id);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("TRANSFER_CANCELLED", "TRANSFER", id, null, r, integer(transfer, "source_id"));
        return get(id);
    }
}
