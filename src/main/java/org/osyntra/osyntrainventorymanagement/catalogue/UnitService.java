package org.osyntra.osyntrainventorymanagement.catalogue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.math.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class UnitService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final CommandService commands;
    private final AuditService audit;

    public UnitService(MvpStore db, TenantContext tenant, CommandService commands, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.commands = commands;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(String id) {
        db.one("SELECT id FROM products WHERE business_id=? AND id=?", tenant.businessId(), id);
        return db.rows("SELECT * FROM product_units WHERE business_id=? AND product_id=? ORDER BY code", tenant.businessId(), id);
    }

    @Transactional
    public List<Map<String, Object>> add(String id, CatalogueDtos.Unit r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        db.one("SELECT id FROM products WHERE business_id=? AND id=? AND active=1", business, id);
        String command = "UNIT|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return list(id);
        if (!r.purchaseAllowed() && !r.saleAllowed()) bad("A unit must support purchases or sales");
        db.update("INSERT INTO product_units VALUES(?,?,?,?,?,?)", business, id, r.code(), r.factor().stripTrailingZeros().toPlainString(), r.purchaseAllowed() ? 1 : 0, r.saleAllowed() ? 1 : 0);
        commands.save(business, r.requestId(), command, id);
        audit.log("PRODUCT_UNIT_CREATED", "PRODUCT", id, null, r);
        return list(id);
    }

    public int convert(int business, String product, String code, BigDecimal quantity, String purpose) {
        var unit = db.one("SELECT * FROM product_units WHERE business_id=? AND product_id=? AND code=?", business, product, code);
        if (integer(unit, purpose.equals("PURCHASE") ? "purchase_allowed" : "sale_allowed") == 0)
            bad("Unit is not permitted for this operation");
        try {
            int base = quantity.multiply(new BigDecimal(unit.get("factor").toString())).intValueExact();
            if (base <= 0) bad("Converted quantity must be positive");
            return base;
        } catch (ArithmeticException e) {
            bad("Conversion must yield whole base units within the supported stock range");
            return 0;
        }
    }

    public Map<String, Object> convert(String id, CatalogueDtos.Convert r) {
        return Map.of("baseQuantity", convert(tenant.businessId(), id, r.unit(), r.quantity(), r.purpose()), "productId", id);
    }

    static void bad(String detail) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, detail);
    }
}
