package org.osyntra.osyntrainventorymanagement.payments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.time.Clock;
import java.math.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class ReversalService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final CommerceService commerce;
    private final StockLedger ledger;
    private final CommandService commands;
    private final AuditService audit;
    private final Clock clock;

    public ReversalService(MvpStore db, TenantContext tenant, CommerceService commerce, StockLedger ledger, CommandService commands, AuditService audit, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.commerce = commerce;
        this.ledger = ledger;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
    }

    public Map<String, Object> get(String id) {
        var r = db.one("SELECT * FROM sale_reversals WHERE id=? AND business_id=?", id, tenant.businessId());
        commerce.get(string(r, "order_id"));
        r.put("lines", db.rows("SELECT * FROM sale_reversal_lines WHERE reversal_id=? AND business_id=? ORDER BY product_id", id, tenant.businessId()));
        return r;
    }

    public List<Map<String, Object>> list(String id) {
        commerce.get(id);
        return db.rows("SELECT * FROM sale_reversals WHERE order_id=? AND business_id=? ORDER BY created_ms,id", id, tenant.businessId());
    }

    @Transactional
    public Map<String, Object> request(String orderId, CheckoutDtos.Reverse r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        int business = tenant.businessId();
        var order = commerce.get(orderId);
        String command = "REVERSAL_REQUEST|" + orderId + "|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return get(prior);
        if (!order.get("payment_state").equals("CONFIRMED") || db.number("SELECT count(*) FROM payment_records WHERE order_id=? AND successful=1", orderId) == 0)
            PaymentService.conflict("Only confirmed paid sales can be reversed");
        if (db.number("SELECT count(*) FROM simulated_refunds WHERE order_id=? AND business_id=?", orderId, business) > 0)
            PaymentService.conflict("This sale already has a full legacy refund");
        if (!Set.of("COMPLETED", "FULFILLED").contains(order.get("status")))
            PaymentService.conflict("Sale state is not eligible for a new reversal");
        if (r.type().equals("RETURN") && !order.get("status").equals("FULFILLED"))
            PaymentService.conflict("Physical return requires a fulfilled sale; use cancellation for unfulfilled goods");
        if (r.type().equals("CANCEL") && !order.get("status").equals("COMPLETED"))
            PaymentService.conflict("Only unfulfilled paid sales may be cancelled");
        if (r.type().equals("REFUND") && r.resellable())
            PaymentService.bad("Refund-only requests cannot restock inventory");
        String id = UUID.randomUUID().toString();
        long net = 0, tax = 0, total = 0;
        Set<UUID> seen = new HashSet<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var requested : r.lines()) {
            if (!seen.add(requested.productId())) PaymentService.bad("Reversal products must be unique");
            String product = requested.productId().toString();
            var line = db.one("SELECT * FROM order_lines WHERE order_id=? AND product_id=? AND business_id=?", orderId, product, business);
            long used = db.number("SELECT coalesce(sum(l.quantity),0) FROM sale_reversal_lines l JOIN sale_reversals r ON r.id=l.reversal_id WHERE r.order_id=? AND l.product_id=? AND r.status<>'REJECTED'", orderId, product);
            int sold = integer(line, "quantity"), qty = requested.quantity();
            if (qty > sold - used) PaymentService.conflict("Reversal exceeds the remaining sold quantity");
            var costRows = db.rows("SELECT value FROM sale_cost_snapshots WHERE order_id=? AND product_id=? AND business_id=?", orderId, product, business);
            BigDecimal cost = costRows.isEmpty() ? BigDecimal.valueOf(((Number) line.get("cost_cents")).longValue(), 2).multiply(BigDecimal.valueOf(sold)) : new BigDecimal(costRows.getFirst().get("value").toString());
            long lineTax = allocate(((Number) line.get("tax_cents")).longValue(), used, qty, sold), lineTotal = allocate(((Number) line.get("total_cents")).longValue(), used, qty, sold);
            long priorTotal = 0, priorTax = 0;
            BigDecimal priorCost = BigDecimal.ZERO;
            for (var allocation : db.rows("SELECT l.total_cents,l.tax_cents,l.cost_value FROM sale_reversal_lines l JOIN sale_reversals r ON r.id=l.reversal_id WHERE r.order_id=? AND l.product_id=? AND r.status<>'REJECTED'", orderId, product)) {
                priorTotal += ((Number) allocation.get("total_cents")).longValue();
                priorTax += ((Number) allocation.get("tax_cents")).longValue();
                priorCost = priorCost.add(new BigDecimal(allocation.get("cost_value").toString()));
            }
            long remainingQty = sold - used;
            lineTotal = remainingShare(((Number) line.get("total_cents")).longValue() - priorTotal, qty, remainingQty);
            lineTax = remainingShare(((Number) line.get("tax_cents")).longValue() - priorTax, qty, remainingQty);
            BigDecimal costPart = cost.subtract(priorCost).multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(remainingQty), 6, RoundingMode.HALF_UP);
            Map<String, Object> saved = Map.of("product", product, "quantity", qty, "net", lineTotal - lineTax, "tax", lineTax, "total", lineTotal, "cost", costPart.toPlainString());
            lines.add(saved);
            net = Math.addExact(net, lineTotal - lineTax);
            tax = Math.addExact(tax, lineTax);
            total = Math.addExact(total, lineTotal);
        }
        if (r.type().equals("CANCEL")) {
            long remaining = db.number("SELECT coalesce(sum(quantity),0) FROM order_lines WHERE order_id=?", orderId) - db.number("SELECT coalesce(sum(l.quantity),0) FROM sale_reversal_lines l JOIN sale_reversals r ON r.id=l.reversal_id WHERE r.order_id=? AND r.status<>'REJECTED'", orderId);
            if (r.lines().stream().mapToLong(CheckoutDtos.ReverseLine::quantity).sum() != remaining)
                PaymentService.conflict("Cancellation must include every remaining unfulfilled unit");
        }
        db.update("INSERT INTO sale_reversals(id,business_id,order_id,type,status,reason,resellable,amount_cents,net_cents,tax_cents,created_by,created_ms) VALUES(?,?,?,?,'REQUESTED',?,?,?,?,?,?,?)",
                id, business, orderId, r.type(), r.reason(), r.resellable() ? 1 : 0, total, net, tax, tenant.actor().getId(), clock.millis());
        for (var line : lines)
            db.update("INSERT INTO sale_reversal_lines VALUES(?,?,?,?,?,?,?,?)", id, business, line.get("product"), line.get("quantity"), line.get("net"), line.get("tax"), line.get("total"), line.get("cost"));
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("REVERSAL_REQUESTED", "REVERSAL", id, null, r, integer(order, "location_id"));
        return get(id);
    }

    @Transactional
    public Map<String, Object> decide(String id, CheckoutDtos.Decision r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var reversal = get(id);
        String command = "REVERSAL_DECISION|" + id + "|" + r.approve();
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (!reversal.get("status").equals("REQUESTED")) PaymentService.conflict("Reversal was already reviewed");
        db.update("UPDATE sale_reversals SET status=?,approved_by=? WHERE id=? AND business_id=?", r.approve() ? "APPROVED" : "REJECTED", tenant.actor().getId(), id, business);
        commands.save(business, r.requestId(), command, id);
        audit.log("REVERSAL_REVIEWED", "REVERSAL", id, null, r);
        return get(id);
    }

    @Transactional
    public Map<String, Object> complete(String id, CheckoutDtos.Complete r) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var reversal = get(id);
        String orderId = string(reversal, "order_id");
        var order = commerce.get(orderId);
        String command = "REVERSAL_COMPLETE|" + id + "|" + r, prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return get(id);
        if (!reversal.get("status").equals("APPROVED") || !r.confirmed())
            PaymentService.conflict("An approved reversal and explicit refund confirmation are required");
        String provider = string(db.one("SELECT provider FROM payment_records WHERE order_id=? AND business_id=? AND successful=1", orderId, business), "provider");
        String expected = switch (provider) {
            case "CASH" -> "CASH";
            case "MANUAL_TERMINAL" -> "EXTERNAL_MANUAL";
            default -> "SIMULATED";
        };
        if (!r.method().equals(expected))
            PaymentService.conflict("Refund confirmation must match the original payment method");
        if (expected.equals("EXTERNAL_MANUAL") && (r.externalReference() == null || r.externalReference().isBlank()))
            PaymentService.bad("Record the externally confirmed terminal refund reference");
        for (var line : db.rows("SELECT * FROM sale_reversal_lines WHERE reversal_id=? AND business_id=? ORDER BY product_id", id, business)) {
            if (!reversal.get("type").equals("REFUND") && (integer(reversal, "resellable") == 1 || reversal.get("type").equals("CANCEL")))
                ledger.changeValued(business, integer(order, "location_id"), string(line, "product_id"), integer(line, "quantity"), 0, reversal.get("type").equals("CANCEL") ? "CANCELLATION" : "RETURN", "APPROVED_" + reversal.get("type"), string(reversal, "reason"), tenant.actor().getId(), "EMPLOYEE", orderId, new BigDecimal(line.get("cost_value").toString()), null, null);
        }
        db.update("UPDATE sale_reversals SET status='COMPLETED',completed_ms=?,refund_method=?,external_reference=? WHERE id=? AND business_id=?", clock.millis(), r.method(), r.externalReference(), id, business);
        long refunded = db.number("SELECT coalesce(sum(amount_cents),0) FROM sale_reversals WHERE order_id=? AND status='COMPLETED'", orderId);
        long reversedQuantity = db.number("SELECT coalesce(sum(l.quantity),0) FROM sale_reversal_lines l JOIN sale_reversals r ON r.id=l.reversal_id WHERE r.order_id=? AND r.status='COMPLETED'", orderId);
        long soldQuantity = db.number("SELECT coalesce(sum(quantity),0) FROM order_lines WHERE order_id=?", orderId);
        if (refunded == ((Number) order.get("total_cents")).longValue() && reversedQuantity == soldQuantity && !reversal.get("type").equals("REFUND"))
            db.update("UPDATE orders SET status=? WHERE id=? AND business_id=?", reversal.get("type").equals("CANCEL") ? "CANCELLED" : "RETURNED", orderId, business);
        commands.save(business, r.requestId(), command, id);
        audit.logLocation("REVERSAL_COMPLETED", "REVERSAL", id, null, r, integer(order, "location_id"));
        return get(id);
    }

    private long remainingShare(long amount, int qty, long remaining) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(remaining), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private long allocate(long amount, long used, int qty, int sold) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(used + qty)).divide(BigDecimal.valueOf(sold), 0, RoundingMode.HALF_UP).longValueExact() - BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(used)).divide(BigDecimal.valueOf(sold), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
