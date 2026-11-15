package org.osyntra.osyntrainventorymanagement.tax;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class TaxService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final SettingsService settings;
    private final AuditService audit;
    private final CommandService commands;
    private final Clock clock;

    public TaxService(MvpStore db, TenantContext tenant, SettingsService settings, AuditService audit, CommandService commands, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.settings = settings;
        this.audit = audit;
        this.commands = commands;
        this.clock = clock;
    }

    public List<Map<String, Object>> templates() {
        tenant.businessId();
        return db.rows("SELECT * FROM tax_templates ORDER BY code");
    }

    public List<Map<String, Object>> policies(int limit, int offset) {
        settings.manageBusiness();
        return db.rows("SELECT * FROM tax_policy_versions WHERE business_id=? ORDER BY effective_from_ms DESC LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public List<Map<String, Object>> rates(int limit, int offset) {
        settings.manageBusiness();
        return db.rows("SELECT * FROM tax_rate_versions WHERE business_id=? ORDER BY code,effective_from_ms DESC LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    private void verification(boolean verified, String note) {
        if (verified) {
            tenant.requireRole("OWNER");
            if (note.isBlank()) bad("Verification requires an owner review reference/note");
        }
    }

    private long date(Instant instant) {
        if (instant.compareTo(Instant.EPOCH) <= 0 || instant.compareTo(Instant.parse("9999-12-31T23:59:59.999Z")) > 0 || instant.getNano() % 1000000 != 0)
            bad("Effective date must be after the Unix epoch, within year 9999, with at most millisecond precision");
        return instant.toEpochMilli();
    }

    @Transactional
    public Map<String, Object> policy(TaxDtos.Policy r) {
        settings.manageBusiness();
        verification(r.verified(), r.verificationNote());
        int business = tenant.businessId();
        if (r.registrationStatus().equals("REGISTERED") && r.registrationNumber().isBlank())
            bad("Registered seller requires a registration reference");
        String fingerprint = "TAX_POLICY|" + r;
        String prior = commands.replay(business, r.requestId(), fingerprint);
        if (prior != null)
            return db.one("SELECT * FROM tax_policy_versions WHERE id=? AND business_id=?", prior, business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO tax_policy_versions VALUES(?,?,?,?,?,?,?,?,?,?)", id, business, date(r.effectiveFrom()), r.registrationStatus(), r.registrationNumber().trim(), r.priceMode(), r.verified() ? 1 : 0, r.verificationNote().trim(), tenant.actor().getId(), clock.millis());
        audit.log("TAX_POLICY_VERSION_CREATED", "TAX_POLICY", id, null, r);
        commands.save(business, r.requestId(), fingerprint, id);
        return db.one("SELECT * FROM tax_policy_versions WHERE id=? AND business_id=?", id, business);
    }

    @Transactional
    public Map<String, Object> rate(String code, TaxDtos.Rate r) {
        settings.manageBusiness();
        verification(r.verified(), r.verificationNote());
        int business = tenant.businessId();
        if (!r.classification().equals("TAXABLE") && r.ratePercent().signum() != 0)
            bad("Zero-rated/exempt classifications require a zero rate");
        String fingerprint = "TAX_RATE|" + code + "|" + r;
        String prior = commands.replay(business, r.requestId(), fingerprint);
        if (prior != null)
            return db.one("SELECT * FROM tax_rate_versions WHERE id=? AND business_id=?", prior, business);
        db.update("INSERT INTO tax_profiles(business_id,code) VALUES(?,?) ON CONFLICT DO NOTHING", business, code);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO tax_rate_versions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", id, business, code, r.label().trim(), r.classification(), r.ratePercent().stripTrailingZeros().toPlainString(), date(r.effectiveFrom()), r.illustrative() ? 1 : 0, r.verified() ? 1 : 0, r.verificationNote().trim(), tenant.actor().getId(), clock.millis());
        audit.log("TAX_RATE_VERSION_CREATED", "TAX_RATE", id, null, r);
        commands.save(business, r.requestId(), fingerprint, id);
        return db.one("SELECT * FROM tax_rate_versions WHERE id=? AND business_id=?", id, business);
    }

    public List<Map<String, Object>> assignments(String product, int limit, int offset) {
        settings.manageBusiness();
        int business = tenant.businessId();
        db.one("SELECT id FROM products WHERE business_id=? AND id=?", business, product);
        return db.rows("SELECT * FROM product_tax_versions WHERE business_id=? AND product_id=? ORDER BY effective_from_ms DESC LIMIT ? OFFSET ?", business, product, limit, offset);
    }

    @Transactional
    public Map<String, Object> assign(String product, TaxDtos.Assignment r) {
        settings.manageBusiness();
        int business = tenant.businessId();
        db.one("SELECT id FROM products WHERE id=? AND business_id=?", product, business);
        db.one("SELECT code FROM tax_profiles WHERE business_id=? AND code=?", business, r.code());
        String fingerprint = "PRODUCT_TAX|" + product + "|" + r;
        String prior = commands.replay(business, r.requestId(), fingerprint);
        if (prior != null)
            return db.one("SELECT * FROM product_tax_versions WHERE id=? AND business_id=?", prior, business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO product_tax_versions VALUES(?,?,?,?,?,?,?)", id, business, product, r.code(), date(r.effectiveFrom()), tenant.actor().getId(), clock.millis());
        audit.log("PRODUCT_TAX_CLASSIFIED", "PRODUCT", product, null, r);
        commands.save(business, r.requestId(), fingerprint, id);
        return db.one("SELECT * FROM product_tax_versions WHERE id=? AND business_id=?", id, business);
    }

    public record Context(Map<String, Object> policy, Map<String, Object> preferences, String priceMode, Instant at) {
    }

    public Context context(int business, int location) {
        Instant at = clock.instant();
        var policy = db.one("SELECT * FROM tax_policy_versions WHERE business_id=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", business, at.toEpochMilli());
        if (string(policy, "registration_status").equals("REGISTERED") && integer(policy, "verified") == 0)
            conflict("Owner verification of registered seller tax policy is required before checkout");
        var preferences = settings.effective(business, location);
        String mode = preferences.get("price_mode") == null ? string(policy, "price_mode") : string(preferences, "price_mode");
        return new Context(policy, preferences, mode, at);
    }

    public Map<String, Object> line(Context context, int business, String product, long price, int quantity) {
        var policy = context.policy();
        boolean registered = string(policy, "registration_status").equals("REGISTERED");
        var assignments = db.rows("SELECT id,code FROM product_tax_versions WHERE business_id=? AND product_id=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", business, product, context.at().toEpochMilli());
        Map<String, Object> assignment = assignments.isEmpty() ? null : assignments.getFirst(), rate = null;
        if (assignment != null) {
            var versions = db.rows("SELECT * FROM tax_rate_versions WHERE business_id=? AND code=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", business, assignment.get("code"), context.at().toEpochMilli());
            if (!versions.isEmpty()) rate = versions.getFirst();
        }
        if (registered && (rate == null || integer(rate, "verified") == 0))
            conflict("Owner-reviewed, effective product tax classification is required before checkout");
        BigDecimal percent = registered ? new BigDecimal(string(rate, "rate_percent")) : BigDecimal.ZERO;
        TaxCalculator.Amounts amounts;
        try {
            amounts = TaxCalculator.calculate(price, quantity, percent, context.priceMode().equals("INCLUSIVE"));
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tax/line total exceeds supported range");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("net_cents", amounts.net());
        result.put("tax_cents", amounts.tax());
        result.put("total_cents", amounts.total());
        result.put("tax_classification", registered ? rate.get("classification") : "NON_REGISTERED");
        result.put("tax_rate_percent", percent.toPlainString());
        result.put("tax_profile_code", assignment == null ? null : assignment.get("code"));
        result.put("tax_rate_version_id", rate == null ? null : rate.get("id"));
        result.put("product_tax_version_id", assignment == null ? null : assignment.get("id"));
        result.put("tax_rule_label", registered ? rate.get("label") : "Seller configured as nonregistered; no sales GCT collected");
        result.put("tax_verified", registered ? rate.get("verified") : policy.get("verified"));
        result.put("tax_illustrative", rate == null ? 0 : rate.get("illustrative"));
        return result;
    }

    public Map<String, Object> snapshot(Context context, long net, long tax) {
        var p = context.policy();
        var prefs = context.preferences();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("net_cents", net);
        result.put("tax_cents", tax);
        result.put("tax_snapshot_version", 1);
        result.put("tax_policy_id", p.get("id"));
        result.put("seller_registration_status", p.get("registration_status"));
        result.put("seller_registration_number", p.get("registration_number"));
        result.put("seller_tax_verified", p.get("verified"));
        result.put("price_mode", context.priceMode());
        result.put("tax_calculated_at", context.at().toString());
        result.put("receipt_name", prefs.get("receipt_name"));
        result.put("receipt_footer", prefs.get("receipt_footer"));
        result.put("preferences_revision", prefs.get("revision"));
        result.put("branch_preferences_revision", prefs.get("branch_revision"));
        return result;
    }

    static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
