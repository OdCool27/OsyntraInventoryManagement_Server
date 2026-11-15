package org.osyntra.osyntrainventorymanagement.tax;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class SettingsService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final AuditService audit;
    private final java.time.Clock clock;

    public SettingsService(MvpStore db, TenantContext tenant, AuditService audit, java.time.Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.audit = audit;
        this.clock = clock;
    }

    public void manageBusiness() {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        if (!tenant.actor().hasAllBranches())
            throw new org.springframework.security.access.AccessDeniedException("All-branch access required for business-wide configuration");
    }

    public Map<String, Object> get() {
        return db.one("SELECT * FROM business_preferences WHERE business_id=?", tenant.businessId());
    }

    @Transactional
    public Map<String, Object> update(TaxDtos.Preferences r) {
        manageBusiness();
        int business = tenant.businessId();
        var before = get();
        if (db.update("UPDATE business_preferences SET receipt_name=?,receipt_footer=?,low_stock_notifications=?,allow_branch_price_mode=?,revision=revision+1 WHERE business_id=? AND revision=?",
                r.receiptName().trim(), r.receiptFooter().trim(), r.lowStockNotifications() ? 1 : 0, r.allowBranchPriceMode() ? 1 : 0, business, r.revision()) != 1)
            stale();
        audit.log("PREFERENCES_UPDATED", "BUSINESS", business, before, r);
        return get();
    }

    public Map<String, Object> branch(int location) {
        tenant.requireLocation(location);
        var result = new LinkedHashMap<String, Object>();
        var effective = effective(tenant.businessId(), location);
        if (effective.get("price_mode") == null)
            effective.put("price_mode", db.one("SELECT price_mode FROM tax_policy_versions WHERE business_id=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", tenant.businessId(), clock.millis()).get("price_mode"));
        result.put("effective", effective);
        var overrides = db.rows("SELECT * FROM branch_preferences WHERE business_id=? AND location_id=?", tenant.businessId(), location);
        result.put("overrides", overrides.isEmpty() ? Map.of("revision", 0) : overrides.getFirst());
        return result;
    }

    @Transactional
    public Map<String, Object> branch(int location, TaxDtos.BranchPreferences r) {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        tenant.requireLocation(location);
        int business = tenant.businessId();
        var preferences = get();
        if (r.priceMode() != null && integer(preferences, "allow_branch_price_mode") == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Business settings must explicitly allow branch price-mode overrides");
        var before = branch(location);
        if (r.revision() == 0) {
            if (db.number("SELECT count(*) FROM branch_preferences WHERE location_id=? AND business_id=?", location, business) > 0)
                stale();
            db.update("INSERT INTO branch_preferences(location_id,business_id,receipt_name,receipt_footer,low_stock_notifications,price_mode) VALUES(?,?,?,?,?,?)",
                    location, business, r.receiptName(), r.receiptFooter(), r.lowStockNotifications() == null ? null : r.lowStockNotifications() ? 1 : 0, r.priceMode());
        } else if (db.update("UPDATE branch_preferences SET receipt_name=?,receipt_footer=?,low_stock_notifications=?,price_mode=?,revision=revision+1 WHERE location_id=? AND business_id=? AND revision=?",
                r.receiptName(), r.receiptFooter(), r.lowStockNotifications() == null ? null : r.lowStockNotifications() ? 1 : 0, r.priceMode(), location, business, r.revision()) != 1)
            stale();
        audit.logLocation("BRANCH_PREFERENCES_UPDATED", "LOCATION", location, before, r, location);
        return branch(location);
    }

    public Map<String, Object> effective(int business, int location) {
        db.one("SELECT id FROM locations WHERE business_id=? AND id=?", business, location);
        var global = db.one("SELECT * FROM business_preferences WHERE business_id=?", business);
        var result = new LinkedHashMap<>(global);
        result.put("branch_revision", 0);
        result.put("price_mode", null);
        var overrides = db.rows("SELECT * FROM branch_preferences WHERE business_id=? AND location_id=?", business, location);
        if (!overrides.isEmpty()) {
            var local = overrides.getFirst();
            result.put("branch_revision", local.get("revision"));
            for (String key : List.of("receipt_name", "receipt_footer", "low_stock_notifications"))
                if (local.get(key) != null) result.put(key, local.get(key));
            if (integer(global, "allow_branch_price_mode") == 1) result.put("price_mode", local.get("price_mode"));
        }
        if (result.get("receipt_name").toString().isBlank())
            result.put("receipt_name", db.one("SELECT name FROM businesses WHERE id=?", business).get("name"));
        return result;
    }

    static void stale() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Configuration revision changed; reload before saving");
    }
}
