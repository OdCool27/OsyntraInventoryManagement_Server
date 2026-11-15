package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.CommerceDtos.*;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.math.BigDecimal;
import java.security.*;
import java.nio.charset.StandardCharsets;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class CommerceService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final StockLedger ledger;
    private final CommandService commands;
    private final org.osyntra.osyntrainventorymanagement.tax.TaxService tax;
    private final org.osyntra.osyntrainventorymanagement.payments.PricingService pricing;
    private final org.osyntra.osyntrainventorymanagement.catalogue.UnitService units;
    private final org.springframework.core.env.Environment environment;
    @org.springframework.beans.factory.annotation.Value("${orvexa.payments.simulator-enabled:false}")
    private boolean simulatorEnabled;

    public CommerceService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, CommandService commands, org.osyntra.osyntrainventorymanagement.tax.TaxService tax, org.osyntra.osyntrainventorymanagement.payments.PricingService pricing, org.osyntra.osyntrainventorymanagement.catalogue.UnitService units, org.springframework.core.env.Environment environment) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.commands = commands;
        this.tax = tax;
        this.pricing = pricing;
        this.units = units;
        this.environment = environment;
    }

    public List<Map<String, Object>> history(int limit, int offset) {
        return db.rows("SELECT id,location_id,channel,status,total_cents,currency,expires_at_ms,created_at FROM orders WHERE business_id=?" + tenant.locationPredicate("location_id") + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public Map<String, Object> get(String id) {
        return view(authorize(tenant.businessId(), id, null, false));
    }

    public Map<String, Object> publicGet(int business, String id, String token) {
        return view(authorize(business, id, token, true));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> cart(Cart request) {
        return calculateCart(tenant.businessId(), new Cart(tenancy.locationId(request.locationId()), request.lines(), request.discountPercent(), request.discountReason()), false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicCart(int business, Cart request) {
        return calculateCart(business, request, true);
    }

    private Map<String, Object> calculateCart(int business, Cart request, boolean online) {
        String currency = string(db.one("SELECT currency FROM businesses WHERE id=? AND active=1", business), "currency");
        int location = tenancy.locationId(business, request.locationId());
        var taxContext = tax.context(business, location);
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        long total = 0, net = 0, taxTotal = 0;
        boolean sufficient = true;
        for (var line : normalize(business, request.lines())) {
            if (!seen.add(line.productId())) bad("Each product may appear only once");
            var product = db.one("SELECT id,sku,item_name,unit_price FROM products WHERE id=? AND business_id=? AND active=1 AND product_kind<>'PARENT'", line.productId().toString(), business);
            var priced = pricing.quote(business, location, line.productId().toString(), cents(product.remove("unit_price")), request.discountPercent(), request.discountReason(), online);
            long price = ((Number) priced.get("price")).longValue();
            product.putAll(priced);
            long available = ledger.sellable(business, location, line.productId().toString());
            product.put("price_cents", price);
            product.put("quantity", line.quantity());
            var calculated = tax.line(taxContext, business, line.productId().toString(), price, line.quantity());
            product.putAll(calculated);
            product.put("available", available);
            sufficient &= available >= line.quantity();
            lines.add(product);
            try {
                total = Math.addExact(total, ((Number) calculated.get("total_cents")).longValue());
                net = Math.addExact(net, ((Number) calculated.get("net_cents")).longValue());
                taxTotal = Math.addExact(taxTotal, ((Number) calculated.get("tax_cents")).longValue());
            } catch (ArithmeticException exception) {
                bad("Cart total exceeds supported range");
            }
        }
        // Cart previews never allocate stock; checkout performs the authoritative conditional update.
        Map<String, Object> result = new LinkedHashMap<>(tax.snapshot(taxContext, net, taxTotal));
        result.putAll(Map.of("lines", lines, "total_cents", total, "currency", currency, "sufficientStock", sufficient, "reserved", false));
        return result;
    }

    public String receipt(Map<String, Object> order) {
        var saved = db.rows("SELECT body FROM sale_receipts WHERE order_id=? AND business_id=?", order.get("id"), order.get("business_id"));
        return saved.isEmpty() ? renderReceipt(order) : saved.getFirst().get("body").toString();
    }

    private String renderReceipt(Map<String, Object> order) {
        String currency = string(order, "currency");
        StringBuilder text = new StringBuilder(Boolean.TRUE.equals(order.get("simulated")) ? "ORVEXA BY OSYNTRA — SIMULATED DEMO RECEIPT\nNo real funds processed.\n" : "ORVEXA BY OSYNTRA — SALE RECEIPT\nCash/external terminal confirmation recorded by staff.\n");
        text.append(order.getOrDefault("receipt_name", "")).append("\n");
        text.append("Order: ").append(order.get("id")).append("\nDate (UTC): ").append(order.get("created_at"))
                .append("\nChannel: ").append(order.get("channel")).append("\nStatus: ").append(order.get("status")).append("\n\n");
        if (order.get("lines") instanceof List<?> lines) for (Object item : lines) {
            @SuppressWarnings("unchecked") var line = (Map<String, Object>) item;
            text.append(line.get("sku")).append(" — ").append(line.get("item_name")).append("\n")
                    .append(line.get("quantity")).append(" x ").append(currency).append(" ")
                    .append(BigDecimal.valueOf(((Number) line.get("price_cents")).longValue(), 2).toPlainString()).append("\n");
            text.append("Tax classification: ").append(line.get("tax_classification")).append("; rate: ").append(line.get("tax_rate_percent")).append("%\n");
            if (((Number) line.get("tax_illustrative")).intValue() == 1)
                text.append("Illustrative tax profile; verify eligibility with TAJ/accountant.\n");
        }
        text.append("Discount: ").append(currency).append(" ").append(BigDecimal.valueOf(((Number) order.getOrDefault("discount_cents", 0)).longValue(), 2)).append("\n");
        text.append("\nNet: ").append(currency).append(" ").append(BigDecimal.valueOf(((Number) order.get("net_cents")).longValue(), 2)).append("\n");
        text.append("Sales tax: ").append(currency).append(" ").append(BigDecimal.valueOf(((Number) order.get("tax_cents")).longValue(), 2)).append("\n");
        text.append("Tax status: ").append(order.get("seller_registration_status")).append("; reviewed: ").append(order.get("seller_tax_verified")).append("\n");
        if (((Number) order.get("tax_snapshot_version")).intValue() == 0)
            text.append("Legacy order: tax was not reconstructed.\n");
        text.append(order.getOrDefault("receipt_footer", "")).append("\n");
        text.append("\nTotal: ").append(currency).append(" ").append(BigDecimal.valueOf(((Number) order.get("total_cents")).longValue(), 2).toPlainString()).append("\n");
        if (order.get("refunds") instanceof List<?> refunds && !refunds.isEmpty())
            text.append("A simulated refund has been recorded.\n");
        return text.toString();
    }

    @Transactional
    public Map<String, Object> checkout(Checkout r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        return create(tenant.businessId(), r, false, null);
    }

    @Transactional
    public Map<String, Object> publicCheckout(int business, Checkout r, String token) {
        return create(business, r, true, token);
    }

    private Map<String, Object> create(int business, Checkout r, boolean online, String retryToken) {
        db.one("SELECT id FROM businesses WHERE id=? AND active=1", business);
        int location = online ? tenancy.locationId(business, r.locationId()) : tenancy.locationId(r.locationId());
        String channel = online ? "ONLINE" : "POS";
        // Sorting makes repeated requests with the same lines independent of cart display order.
        var lines = normalize(business, r.lines()).stream().sorted(Comparator.comparing(l -> l.productId().toString())).toList();
        String fingerprint = "CHECKOUT|" + channel + "|" + location + "|" + lines + "|" + r.discountPercent() + "|" + r.discountReason() + "|" + r.lines().stream().sorted(Comparator.comparing(l -> l.productId().toString())).toList();
        String legacyLines = lines.stream().map(l -> "Line[productId=" + l.productId() + ", quantity=" + l.quantity() + "]").collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        String legacyFingerprint = (r.discountPercent() == null || r.discountPercent().signum() == 0) && r.lines().stream().allMatch(l -> l.unitCode() == null && l.unitQuantity() == null) ? "CHECKOUT|" + channel + "|" + location + "|" + legacyLines : null;
        String previous = commands.replayCompatible(business, r.requestId(), fingerprint, legacyFingerprint);
        if (previous != null) {
            // Request IDs prevent duplicate writes; they never authorize reading another customer's order.
            if (online) return view(authorize(business, previous, retryToken, true));
            return get(previous);
        }
        expireBusiness(business);
        String id = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = online ? Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes) : null;
        long total = 0, net = 0, taxTotal = 0;
        var taxContext = tax.context(business, location);
        Set<UUID> unique = new HashSet<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (var line : lines) {
            if (!unique.add(line.productId())) bad("Each product may appear only once");
            var p = db.one("SELECT id,sku,item_name,unit_price,cost FROM products WHERE business_id=? AND id=? AND active=1 AND product_kind<>'PARENT'", business, line.productId().toString());
            var priced = pricing.quote(business, location, line.productId().toString(), cents(p.get("unit_price")), r.discountPercent(), r.discountReason(), online);
            long price = ((Number) priced.get("price")).longValue(), cost = ledger.average(business, location, line.productId().toString()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            var calculated = tax.line(taxContext, business, line.productId().toString(), price, line.quantity());
            try {
                total = Math.addExact(total, ((Number) calculated.get("total_cents")).longValue());
                net = Math.addExact(net, ((Number) calculated.get("net_cents")).longValue());
                taxTotal = Math.addExact(taxTotal, ((Number) calculated.get("tax_cents")).longValue());
            } catch (ArithmeticException e) {
                bad("Order total exceeds supported range");
            }
            Map<String, Object> snapshot = new HashMap<>(p);
            snapshot.put("quantity", line.quantity());
            snapshot.put("price", price);
            snapshot.put("cost_snapshot", cost);
            snapshot.putAll(calculated);
            snapshot.putAll(priced);
            snapshots.add(snapshot);
        }
        String currency = string(db.one("SELECT currency FROM businesses WHERE id=?", business), "currency");
        long discount = snapshots.stream().mapToLong(p -> Math.multiplyExact(((Number) p.get("list_price_cents")).longValue() - ((Number) p.get("price")).longValue(), integer(p, "quantity"))).reduce(0, Math::addExact);
        var ts = tax.snapshot(taxContext, net, taxTotal);
        db.update("INSERT INTO orders(id,business_id,location_id,channel,status,created_by,access_hash,expires_at_ms,total_cents,currency,net_cents,tax_cents,tax_snapshot_version,tax_policy_id,seller_registration_status,seller_registration_number,seller_tax_verified,price_mode,tax_calculated_at,receipt_name,receipt_footer,preferences_revision,branch_preferences_revision,discount_cents) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, business, location, channel, "RESERVED", online ? null : tenant.actor().getId(), token == null ? null : CommandService.hash(token),
                System.currentTimeMillis() + 300000, total, currency, net, taxTotal, 1, ts.get("tax_policy_id"), ts.get("seller_registration_status"),
                ts.get("seller_registration_number"), ts.get("seller_tax_verified"), ts.get("price_mode"), ts.get("tax_calculated_at"),
                ts.get("receipt_name"), ts.get("receipt_footer"), ts.get("preferences_revision"), ts.get("branch_preferences_revision"), discount);
        for (var p : snapshots) {
            String product = string(p, "id");
            int qty = integer(p, "quantity");
            db.update("INSERT INTO order_lines(order_id,business_id,product_id,sku,item_name,quantity,price_cents,cost_cents,net_cents,tax_cents,total_cents,tax_classification,tax_rate_percent,tax_profile_code,tax_rate_version_id,product_tax_version_id,tax_rule_label,tax_verified,tax_illustrative,list_price_cents,discount_cents,discount_percent,discount_reason,price_version_id,promotion_id,unit_code,unit_quantity,unit_factor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, business, product, p.get("sku"), p.get("item_name"), qty, p.get("price"), p.get("cost_snapshot"),
                    p.get("net_cents"), p.get("tax_cents"), p.get("total_cents"), p.get("tax_classification"), p.get("tax_rate_percent"),
                    p.get("tax_profile_code"), p.get("tax_rate_version_id"), p.get("product_tax_version_id"), p.get("tax_rule_label"), p.get("tax_verified"), p.get("tax_illustrative"),
                    p.get("list_price_cents"), Math.multiplyExact(((Number) p.get("list_price_cents")).longValue() - ((Number) p.get("price")).longValue(), qty), p.get("discount_percent"), p.get("discount_reason"), p.get("price_version_id"), p.get("promotion_id"),
                    sourceUnit(business, r.lines(), product, "code"), sourceUnit(business, r.lines(), product, "quantity"), sourceUnit(business, r.lines(), product, "factor"));
            ledger.change(business, location, product, 0, qty, "RESERVATION", "CHECKOUT", null, online ? null : tenant.actor().getId(), online ? "ONLINE_CUSTOMER" : "EMPLOYEE", id);
        }
        event(business, id, "RESERVED", online ? null : tenant.actor().getId(), online ? "ONLINE_CUSTOMER" : "EMPLOYEE", null);
        commands.save(business, r.requestId(), fingerprint, id);
        var result = view(db.one("SELECT * FROM orders WHERE id=? AND business_id=?", id, business));
        if (online) result.put("accessToken", token);
        return result;
    }

    private Map<String, Object> authorize(int business, String id, String token, boolean online) {
        var order = db.one("SELECT * FROM orders WHERE business_id=? AND id=?", business, id);
        if (!online) tenant.requireLocation(integer(order, "location_id"));
        if (online) {
            String expected = string(order, "access_hash");
            if (expected == null || token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), CommandService.hash(token).getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return order;
    }

    private Map<String, Object> view(Map<String, Object> order) {
        int business = integer(order, "business_id");
        String id = string(order, "id");
        Map<String, Object> result = new LinkedHashMap<>(order);
        result.remove("access_hash");
        result.put("simulated", db.number("SELECT count(*) FROM payment_records WHERE order_id=? AND business_id=? AND provider<>'SIMULATOR'", id, business) == 0);
        result.put("lines", db.rows("SELECT product_id,sku,item_name,quantity,price_cents,cost_cents,net_cents,tax_cents,total_cents,tax_classification,tax_rate_percent,tax_profile_code,tax_rate_version_id,product_tax_version_id,tax_rule_label,tax_verified,tax_illustrative,list_price_cents,discount_cents,discount_percent,discount_reason,price_version_id,promotion_id,unit_code,unit_quantity,unit_factor FROM order_lines WHERE order_id=? AND business_id=? ORDER BY sku", id, business));
        result.put("payments", db.rows("SELECT provider,method,successful,amount_cents,created_at FROM payment_records WHERE order_id=? AND business_id=?", id, business));
        result.put("refunds", db.rows("SELECT amount_cents,reason,created_at FROM simulated_refunds WHERE order_id=? AND business_id=? UNION ALL SELECT amount_cents,reason,completed_ms AS created_at FROM sale_reversals WHERE order_id=? AND business_id=? AND status='COMPLETED'", id, business, id, business));
        result.put("events", db.rows("SELECT action,actor_id,actor_kind,reason,created_at FROM order_events WHERE order_id=? AND business_id=? ORDER BY created_at,id", id, business));
        return result;
    }

    @Transactional
    public Map<String, Object> pay(String id, Payment r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        return payment(tenant.businessId(), id, null, false, r);
    }

    @Transactional
    public Map<String, Object> publicPay(int business, String id, String token, Payment r) {
        return payment(business, id, token, true, r);
    }

    @Transactional
    public Map<String, Object> settle(String id, boolean successful, String requestId, String provider) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        return payment(tenant.businessId(), id, null, false, new Payment(provider.equals("CASH") ? "CASH" : "CARD", successful, requestId), provider, true);
    }

    private Map<String, Object> payment(int business, String id, String token, boolean online, Payment r) {
        return payment(business, id, token, online, r, "SIMULATOR", false);
    }

    private Map<String, Object> payment(int business, String id, String token, boolean online, Payment r, String provider, boolean controlled) {
        if (!controlled && (!simulatorEnabled || !environment.matchesProfiles("demo", "test", "dev")))
            conflict("Use the explicit cash/manual-terminal payment workflow; simulator is disabled");
        var order = authorize(business, id, token, online);
        if (!controlled && Set.of("PENDING", "UNKNOWN").contains(order.get("payment_state")))
            conflict("Reconcile the held payment attempt first");
        String command = "PAY|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (order.get("status").equals("RESERVED") && order.get("payment_state").equals("NOT_STARTED") && ((Number) order.get("expires_at_ms")).longValue() <= System.currentTimeMillis()) {
            release(order, "EXPIRED", null, "SYSTEM_EXPIRY", "Reservation expired");
            commands.save(business, r.requestId(), command, id);
            return view(db.one("SELECT * FROM orders WHERE id=? AND business_id=?", id, business));
        }
        if (!order.get("status").equals("RESERVED")) conflict("Order is not awaiting payment");
        int location = integer(order, "location_id");
        Integer actor = online ? null : tenant.actor().getId();
        String actorKind = online ? "ONLINE_CUSTOMER" : "EMPLOYEE";
        for (var line : lines(business, id)) {
            int qty = integer(line, "quantity");
            var movement = ledger.change(business, location, string(line, "product_id"), r.successful() ? -(long) qty : 0, -(long) qty,
                    r.successful() ? "SALE" : "RELEASE", r.successful() ? "SIMULATED_SALE" : "PAYMENT_FAILED", null, actor, actorKind, id);
            if (r.successful())
                db.update("INSERT INTO sale_cost_snapshots VALUES(?,?,?,?,?)", id, business, line.get("product_id"), qty, new BigDecimal(movement.get("value_delta").toString()).negate().toPlainString());
        }
        if (!controlled)
            db.update("INSERT INTO simulated_payments(order_id,business_id,method,successful,amount_cents) VALUES(?,?,?,?,?)", id, business, r.method(), r.successful() ? 1 : 0, order.get("total_cents"));
        db.update("INSERT INTO payment_records(order_id,business_id,provider,method,successful,amount_cents) VALUES(?,?,?,?,?,?)", id, business, provider, r.method(), r.successful() ? 1 : 0, order.get("total_cents"));
        db.update("UPDATE orders SET payment_state=? WHERE id=? AND business_id=?", r.successful() ? "CONFIRMED" : "DECLINED", id, business);
        String status = r.successful() ? "COMPLETED" : "PAYMENT_FAILED";
        status(business, id, status);
        event(business, id, status, actor, actorKind, provider + " " + r.method());
        if (r.successful()) captureReceipt(business, id);
        commands.save(business, r.requestId(), command, id);
        return view(db.one("SELECT * FROM orders WHERE id=? AND business_id=?", id, business));
    }

    @Transactional
    public Map<String, Object> cancel(String id, Action r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        return cancellation(tenant.businessId(), id, null, false, r);
    }

    @Transactional
    public Map<String, Object> publicCancel(int business, String id, String token, Action r) {
        return cancellation(business, id, token, true, r);
    }

    private Map<String, Object> cancellation(int business, String id, String token, boolean online, Action r) {
        var order = authorize(business, id, token, online);
        String command = "CANCEL|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        String state = string(order, "status");
        if (Set.of("PENDING", "UNKNOWN").contains(order.get("payment_state")))
            conflict("Uncertain payment must be reconciled before cancellation");
        guardLegacyReversal(business, id);
        Integer actor = online ? null : tenant.actor().getId();
        String kind = online ? "ONLINE_CUSTOMER" : "EMPLOYEE";
        if (Set.of("FULFILLED", "RETURNED").contains(state))
            conflict("Fulfilled orders require an explicit physical return");
        if (state.equals("RESERVED")) release(order, "CANCELLED", actor, kind, r.reason());
        else if (state.equals("COMPLETED")) {
            for (var line : lines(business, id))
                restock(order, line, "CANCELLATION", "UNFULFILLED_CANCELLATION", r.reason(), actor, kind);
            refundRecord(order, r.reason());
            status(business, id, "CANCELLED");
            event(business, id, "CANCELLED", actor, kind, r.reason());
        }
        commands.save(business, r.requestId(), command, id);
        return view(db.one("SELECT * FROM orders WHERE id=? AND business_id=?", id, business));
    }

    @Transactional
    public Map<String, Object> fulfill(String id, Action r) {
        tenant.requireRole("OWNER", "MANAGER", "CLERK");
        int business = tenant.businessId();
        var order = authorize(business, id, null, false);
        String command = "FULFILL|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (!order.get("status").equals("COMPLETED")) conflict("Only completed orders may be fulfilled");
        status(business, id, "FULFILLED");
        event(business, id, "FULFILLED", tenant.actor().getId(), "EMPLOYEE", r.reason());
        commands.save(business, r.requestId(), command, id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> returnOrder(String id, Return r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var order = authorize(business, id, null, false);
        guardLegacyReversal(business, id);
        String command = "RETURN|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (!order.get("status").equals("FULFILLED")) conflict("Only fulfilled orders may be physically returned");
        if (r.resellable()) for (var line : lines(business, id))
            restock(order, line, "RETURN", "RESELLABLE_RETURN", r.reason(), tenant.actor().getId(), "EMPLOYEE");
        refundRecord(order, r.reason());
        status(business, id, "RETURNED");
        event(business, id, "RETURNED", tenant.actor().getId(), "EMPLOYEE", r.reason() + "; resellable=" + r.resellable());
        commands.save(business, r.requestId(), command, id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> refund(String id, Action r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var order = authorize(business, id, null, false);
        guardLegacyReversal(business, id);
        String command = "REFUND|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (!Set.of("COMPLETED", "FULFILLED", "RETURNED", "CANCELLED").contains(string(order, "status")))
            conflict("Order has no successful payment to refund");
        refundRecord(order, r.reason());
        event(business, id, "SIMULATED_REFUND", tenant.actor().getId(), "EMPLOYEE", r.reason());
        commands.save(business, r.requestId(), command, id);
        return get(id);
    }

    private void restock(Map<String, Object> order, Map<String, Object> line, String type, String reason, String notes, Integer actor, String actorKind) {
        int business = integer(order, "business_id");
        String id = string(order, "id"), product = string(line, "product_id");
        int quantity = integer(line, "quantity");
        var costs = db.rows("SELECT value FROM sale_cost_snapshots WHERE order_id=? AND product_id=? AND business_id=?", id, product, business);
        BigDecimal value = costs.isEmpty() ? BigDecimal.valueOf(((Number) line.get("cost_cents")).longValue(), 2).multiply(BigDecimal.valueOf(quantity)) : new BigDecimal(costs.getFirst().get("value").toString());
        ledger.changeValued(business, integer(order, "location_id"), product, quantity, 0, type, reason, notes, actor, actorKind, id, value, null, null);
    }

    private void refundRecord(Map<String, Object> order, String reason) {
        int business = integer(order, "business_id");
        String id = string(order, "id");
        if (db.number("SELECT count(*) FROM simulated_payments WHERE order_id=? AND business_id=? AND successful=1", id, business) == 0)
            conflict("No successful simulated payment");
        db.update("INSERT INTO simulated_refunds(order_id,business_id,amount_cents,reason) VALUES(?,?,?,?) ON CONFLICT(order_id) DO NOTHING", id, business, order.get("total_cents"), reason);
    }

    private List<Map<String, Object>> lines(int business, String id) {
        return db.rows("SELECT * FROM order_lines WHERE order_id=? AND business_id=? ORDER BY product_id", id, business);
    }

    private void status(int business, String id, String state) {
        db.update("UPDATE orders SET status=? WHERE business_id=? AND id=?", state, business, id);
    }

    private void event(int business, String id, String action, Integer actor, String kind, String reason) {
        db.update("INSERT INTO order_events(id,business_id,order_id,action,actor_id,actor_kind,reason) VALUES(?,?,?,?,?,?,?)", UUID.randomUUID().toString(), business, id, action, actor, kind, reason);
    }

    private void release(Map<String, Object> order, String state, Integer actor, String kind, String reason) {
        int business = integer(order, "business_id");
        String id = string(order, "id");
        for (var line : lines(business, id))
            ledger.change(business, integer(order, "location_id"), string(line, "product_id"), 0, -(long) integer(line, "quantity"), "RELEASE", state, reason, actor, kind, id);
        status(business, id, state);
        event(business, id, state, actor, kind, reason);
    }

    private void expireBusiness(int business) {
        for (var order : db.rows("SELECT * FROM orders WHERE business_id=? AND status='RESERVED' AND payment_state='NOT_STARTED' AND expires_at_ms<=? ORDER BY id", business, System.currentTimeMillis()))
            release(order, "EXPIRED", null, "SYSTEM_EXPIRY", "Reservation expired");
    }

    @Transactional
    public void expire() {
        for (var row : db.rows("SELECT DISTINCT business_id FROM orders WHERE status='RESERVED' AND expires_at_ms<=?", System.currentTimeMillis()))
            expireBusiness(integer(row, "business_id"));
    }

    private List<Line> normalize(int business, List<Line> input) {
        List<Line> result = new ArrayList<>();
        for (var line : input) {
            if (line.unitCode() != null || line.unitQuantity() != null) {
                if (line.unitCode() == null || line.unitQuantity() == null || (line.quantity() != null && line.quantity() != 0))
                    bad("Use either base quantity or unitCode/unitQuantity, not both");
                result.add(new Line(line.productId(), units.convert(business, line.productId().toString(), line.unitCode(), line.unitQuantity(), "SALE")));
            } else {
                if (line.quantity() == null || line.quantity() < 1) bad("Quantity must be positive");
                result.add(line);
            }
        }
        return result;
    }

    private String sourceUnit(int business, List<Line> lines, String product, String field) {
        var source = lines.stream().filter(l -> l.productId().toString().equals(product)).findFirst().orElseThrow();
        return switch (field) {
            case "code" -> source.unitCode() == null ? "BASE" : source.unitCode();
            case "quantity" ->
                    source.unitQuantity() == null ? Integer.toString(source.quantity()) : source.unitQuantity().toPlainString();
            default ->
                    source.unitCode() == null ? "1" : db.one("SELECT factor FROM product_units WHERE business_id=? AND product_id=? AND code=?", business, product, source.unitCode()).get("factor").toString();
        };
    }

    private void guardLegacyReversal(int business, String id) {
        if (db.number("SELECT count(*) FROM payment_attempts WHERE business_id=? AND order_id=?", business, id) > 0 ||
                db.number("SELECT count(*) FROM sale_reversals WHERE business_id=? AND order_id=? AND status<>'REJECTED'", business, id) > 0 ||
                db.number("SELECT count(*) FROM payment_records WHERE business_id=? AND order_id=? AND successful=1 AND provider<>'SIMULATOR'", business, id) > 0)
            conflict("Use the approved return/refund workflow for this sale");
    }

    private void captureReceipt(int business, String id) {
        String body = renderReceipt(view(db.one("SELECT * FROM orders WHERE business_id=? AND id=?", business, id)));
        db.update("INSERT INTO sale_receipts VALUES(?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING", id, business, body, CommandService.hash(body), System.currentTimeMillis());
    }

    private long cents(Object amount) {
        return new BigDecimal(amount.toString()).movePointRight(2).longValueExact();
    }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
