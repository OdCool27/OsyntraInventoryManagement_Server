package org.osyntra.osyntrainventorymanagement.catalogue;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class LabelService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final AuditService audit;

    public LabelService(MvpStore db, TenantContext tenant, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> generate(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var p = db.one("SELECT barcode FROM products WHERE business_id=? AND id=? AND active=1 AND product_kind<>'PARENT'", business, id);
        if (p.get("barcode") == null) {
            String code = "ORV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
            db.update("UPDATE products SET barcode=? WHERE business_id=? AND id=?", code, business, id);
            audit.log("INTERNAL_BARCODE_CREATED", "PRODUCT", id, null, code);
            p.put("barcode", code);
        }
        p.put("namespace", p.get("barcode").toString().startsWith("ORV-") ? "ORVEXA_INTERNAL" : "EXTERNAL");
        return p;
    }

    public byte[] label(String id, String format, int widthMm, int heightMm) throws IOException {
        if (widthMm < 50 || widthMm > 150 || heightMm < 20 || heightMm > 80)
            UnitService.bad("Label dimensions must be 50–150 mm by 20–80 mm");
        String code = string(db.one("SELECT barcode FROM products WHERE id=? AND business_id=? AND product_kind<>'PARENT'", id, tenant.businessId()), "barcode");
        if (code == null || !code.matches("[ -~]{1,60}"))
            UnitService.bad("Printable CODE128 labels require 1–60 ASCII barcode characters");
        BitMatrix bits = new Code128Writer().encode(code, BarcodeFormat.CODE_128, 1, 1, Map.of(EncodeHintType.MARGIN, 20));
        if ((double) widthMm / bits.getWidth() < 0.25)
            UnitService.bad("Increase label width to retain a minimum 0.25 mm module including quiet zones");
        if (format.equals("svg")) {
            StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + widthMm + "mm\" height=\"" + heightMm + "mm\" viewBox=\"0 0 " + bits.getWidth() + " 100\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            for (int x = 0; x < bits.getWidth(); x++)
                if (bits.get(x, 0)) svg.append("<rect x=\"").append(x).append("\" y=\"5\" width=\"1\" height=\"70\"/>");
            svg.append("<text x=\"").append(bits.getWidth() / 2).append("\" y=\"93\" text-anchor=\"middle\" font-size=\"10\">").append(escape(code)).append("</text></svg>");
            return svg.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (format.equals("png")) {
            int width = (int) Math.round(widthMm * 300 / 25.4), height = (int) Math.round(heightMm * 300 / 25.4);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(java.awt.Color.BLACK);
            // Integer module widths prevent blurred edges when printed at 300 DPI.
            int module = width / bits.getWidth(), start = (width - module * bits.getWidth()) / 2;
            for (int x = 0; x < bits.getWidth(); x++)
                if (bits.get(x, 0)) g.fillRect(start + x * module, 10, module, height - 55);
            g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 24));
            g.drawString(code, (width - g.getFontMetrics().stringWidth(code)) / 2, height - 15);
            g.dispose();
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        if (!format.equals("pdf")) UnitService.bad("Label format must be svg, png or pdf");
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            float w = widthMm * 72f / 25.4f, h = heightMm * 72f / 25.4f;
            var page = new PDPage(new PDRectangle(w, h));
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                float module = w / bits.getWidth();
                for (int x = 0; x < bits.getWidth(); x++)
                    if (bits.get(x, 0)) content.addRect(x * module, 20, module, h - 25);
                content.fill();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 8);
                content.newLineAtOffset(8, 7);
                content.showText(code);
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
