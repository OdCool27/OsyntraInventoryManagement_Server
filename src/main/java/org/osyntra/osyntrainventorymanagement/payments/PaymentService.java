package org.osyntra.osyntrainventorymanagement.payments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.time.Clock;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class PaymentService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final CommerceService commerce;
    private final CommandService commands;
    private final AuditService audit;
    private final Clock clock;
    private final Map<String, PaymentProvider> providers;

    public PaymentService(MvpStore db, TenantContext tenant, CommerceService commerce, CommandService commands, AuditService audit, Clock clock, List<PaymentProvider> providers) {
        this.db = db;
        this.tenant = tenant;
        this.commerce = commerce;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
        this.providers = new HashMap<>();
        for (var p : providers) this.providers.put(p.id(), p);
    }

    public List<Map<String, Object>> list(String order) {
        commerce.get(order);
        return db.rows("SELECT id,provider,status,amount_cents,currency,external_reference,created_ms,resolved_ms FROM payment_attempts WHERE order_id=? AND business_id=? ORDER BY created_ms,id", order, tenant.businessId());
    }

    @Transactional
    public Map<String, Object> start(String orderId, CheckoutDtos.Pay r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        int business = tenant.businessId();
        var order = commerce.get(orderId);
        String command = "PAYMENT_ATTEMPT|" + orderId + "|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return view(prior);
        if (!order.get("status").equals("RESERVED") || !order.get("payment_state").equals("NOT_STARTED"))
            conflict("Order cannot start another payment; reconcile an unresolved attempt first");
        if (((Number) order.get("expires_at_ms")).longValue() <= System.currentTimeMillis())
            conflict("Reservation expired before payment initiation");
        if (!r.provider().equals("SIMULATOR") && r.simulatorOutcome() != null)
            bad("Simulator outcomes cannot confirm real cash/terminal operations");
        var provider = providers.get(r.provider());
        if (provider == null) bad("Payment provider unavailable");
        var outcome = provider.prepare(r.simulatorOutcome()); // Pure local decisions; no network or card details.
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO payment_attempts(id,business_id,order_id,provider,status,amount_cents,currency,created_by,created_ms) VALUES(?,?,?,?,'PENDING',?,?,?,?)",
                id, business, orderId, r.provider(), order.get("total_cents"), order.get("currency"), tenant.actor().getId(), clock.millis());
        db.update("UPDATE orders SET payment_state=? WHERE id=? AND business_id=?", outcome == PaymentProvider.Outcome.UNKNOWN ? "UNKNOWN" : "PENDING", orderId, business);
        if (outcome != PaymentProvider.Outcome.UNKNOWN) {
            commerce.settle(orderId, outcome == PaymentProvider.Outcome.CONFIRMED, "settlement-" + id, r.provider());
            db.update("UPDATE payment_attempts SET status=?,resolved_by=?,resolved_ms=? WHERE id=?", outcome.name(), tenant.actor().getId(), clock.millis(), id);
        } else db.update("UPDATE payment_attempts SET status='UNKNOWN' WHERE id=?", id);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("PAYMENT_ATTEMPT_CREATED", "PAYMENT_ATTEMPT", id, null, r, integer(order, "location_id"));
        return view(id);
    }

    @Transactional
    public Map<String, Object> reconcile(String id, CheckoutDtos.Reconcile r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var attempt = view(id);
        String orderId = string(attempt, "order_id");
        var order = commerce.get(orderId);
        String fingerprint = "PAYMENT_RECONCILE|" + id + "|" + r.outcome() + "|" + r.externalReference();
        var events = db.rows("SELECT payload_hash FROM payment_events WHERE business_id=? AND event_key=?", business, r.eventId());
        if (!events.isEmpty()) {
            if (!events.getFirst().get("payload_hash").equals(CommandService.hash(fingerprint)))
                conflict("Reconciliation event already has a different outcome");
            return view(id);
        }
        String prior = commands.replay(business, r.requestId(), fingerprint);
        if (prior != null) return view(id);
        if (!Set.of("PENDING", "UNKNOWN").contains(attempt.get("status")))
            conflict("Payment already resolved; conflicting outcomes require an explicit approved refund");
        boolean confirmed = r.outcome().equals("CONFIRMED");
        db.update("UPDATE payment_attempts SET external_reference=? WHERE id=? AND business_id=?", r.externalReference(), id, business);
        commerce.settle(orderId, confirmed, "settlement-" + id, string(attempt, "provider"));
        db.update("UPDATE payment_attempts SET status=?,resolved_by=?,resolved_ms=? WHERE id=?", confirmed ? "CONFIRMED" : "DECLINED", tenant.actor().getId(), clock.millis(), id);
        db.update("INSERT INTO payment_events VALUES(?,?,?,?,?,?,?)", UUID.randomUUID().toString(), business, id, r.eventId(), CommandService.hash(fingerprint), r.outcome(), clock.millis());
        commands.save(business, r.requestId(), fingerprint, id);
        audit.logLocation("PAYMENT_RECONCILED", "PAYMENT_ATTEMPT", id, null, r, integer(order, "location_id"));
        return view(id);
    }

    private Map<String, Object> view(String id) {
        var result = db.one("SELECT * FROM payment_attempts WHERE id=? AND business_id=?", id, tenant.businessId());
        commerce.get(string(result, "order_id"));
        return result;
    }

    static void bad(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    static void conflict(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
}
