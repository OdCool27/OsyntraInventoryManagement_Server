package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.osyntra.osyntrainventorymanagement.catalogue.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;

import java.util.*;
import java.io.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogueB3Tests extends MvpTestSupport {
    @Autowired
    CatalogueImportService imports;

    String key() {
        return UUID.randomUUID().toString();
    }

    Map<String, Object> options(String policy) {
        return Map.of("mapping", Map.of(), "duplicatePolicy", policy, "requestId", key());
    }

    @Test
    void variantParentsCannotHoldStockAndChildrenMustBeUniqueWithinTenant() throws Exception {
        var parentBody = productBody("PARENT");
        parentBody.put("productKind", "PARENT");
        String parent = send("/api/products", parentBody, "OWNER", 201).get("itemId").asString();
        open(parent, 1, "OWNER", 404);
        var body = productBody("RED");
        body.putAll(Map.of("productKind", "VARIANT", "parentId", parent, "variantKey", "RED-SMALL"));
        String child = send("/api/products", body, "OWNER", 201).get("itemId").asString();
        open(child, 4, "OWNER", 201);
        body.put("sku", "OTHER_RED");
        send("/api/products", body, "OWNER", 409);
        send("/api/products", body, "OTHER", 404);
        send("/api/orders/cart", Map.of("lines", List.of(Map.of("productId", parent, "quantity", 1))), "CASHIER", 404);
        assertEquals("VARIANT", getJson("/api/products/" + child, "OWNER", 200).get("productKind").asString());
    }

    @Test
    void decimalUnitConversionsRequireExactBaseGranularityAndCannotBeEdited() throws Exception {
        String p = stocked("UNITS", 5);
        var unit = Map.of("code", "BOX", "factor", "12", "purchaseAllowed", true, "saleAllowed", true, "requestId", key());
        send("/api/v1/catalogue/products/" + p + "/units", unit, "OWNER", 200);
        send("/api/v1/catalogue/products/" + p + "/units", unit, "OWNER", 200);
        var converted = send("/api/v1/catalogue/products/" + p + "/convert", Map.of("unit", "BOX", "quantity", "0.5", "purpose", "SALE"), "CASHIER", 200);
        assertEquals(6, converted.get("baseQuantity").asInt());
        send("/api/v1/catalogue/products/" + p + "/convert", Map.of("unit", "BOX", "quantity", "0.01", "purpose", "SALE"), "CASHIER", 400);
        send("/api/v1/catalogue/products/" + p + "/units", unit, "CASHIER", 403);
        getJson("/api/v1/catalogue/products/" + p + "/units", "OTHER", 404);
    }

    @Test
    void internalCodesAreStableAndLabelsHaveBoundedDimensionsAndValidFormats() throws Exception {
        String p = product("LABEL", "OWNER");
        var code = send("/api/v1/catalogue/products/" + p + "/barcode", Map.of(), "OWNER", 200);
        assertTrue(code.get("barcode").asString().startsWith("ORV-"));
        assertEquals(code, send("/api/v1/catalogue/products/" + p + "/barcode", Map.of(), "OWNER", 200));
        for (String format : List.of("svg", "png", "pdf")) {
            var bytes = mvc.perform(get("/api/v1/catalogue/products/" + p + "/label?format=" + format).with(as("OWNER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertTrue(bytes.length > 100);
            if (format.equals("pdf")) assertTrue(new String(bytes, 0, 4).equals("%PDF"));
        }
        getJson("/api/v1/catalogue/products/" + p + "/label?widthMm=10000", "OWNER", 400);
    }

    @Test
    void previewDoesNotWriteProductsAndCommitRetriesOnceWithMapping() throws Exception {
        var request = Map.of("csv", "Code,Name,Description,Price\nNEW1,Imported,Description,10.00\n", "options", Map.of("mapping", Map.of("sku", "Code", "itemName", "Name", "itemDescription", "Description", "unitPrice", "Price"), "duplicatePolicy", "REJECT", "requestId", key()));
        var preview = send("/api/v1/catalogue/imports/csv", request, "OWNER", 201);
        String id = preview.get("id").asString();
        assertEquals(0, db.number("SELECT count(*) FROM products"));
        var command = Map.of("requestId", key());
        send("/api/v1/catalogue/imports/" + id + "/commit", command, "OWNER", 200);
        send("/api/v1/catalogue/imports/" + id + "/commit", command, "OWNER", 200);
        assertEquals(1, db.number("SELECT count(*) FROM products"));
        getJson("/api/v1/catalogue/imports/" + id, "OTHER", 404);
    }

    @Test
    void rowErrorsDuplicatePolicyAndRacesCannotPartiallyCommit() throws Exception {
        String p = product("EXISTING", "OWNER");
        String csv = "sku,itemName,itemDescription,unitPrice\nEXISTING,Replace,Changed,20\nNEW,Valid,Description,20\n";
        var rejected = send("/api/v1/catalogue/imports/csv", Map.of("csv", csv, "options", options("REJECT")), "OWNER", 201);
        send("/api/v1/catalogue/imports/" + rejected.get("id").asString() + "/commit", Map.of("requestId", key()), "OWNER", 409);
        assertEquals(1, db.number("SELECT count(*) FROM products"));
        var job = send("/api/v1/catalogue/imports/csv", Map.of("csv", csv, "options", options("UPDATE")), "OWNER", 201);
        var body = productBody("EXISTING");
        body.put("unitPrice", 30);
        putJson("/api/products/" + p, body, "OWNER", 200);
        send("/api/v1/catalogue/imports/" + job.get("id").asString() + "/commit", Map.of("requestId", key()), "OWNER", 409);
        assertEquals(1, db.number("SELECT count(*) FROM products"));
        var skipped = send("/api/v1/catalogue/imports/csv", Map.of("csv", csv, "options", options("SKIP")), "OWNER", 201);
        send("/api/v1/catalogue/imports/" + skipped.get("id").asString() + "/commit", Map.of("requestId", key()), "OWNER", 200);
        assertEquals(2, db.number("SELECT count(*) FROM products"));
        assertEquals(30, getJson("/api/products/" + p, "OWNER", 200).get("unitPrice").asInt());
    }

    @Test
    void malformedAndFormulaWorkbooksAreRejectedWithoutEvaluation() throws Exception {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> imports.xlsx(new byte[]{1, 2, 3}));
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Products");
            sheet.createRow(0).createCell(0).setCellValue("sku");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            book.write(out);
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> imports.xlsx(out.toByteArray()));
        }
        assertTrue(imports.templateXlsx().length > 100);
        send("/api/v1/catalogue/imports/csv", Map.of("csv", "sku,itemName,itemDescription,unitPrice\nBAD,\"unfinished", "options", options("REJECT")), "OWNER", 400);
        assertTrue(org.osyntra.osyntrainventorymanagement.services.CsvSupport.cell("=1+1").startsWith("\"'"));
    }

    @Test
    void xlsxPreviewCommitsAValidMappedWorkbookAndLabelsDecode() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Products");
            String[] header = {"sku", "itemName", "itemDescription", "unitPrice"};
            sheet.createRow(0);
            var row = sheet.getRow(0);
            for (int i = 0; i < header.length; i++) row.createCell(i).setCellValue(header[i]);
            var item = sheet.createRow(1);
            item.createCell(0).setCellValue("XLSX-SKU");
            item.createCell(1).setCellValue("Workbook product");
            item.createCell(2).setCellValue("Safe strings");
            item.createCell(3).setCellValue(15.50);
            book.write(out);
            bytes = out.toByteArray();
        }
        var file = new org.springframework.mock.web.MockMultipartFile("file", "products.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var optionsPart = new org.springframework.mock.web.MockMultipartFile("options", "options.json", "application/json", mapper.writeValueAsBytes(options("REJECT")));
        var preview = json(mvc.perform(multipart("/api/v1/catalogue/imports/xlsx").file(file).file(optionsPart).with(as("OWNER"))).andExpect(status().isCreated()).andReturn());
        send("/api/v1/catalogue/imports/" + preview.get("id").asString() + "/commit", Map.of("requestId", key()), "OWNER", 200);
        String p = db.one("SELECT id FROM products WHERE sku='XLSX-SKU'").get("id").toString();
        String code = send("/api/v1/catalogue/products/" + p + "/barcode", Map.of(), "OWNER", 200).get("barcode").asString();
        byte[] png = mvc.perform(get("/api/v1/catalogue/products/" + p + "/label?format=png").with(as("OWNER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var bitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
        assertEquals(code, new com.google.zxing.MultiFormatReader().decode(bitmap).getText());
        assertTrue(org.osyntra.osyntrainventorymanagement.services.CsvSupport.cell(Character.toString(9) + "=1+1").contains("'"));
    }

    @Test
    void businessDefaultPropagatesOnlyToInheritingProductsAndCurrencyLocksAtAcquisition() throws Exception {
        String inherited = stocked("INHERITS", 2);
        var explicitBody = productBody("EXPLICIT");
        explicitBody.put("minStockThreshold", 9);
        String explicit = send("/api/products", explicitBody, "OWNER", 201).get("itemId").asString();
        open(explicit, 1, "OWNER", 201);
        int branch = ((Number) db.one("INSERT INTO locations(business_id,name) VALUES(1,'Threshold branch') RETURNING id").get("id")).intValue();
        send("/api/inventory/" + inherited + "?locationId=" + branch, Map.of("quantityOnHand", 1, "reason", "OPENING_COUNT", "requestId", key()), "OWNER", 201);
        putJson("/api/business", Map.of("name", "Test business", "currency", "JMD", "defaultThreshold", 5), "OWNER", 200);
        assertEquals(2, db.number("SELECT count(*) FROM inventory WHERE product_id=? AND reorder_level=5", inherited));
        assertEquals(9, db.number("SELECT reorder_level FROM inventory WHERE product_id=?", explicit));
        putJson("/api/business", Map.of("name", "Test business", "currency", "USD", "defaultThreshold", 5), "OWNER", 409);
    }
}
