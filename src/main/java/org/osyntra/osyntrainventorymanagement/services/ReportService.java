package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.*;

@Service
public class ReportService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final TenancyService tenancy;
    private final InventoryService inventory;

    public ReportService(MvpStore db, TenantContext tenant, TenancyService tenancy, InventoryService inventory) {
        this.db = db;
        this.tenant = tenant;
        this.tenancy = tenancy;
        this.inventory = inventory;
    }

    public Map<String, Object> summary(Integer location) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        int place = tenancy.locationId(location);
        return db.one("""
                SELECT (SELECT count(*) FROM products WHERE business_id=? AND active=1) AS total_products,
                 count(*) AS stocked_products,coalesce(sum(i.quantity_on_hand),0) AS total_units,
                 coalesce(sum(CASE WHEN i.quantity_on_hand-i.quantity_reserved<=i.reorder_level THEN 1 ELSE 0 END),0) AS low_stock_products,
                 coalesce(sum(CASE WHEN i.quantity_on_hand=i.quantity_reserved THEN 1 ELSE 0 END),0) AS out_of_stock_products,
                 coalesce(sum(round(p.cost*100)*i.quantity_on_hand),0) AS inventory_cost_estimate_cents
                FROM inventory i JOIN products p ON p.id=i.product_id AND p.business_id=i.business_id
                WHERE i.business_id=? AND i.location_id=? AND p.active=1
                """, business, business, place);
    }

    public List<Map<String, Object>> stock(Integer location, boolean low, boolean out, int limit, int offset) {
        tenant.requireRole("OWNER", "MANAGER");
        return inventory.list(location, null, null, low, out, limit, offset);
    }

    public Map<String, Object> sales(LocalDate from, LocalDate to, String channel) {
        tenant.requireRole("OWNER", "MANAGER");
        if (from != null && to != null && to.isBefore(from))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End date precedes start date");
        int business = tenant.businessId();
        String start = from == null ? "0001-01-01" : from.toString();
        String end = to == null ? "9999-12-31" : to.toString();
        var totals = db.one("""
                SELECT count(*) AS completed_simulated_payments,coalesce(sum(o.total_cents),0) AS gross_revenue_cents,
                 coalesce(sum(coalesce(r.amount_cents,0)),0) AS refunded_cents,
                 coalesce(sum(o.total_cents-coalesce(r.amount_cents,0)),0) AS net_revenue_cents
                FROM orders o JOIN simulated_payments pay ON pay.order_id=o.id AND pay.business_id=o.business_id AND pay.successful=1
                LEFT JOIN simulated_refunds r ON r.order_id=o.id AND r.business_id=o.business_id
                WHERE o.business_id=? AND date(pay.created_at) BETWEEN ? AND ? AND (? IS NULL OR o.channel=?)
                """, business, start, end, channel, channel);
        totals.put("simulated", true);
        totals.put("byDate", db.rows("""
                SELECT date(pay.created_at) AS date,count(*) AS sales,sum(o.total_cents) AS gross_revenue_cents,
                    sum(coalesce(r.amount_cents,0)) AS refunded_cents
                FROM orders o JOIN simulated_payments pay ON pay.order_id=o.id AND pay.business_id=o.business_id AND pay.successful=1
                LEFT JOIN simulated_refunds r ON r.order_id=o.id AND r.business_id=o.business_id
                WHERE o.business_id=? AND date(pay.created_at) BETWEEN ? AND ? AND (? IS NULL OR o.channel=?)
                GROUP BY date(pay.created_at) ORDER BY date(pay.created_at)
                """, business, start, end, channel, channel));
        totals.put("byChannel", db.rows("""
                SELECT o.channel,count(*) AS sales,coalesce(sum(o.total_cents),0) AS gross_revenue_cents,
                 coalesce(sum(coalesce(r.amount_cents,0)),0) AS refunded_cents
                FROM orders o JOIN simulated_payments pay ON pay.order_id=o.id AND pay.business_id=o.business_id AND pay.successful=1
                LEFT JOIN simulated_refunds r ON r.order_id=o.id AND r.business_id=o.business_id
                WHERE o.business_id=? AND date(pay.created_at) BETWEEN ? AND ? AND (? IS NULL OR o.channel=?) GROUP BY o.channel
                """, business, start, end, channel, channel));
        totals.put("byProduct", db.rows("""
                SELECT l.product_id,l.sku,l.item_name,sum(l.quantity) AS units,
                 sum(l.quantity*l.price_cents) AS gross_revenue_cents,
                 sum(l.quantity*l.cost_cents) AS sale_cost_estimate_cents,
                 sum(l.quantity*(l.price_cents-l.cost_cents)) AS gross_profit_estimate_cents
                FROM order_lines l JOIN orders o ON o.id=l.order_id AND o.business_id=l.business_id
                JOIN simulated_payments pay ON pay.order_id=o.id AND pay.business_id=o.business_id AND pay.successful=1
                WHERE o.business_id=? AND date(pay.created_at) BETWEEN ? AND ? AND (? IS NULL OR o.channel=?)
                GROUP BY l.product_id,l.sku,l.item_name ORDER BY l.sku
                """, business, start, end, channel, channel));
        return totals;
    }

    public String export(Integer location) {
        var rows = stock(location, false, false, 200, 0);
        StringBuilder csv = new StringBuilder("sku,locationId,onHand,reserved,available,reorderLevel\r\n");
        // Export all matching rows in bounded pages.
        int offset = 0;
        while (!rows.isEmpty()) {
            for (var row : rows)
                csv.append(CsvSupport.cell(row.get("sku"))).append(',').append(row.get("location_id")).append(',')
                        .append(row.get("quantity_on_hand")).append(',').append(row.get("quantity_reserved")).append(',')
                        .append(row.get("available_quantity")).append(',').append(row.get("reorder_level")).append("\r\n");
            offset += rows.size();
            rows = stock(location, false, false, 200, offset);
        }
        return csv.toString();
    }
}
