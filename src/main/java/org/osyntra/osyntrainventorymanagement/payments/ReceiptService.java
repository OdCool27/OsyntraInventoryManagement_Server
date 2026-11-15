package org.osyntra.osyntrainventorymanagement.payments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ReceiptService {
    private final CommerceService commerce;
    private final MvpStore db;
    private final TenantContext tenant;
    private final MailOutbox mail;
    private final CommandService commands;
    private final AuthRateLimiter limits;

    public ReceiptService(CommerceService commerce, MvpStore db, TenantContext tenant, MailOutbox mail, CommandService commands, AuthRateLimiter limits) {
        this.commerce = commerce;
        this.db = db;
        this.tenant = tenant;
        this.mail = mail;
        this.commands = commands;
        this.limits = limits;
    }

    public String text(String id) {
        var order = commerce.get(id);
        if (!order.get("payment_state").equals("CONFIRMED"))
            PaymentService.conflict("Receipt requires confirmed payment");
        return commerce.receipt(order);
    }

    public byte[] render(String id, String format) throws IOException {
        String body = text(id);
        if (format.equals("text")) return body.getBytes(StandardCharsets.UTF_8);
        if (format.equals("html"))
            return ("<!doctype html><html><head><meta charset=\"UTF-8\"><title>Orvexa receipt</title></head><body><pre>" + body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre></body></html>").getBytes(StandardCharsets.UTF_8);
        if (!format.equals("pdf")) PaymentService.bad("Receipt format must be text, html or pdf");
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            List<String> lines = new ArrayList<>();
            for (String line : body.split("\\R", -1)) {
                String clean = line.replaceAll("[^\\x20-\\x7E]", "?");
                if (clean.isEmpty()) lines.add("");
                else for (int start = 0; start < clean.length(); start += 90)
                    lines.add(clean.substring(start, Math.min(start + 90, clean.length())));
            }
            for (int start = 0; start < lines.size(); start += 60) {
                var page = new PDPage();
                doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 9);
                    content.setLeading(11);
                    content.newLineAtOffset(35, 755);
                    for (int n = start; n < Math.min(start + 60, lines.size()); n++) {
                        content.showText(lines.get(n));
                        content.newLine();
                    }
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Transactional
    public Map<String, Object> email(String id, CheckoutDtos.ReceiptEmail r) {
        tenant.requireRole("OWNER", "MANAGER", "CASHIER");
        int business = tenant.businessId();
        String body = text(id), command = "RECEIPT_EMAIL|" + id + "|" + r.email();
        String prior = commands.replay(business, r.requestId(), command);
        if (prior != null) return Map.of("queued", true, "orderId", id);
        mail.enqueueReceipt(tenant.actor().getId(), business, r.email(), id + ":" + r.requestId(), body);
        commands.save(business, r.requestId(), command, id);
        return Map.of("queued", true, "orderId", id);
    }

    public void emailLimit() {
        limits.check("receipt-email:" + tenant.actor().getId(), 10, 3600000);
    }
}
