package org.osyntra.osyntrainventorymanagement.tax;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class OnboardingService {
    private static final List<String> STEPS = List.of("BUSINESS_DETAILS", "BRANCHES", "TAX_REVIEW", "CATALOG", "OPENING_STOCK", "STAFF", "POS_TEST");
    private final MvpStore db;
    private final TenantContext tenant;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public OnboardingService(MvpStore db, TenantContext tenant, SettingsService settings, AuditService audit, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Map<String, Object>> get() {
        settings.manageBusiness();
        int business = tenant.businessId();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String step : STEPS) {
            var rows = db.rows("SELECT * FROM onboarding_steps WHERE business_id=? AND step=?", business, step);
            Map<String, Object> item = rows.isEmpty() ? new LinkedHashMap<>(Map.of("step", step, "status", "NOT_STARTED", "notes", "", "revision", 0)) : rows.getFirst();
            boolean ready = ready(business, step);
            item.put("ready", ready);
            item.put("needsReview", item.get("status").equals("COMPLETE") && !ready);
            result.add(item);
        }
        return result;
    }

    private boolean ready(int business, String step) {
        return switch (step) {
            case "BUSINESS_DETAILS" ->
                    db.number("SELECT count(*) FROM businesses WHERE id=? AND length(trim(name))>0 AND length(currency)=3", business) > 0;
            case "BRANCHES" ->
                    db.number("SELECT count(*) FROM locations WHERE business_id=? AND active=1", business) > 0;
            case "TAX_REVIEW" -> {
                var p = db.one("SELECT registration_status,verified FROM tax_policy_versions WHERE business_id=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", business, clock.millis());
                boolean reviewed = integer(p, "verified") == 1;
                if (reviewed && p.get("registration_status").equals("REGISTERED"))
                    reviewed = db.number("""
                            SELECT count(*) FROM products p WHERE p.business_id=? AND p.active=1 AND p.product_kind<>'PARENT' AND NOT EXISTS(
                             SELECT 1 FROM product_tax_versions a JOIN tax_rate_versions r ON r.business_id=a.business_id AND r.code=a.code
                             WHERE a.business_id=p.business_id AND a.product_id=p.id
                             AND a.effective_from_ms=(SELECT max(a2.effective_from_ms) FROM product_tax_versions a2 WHERE a2.product_id=p.id AND a2.effective_from_ms<=?)
                             AND r.effective_from_ms=(SELECT max(r2.effective_from_ms) FROM tax_rate_versions r2 WHERE r2.business_id=a.business_id AND r2.code=a.code AND r2.effective_from_ms<=?)
                             AND r.verified=1)
                            """, business, clock.millis(), clock.millis()) == 0;
                yield reviewed;
            }
            case "CATALOG" ->
                    db.number("SELECT count(*) FROM products WHERE business_id=? AND active=1 AND product_kind<>'PARENT'", business) > 0;
            case "OPENING_STOCK" ->
                    db.number("SELECT count(*) FROM stock_movements WHERE business_id=? AND movement_type IN('OPENING','RECEIPT','COUNT')", business) > 0;
            case "STAFF" ->
                    db.number("SELECT count(*) FROM memberships WHERE business_id=? AND status='ACTIVE' AND role<>'OWNER'", business) > 0;
            case "POS_TEST" ->
                    db.number("SELECT count(*) FROM orders o JOIN payment_records p ON p.order_id=o.id WHERE o.business_id=? AND o.channel='POS' AND p.successful=1", business) > 0;
            default -> false;
        };
    }

    @Transactional
    public Map<String, Object> update(String step, TaxDtos.Onboarding r) {
        settings.manageBusiness();
        if (!STEPS.contains(step))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Onboarding step not found");
        int business = tenant.businessId();
        if (r.status().equals("COMPLETE") && !ready(business, step))
            TaxService.conflict("Complete the server-side setup check before marking this step complete");
        var rows = db.rows("SELECT * FROM onboarding_steps WHERE business_id=? AND step=?", business, step);
        if (rows.isEmpty()) {
            if (r.revision() != 0) SettingsService.stale();
            db.update("INSERT INTO onboarding_steps VALUES(?,?,?,?,1,?,?)", business, step, r.status(), r.notes(), tenant.actor().getId(), clock.millis());
        } else if (db.update("UPDATE onboarding_steps SET status=?,notes=?,revision=revision+1,updated_by=?,updated_ms=? WHERE business_id=? AND step=? AND revision=?",
                r.status(), r.notes(), tenant.actor().getId(), clock.millis(), business, step, r.revision()) != 1)
            SettingsService.stale();
        audit.log("ONBOARDING_STEP_UPDATED", "ONBOARDING", step, rows.isEmpty() ? null : rows.getFirst(), r);
        return db.one("SELECT * FROM onboarding_steps WHERE business_id=? AND step=?", business, step);
    }
}
