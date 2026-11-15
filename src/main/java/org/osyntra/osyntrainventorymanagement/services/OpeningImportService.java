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
public class OpeningImportService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final InventoryService stock;
    private final AuditService audit;

    public OpeningImportService(MvpStore db, TenantContext tenant, TenancyService tenancy, InventoryService stock, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.stock = stock;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> preview(CsvPreview r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int location = tenancy.locationId(r.locationId());
        var rows = CsvSupport.parse(r.csv().replaceFirst("^\uFEFF", ""));
        if (rows.isEmpty() || !rows.getFirst().equals(List.of("sku", "quantity", "reorderLevel")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV header must be sku,quantity,reorderLevel");
        if (rows.size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV needs at least one data row");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO opening_imports(id,business_id,location_id,created_by,status) VALUES(?,?,?,?,'PREVIEW')", id, business, location, tenant.actor().getId());
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            var row = rows.get(i);
            String sku = row.getFirst().trim();
            String error = null;
            Integer quantity = null, level = null;
            try {
                if (row.size() != 3) throw new IllegalArgumentException("Expected three columns");
                if (sku.isEmpty() || !seen.add(sku)) throw new IllegalArgumentException("Missing or duplicate SKU");
                quantity = nonnegative(row.get(1));
                level = nonnegative(row.get(2));
                var products = db.rows("SELECT id FROM products WHERE business_id=? AND sku=? AND active=1", business, sku);
                if (products.isEmpty()) throw new IllegalArgumentException("Unknown active SKU");
                if (db.number("SELECT count(*) FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, products.getFirst().get("id")) > 0)
                    throw new IllegalArgumentException("Stock already initialized; use a count session");
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
            db.update("INSERT INTO opening_import_lines(import_id,business_id,row_number,sku,quantity,reorder_level,error) VALUES(?,?,?,?,?,?,?)", id, business, i + 1, sku, quantity, level, error);
        }
        audit.logLocation("IMPORT_PREVIEWED", "IMPORT", id, null, rows.size() - 1, location);
        return get(id);
    }

    public Map<String, Object> get(String id) {
        var result = new LinkedHashMap<>(db.one("SELECT * FROM opening_imports WHERE id=? AND business_id=?", id, tenant.businessId()));
        tenant.requireLocation(integer(result, "location_id"));
        result.put("lines", db.rows("SELECT row_number,sku,quantity,reorder_level,error FROM opening_import_lines WHERE import_id=? AND business_id=? ORDER BY row_number", id, tenant.businessId()));
        return result;
    }

    @Transactional
    public Map<String, Object> commit(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var header = db.one("SELECT * FROM opening_imports WHERE id=? AND business_id=?", id, business);
        tenant.requireLocation(integer(header, "location_id"));
        if (header.get("status").equals("COMMITTED")) return get(id);
        if (db.number("SELECT count(*) FROM opening_import_lines WHERE import_id=? AND business_id=? AND error IS NOT NULL", id, business) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Fix CSV errors in a new preview before committing");
        for (var line : db.rows("SELECT * FROM opening_import_lines WHERE import_id=? AND business_id=? ORDER BY row_number", id, business)) {
            var product = db.one("SELECT id FROM products WHERE business_id=? AND sku=? AND active=1", business, line.get("sku"));
            stock.open(UUID.fromString(string(product, "id")), integer(header, "location_id"), new Opening(integer(line, "quantity"), integer(line, "reorder_level"), "OPENING_COUNT", "CSV " + id, "import:" + id + ":" + line.get("row_number")));
        }
        db.update("UPDATE opening_imports SET status='COMMITTED' WHERE id=? AND business_id=?", id, business);
        audit.logLocation("IMPORT_COMMITTED", "IMPORT", id, null, "COMMITTED", integer(header, "location_id"));
        return get(id);
    }

    private int nonnegative(String text) {
        if (!text.trim().matches("[0-9]{1,10}"))
            throw new IllegalArgumentException("Quantity and threshold must be nonnegative whole numbers");
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Quantity exceeds integer range");
        }
    }
}
