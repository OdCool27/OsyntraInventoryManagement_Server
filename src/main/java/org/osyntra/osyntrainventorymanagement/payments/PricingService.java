package org.osyntra.osyntrainventorymanagement.payments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.time.*;
import java.math.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class PricingService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final CommandService commands;
    private final AuditService audit;
    private final Clock clock;
    private final StockLedger ledger;

    public PricingService(MvpStore db, TenantContext tenant, CommandService commands, AuditService audit, Clock clock, StockLedger ledger) {
        this.db = db;
        this.tenant = tenant;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
        this.ledger = ledger;
    }

    @Transactional
    public Map<String, Object> price(CheckoutDtos.Price r) {
        tenant.requireRole("OWNER", "MANAGER");
        tenant.requireLocation(r.locationId());
        int business = tenant.businessId();
        db.one("SELECT id FROM products WHERE business_id=? AND id=? AND active=1 AND product_kind<>'PARENT'", business, r.productId().toString());
        if (r.effectiveFrom().isBefore(Instant.EPOCH) || r.effectiveFrom().isAfter(Instant.parse("9999-12-31T23:59:59Z")))
            bad("Invalid effective date");
        String command = "BRANCH_PRICE|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null)
            return db.one("SELECT * FROM branch_price_versions WHERE id=? AND business_id=?", prior, business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO branch_price_versions VALUES(?,?,?,?,?,?,?)", id, business, r.locationId(), r.productId().toString(), r.price().movePointRight(2).longValueExact(), r.effectiveFrom().toEpochMilli(), tenant.actor().getId());
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("BRANCH_PRICE_CREATED", "PRODUCT", r.productId(), null, r, r.locationId());
        return db.one("SELECT * FROM branch_price_versions WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> promotion(CheckoutDtos.Promotion r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        if (r.locationId() != null) tenant.requireLocation(r.locationId());
        else if (!tenant.actor().hasAllBranches())
            throw new org.springframework.security.access.AccessDeniedException("All-branch access required for a business-wide promotion");
        if (r.productId() != null)
            db.one("SELECT id FROM products WHERE business_id=? AND id=?", business, r.productId().toString());
        if (!r.endsAt().isAfter(r.startsAt()) || r.startsAt().isBefore(Instant.EPOCH) || r.endsAt().isAfter(Instant.parse("9999-12-31T23:59:59Z")))
            bad("Invalid promotion dates");
        String command = "PROMOTION|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null)
            return db.one("SELECT * FROM checkout_promotions WHERE id=? AND business_id=?", prior, business);
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO checkout_promotions VALUES(?,?,?,?,?,?,?,?,?)", id, business, r.locationId(), r.productId() == null ? null : r.productId().toString(), r.name(), r.percent().toPlainString(), r.startsAt().toEpochMilli(), r.endsAt().toEpochMilli(), tenant.actor().getId());
        commands.save(business, r.requestId(), command, id);
        audit.log("PROMOTION_CREATED", "PROMOTION", id, null, r);
        return db.one("SELECT * FROM checkout_promotions WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> revoke(String id, org.osyntra.osyntrainventorymanagement.dtos.CommerceDtos.Action r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var p = db.one("SELECT * FROM checkout_promotions WHERE id=? AND business_id=?", id, business);
        if (p.get("location_id") != null) tenant.requireLocation(integer(p, "location_id"));
        else if (!tenant.actor().hasAllBranches())
            throw new org.springframework.security.access.AccessDeniedException("All-branch access required to revoke a business-wide promotion");
        String command = "PROMOTION_REVOKE|" + id + "|" + r.reason();
        if (commands.replay(business, r.requestId(), command) == null) {
            db.update("INSERT INTO promotion_revocations VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING", id, business, tenant.actor().getId(), r.reason(), clock.millis());
            commands.save(business, r.requestId(), command, id);
            audit.log("PROMOTION_REVOKED", "PROMOTION", id, null, r);
        }
        return Map.of("id", id, "revoked", true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue(int location, String search, String code, int limit, int offset) {
        int business = tenant.businessId();
        tenant.requireLocation(location);
        db.one("SELECT id FROM locations WHERE id=? AND business_id=? AND active=1", location, business);
        var rows = db.rows("SELECT id,sku,barcode,item_name,unit_price,unit_of_measure,batch_tracked FROM products WHERE business_id=? AND active=1 AND product_kind<>'PARENT' AND (? IS NULL OR sku=? OR barcode=?) AND (? IS NULL OR lower(item_name) LIKE lower(?) OR lower(sku) LIKE lower(?)) ORDER BY item_name,id LIMIT ? OFFSET ?",
                business, code, code, code, search, "%" + Objects.toString(search, "") + "%", "%" + Objects.toString(search, "") + "%", limit, offset);
        for (var p : rows) {
            var priced = quote(business, location, string(p, "id"), new BigDecimal(p.remove("unit_price").toString()).movePointRight(2).longValueExact(), null, null, false);
            p.putAll(priced);
            p.put("price_cents", p.remove("price"));
            p.put("available", ledger.sellable(business, location, string(p, "id")));
        }
        return rows;
    }

    public List<Map<String, Object>> prices(int limit, int offset) {
        return db.rows("SELECT * FROM branch_price_versions WHERE business_id=?" + tenant.locationPredicate("location_id") + " ORDER BY effective_from_ms DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public List<Map<String, Object>> promotions(int limit, int offset) {
        int business = tenant.businessId();
        return db.rows("SELECT * FROM checkout_promotions WHERE business_id=? AND (location_id IS NULL OR location_id IN(SELECT id FROM locations WHERE business_id=?" + tenant.locationPredicate("id") + ")) ORDER BY start_ms DESC,id LIMIT ? OFFSET ?", business, business, limit, offset);
    }

    public Map<String, Object> quote(int business, int location, String product, long catalogueCents, BigDecimal manual, String reason, boolean online) {
        if (manual != null && manual.signum() > 0) {
            if (online) bad("Public checkout cannot request manual discounts");
            tenant.requireRole("OWNER", "MANAGER");
            if (reason == null || reason.isBlank()) bad("Manual discounts require a reason");
        }
        long price = catalogueCents;
        String priceId = null, promoId = null;
        BigDecimal percent = manual == null ? BigDecimal.ZERO : manual;
        var overrides = db.rows("SELECT id,price_cents FROM branch_price_versions WHERE business_id=? AND location_id=? AND product_id=? AND effective_from_ms<=? ORDER BY effective_from_ms DESC LIMIT 1", business, location, product, clock.millis());
        if (!overrides.isEmpty()) {
            price = ((Number) overrides.getFirst().get("price_cents")).longValue();
            priceId = string(overrides.getFirst(), "id");
        }
        for (var p : db.rows("SELECT id,percent FROM checkout_promotions WHERE business_id=? AND (location_id IS NULL OR location_id=?) AND (product_id IS NULL OR product_id=?) AND start_ms<=? AND end_ms>? AND NOT EXISTS(SELECT 1 FROM promotion_revocations r WHERE r.promotion_id=checkout_promotions.id) ORDER BY id", business, location, product, clock.millis(), clock.millis())) {
            BigDecimal candidate = new BigDecimal(p.get("percent").toString());
            if (candidate.compareTo(percent) > 0) {
                percent = candidate;
                promoId = string(p, "id");
            }
        }
        long adjusted = BigDecimal.valueOf(price).multiply(new BigDecimal("100").subtract(percent)).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).longValueExact();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", adjusted);
        result.put("list_price_cents", price);
        result.put("discount_percent", percent.toPlainString());
        result.put("price_version_id", priceId);
        result.put("promotion_id", promoId);
        result.put("discount_reason", promoId == null ? Objects.toString(reason, "") : "Automatic promotion " + promoId);
        return result;
    }

    static void bad(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
}
