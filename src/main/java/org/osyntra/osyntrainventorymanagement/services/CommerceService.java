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

    public CommerceService(MvpStore db, TenantContext tenant, TenancyService tenancy, StockLedger ledger, CommandService commands) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.ledger = ledger;
        this.commands = commands;
    }

    public List<Map<String, Object>> history(int limit, int offset) {
        return db.rows("SELECT id,location_id,channel,status,total_cents,currency,expires_at_ms,created_at FROM orders WHERE business_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    public Map<String, Object> get(String id) {
        return view(authorize(tenant.businessId(), id, null, false));
    }

    public Map<String, Object> publicGet(int business, String id, String token) {
        return view(authorize(business, id, token, true));
    }

    public Map<String, Object> cart(Cart request) {
        return publicCart(tenant.businessId(), request);
    }

    public Map<String, Object> publicCart(int business, Cart request) {
        String currency = string(db.one("SELECT currency FROM businesses WHERE id=? AND active=1", business), "currency");
        int location = tenancy.locationId(business, request.locationId());
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        long total = 0;
        boolean sufficient = true;
        for (var line : request.lines()) {
            if (!seen.add(line.productId())) bad("Each product may appear only once");
            var product = db.one("SELECT id,sku,item_name,unit_price FROM products WHERE id=? AND business_id=? AND active=1", line.productId().toString(), business);
            long price = cents(product.remove("unit_price"));
            long available = db.number("SELECT coalesce(sum(quantity_on_hand-quantity_reserved),0) FROM inventory WHERE product_id=? AND business_id=? AND location_id=?", line.productId().toString(), business, location);
            product.put("price_cents", price);
            product.put("quantity", line.quantity());
            product.put("available", available);
            sufficient &= available >= line.quantity();
            lines.add(product);
            try {
                total = Math.addExact(total, Math.multiplyExact(price, line.quantity()));
            } catch (ArithmeticException exception) {
                bad("Cart total exceeds supported range");
            }
        }
        // Cart previews never allocate stock; checkout performs the authoritative conditional update.
        return Map.of("lines", lines, "total_cents", total, "currency", currency, "sufficientStock", sufficient, "reserved", false);
    }

    public String receipt(Map<String, Object> order) {
        String currency = string(order, "currency");
        StringBuilder text = new StringBuilder("OSYNTRA — SIMULATED DEMO RECEIPT\nNo real funds processed.\n");
        text.append("Order: ").append(order.get("id")).append("\nDate (UTC): ").append(order.get("created_at"))
                .append("\nChannel: ").append(order.get("channel")).append("\nStatus: ").append(order.get("status")).append("\n\n");
        if (order.get("lines") instanceof List<?> lines) for (Object item : lines) {
            @SuppressWarnings("unchecked") var line = (Map<String, Object>) item;
            text.append(line.get("sku")).append(" — ").append(line.get("item_name")).append("\n")
                    .append(line.get("quantity")).append(" x ").append(currency).append(" ")
                    .append(BigDecimal.valueOf(((Number) line.get("price_cents")).longValue(), 2).toPlainString()).append("\n");
        }
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
        int location = tenancy.locationId(business, r.locationId());
        String channel = online ? "ONLINE" : "POS";
        // Sorting makes repeated requests with the same lines independent of cart display order.
        var lines = r.lines().stream().sorted(Comparator.comparing(l -> l.productId().toString())).toList();
        String fingerprint = "CHECKOUT|" + channel + "|" + location + "|" + lines;
        String previous = commands.replay(business, r.requestId(), fingerprint);
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
        long total = 0;
        Set<UUID> unique = new HashSet<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (var line : lines) {
            if (!unique.add(line.productId())) bad("Each product may appear only once");
            var p = db.one("SELECT id,sku,item_name,unit_price,cost FROM products WHERE business_id=? AND id=? AND active=1", business, line.productId().toString());
            long price = cents(p.get("unit_price")), cost = cents(p.get("cost"));
            try {
                total = Math.addExact(total, Math.multiplyExact(price, line.quantity()));
            } catch (ArithmeticException e) {
                bad("Order total exceeds supported range");
            }
            Map<String, Object> snapshot = new HashMap<>(p);
            snapshot.put("quantity", line.quantity());
            snapshot.put("price", price);
            snapshot.put("cost_snapshot", cost);
            snapshots.add(snapshot);
        }
        String currency = string(db.one("SELECT currency FROM businesses WHERE id=?", business), "currency");
        db.update("INSERT INTO orders(id,business_id,location_id,channel,status,created_by,access_hash,expires_at_ms,total_cents,currency) VALUES(?,?,?,?,'RESERVED',?,?,?,?,?)",
                id, business, location, channel, online ? null : tenant.actor().getId(), token == null ? null : CommandService.hash(token),
                System.currentTimeMillis() + 300000, total, currency);
        for (var p : snapshots) {
            String product = string(p, "id");
            int qty = integer(p, "quantity");
            db.update("INSERT INTO order_lines(order_id,business_id,product_id,sku,item_name,quantity,price_cents,cost_cents) VALUES(?,?,?,?,?,?,?,?)",
                    id, business, product, p.get("sku"), p.get("item_name"), qty, p.get("price"), p.get("cost_snapshot"));
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
        result.put("simulated", true);
        result.put("lines", db.rows("SELECT product_id,sku,item_name,quantity,price_cents,cost_cents FROM order_lines WHERE order_id=? AND business_id=? ORDER BY sku", id, business));
        result.put("payments", db.rows("SELECT method,successful,amount_cents,created_at FROM simulated_payments WHERE order_id=? AND business_id=?", id, business));
        result.put("refunds", db.rows("SELECT amount_cents,reason,created_at FROM simulated_refunds WHERE order_id=? AND business_id=?", id, business));
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

    private Map<String, Object> payment(int business, String id, String token, boolean online, Payment r) {
        var order = authorize(business, id, token, online);
        String command = "PAY|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (order.get("status").equals("RESERVED") && ((Number) order.get("expires_at_ms")).longValue() <= System.currentTimeMillis()) {
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
            ledger.change(business, location, string(line, "product_id"), r.successful() ? -(long) qty : 0, -(long) qty,
                    r.successful() ? "SALE" : "RELEASE", r.successful() ? "SIMULATED_SALE" : "PAYMENT_FAILED", null, actor, actorKind, id);
        }
        db.update("INSERT INTO simulated_payments(order_id,business_id,method,successful,amount_cents) VALUES(?,?,?,?,?)", id, business, r.method(), r.successful() ? 1 : 0, order.get("total_cents"));
        String status = r.successful() ? "COMPLETED" : "PAYMENT_FAILED";
        status(business, id, status);
        event(business, id, status, actor, actorKind, "Simulated " + r.method());
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
        Integer actor = online ? null : tenant.actor().getId();
        String kind = online ? "ONLINE_CUSTOMER" : "EMPLOYEE";
        if (Set.of("FULFILLED", "RETURNED").contains(state))
            conflict("Fulfilled orders require an explicit physical return");
        if (state.equals("RESERVED")) release(order, "CANCELLED", actor, kind, r.reason());
        else if (state.equals("COMPLETED")) {
            for (var line : lines(business, id))
                ledger.change(business, integer(order, "location_id"), string(line, "product_id"), integer(line, "quantity"), 0, "CANCELLATION", "UNFULFILLED_CANCELLATION", r.reason(), actor, kind, id);
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
        String command = "RETURN|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (!order.get("status").equals("FULFILLED")) conflict("Only fulfilled orders may be physically returned");
        if (r.resellable()) for (var line : lines(business, id))
            ledger.change(business, integer(order, "location_id"), string(line, "product_id"), integer(line, "quantity"), 0, "RETURN", "RESELLABLE_RETURN", r.reason(), tenant.actor().getId(), "EMPLOYEE", id);
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
        String command = "REFUND|" + id + "|" + r;
        if (commands.replay(business, r.requestId(), command) != null) return view(order);
        if (!Set.of("COMPLETED", "FULFILLED", "RETURNED", "CANCELLED").contains(string(order, "status")))
            conflict("Order has no successful payment to refund");
        refundRecord(order, r.reason());
        event(business, id, "SIMULATED_REFUND", tenant.actor().getId(), "EMPLOYEE", r.reason());
        commands.save(business, r.requestId(), command, id);
        return get(id);
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
        for (var order : db.rows("SELECT * FROM orders WHERE business_id=? AND status='RESERVED' AND expires_at_ms<=? ORDER BY id", business, System.currentTimeMillis()))
            release(order, "EXPIRED", null, "SYSTEM_EXPIRY", "Reservation expired");
    }

    @Transactional
    public void expire() {
        for (var row : db.rows("SELECT DISTINCT business_id FROM orders WHERE status='RESERVED' AND expires_at_ms<=?", System.currentTimeMillis()))
            expireBusiness(integer(row, "business_id"));
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
