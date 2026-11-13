package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class InventoryApiTests extends MvpTestSupport {
    @Test
    void categoryCrudIsTenantScopedAndProtectsProductReferences() throws Exception {
        int id = send("/api/categories", Map.of("name", "Original"), "OWNER", 201).get("id").asInt();
        putJson("/api/categories/" + id, Map.of("name", "Renamed"), "MANAGER", 200);
        assertEquals("Renamed", getJson("/api/categories/" + id, "CLERK", 200).get("name").asString());
        getJson("/api/categories/" + id, "OTHER", 404);
        var product = productBody("CATEGORIZED");
        product.put("categoryId", id);
        send("/api/products", product, "OWNER", 201);
        mvc.perform(delete("/api/categories/" + id).with(as("OWNER"))).andExpect(status().isConflict());
        int unused = send("/api/categories", Map.of("name", "Unused"), "OWNER", 201).get("id").asInt();
        mvc.perform(delete("/api/categories/" + unused).with(as("MANAGER"))).andExpect(status().isNoContent());
    }

    @Test
    void isolatesCatalogStockCategoriesLocationsAuditAndReports() throws Exception {
        String own = stocked("SHARED-SKU", 5);
        String other = product("SHARED-SKU", "OTHER");
        open(other, 8, "OTHER", 201);
        assertEquals(1, getJson("/api/products", "OWNER", 200).size());
        getJson("/api/products/" + other, "OWNER", 404);
        putJson("/api/products/" + other, productBody("TAMPER"), "OWNER", 404);
        send("/api/inventory/" + other + "/movements", movement("RECEIPT", 1, "tamper"), "OWNER", 404);
        getJson("/api/inventory/" + own + "?locationId=" + otherLocation, "OWNER", 404);
        assertEquals(5, getJson("/api/reports/summary", "OWNER", 200).get("total_units").asInt());
        assertEquals(0, getJson("/api/audit?entityId=" + other, "OWNER", 200).size());
        var category = send("/api/categories", Map.of("name", "Other"), "OTHER", 201).get("id").asInt();
        var request = productBody("WRONG-CATEGORY");
        request.put("categoryId", category);
        send("/api/products", request, "OWNER", 404);
        mvc.perform(get("/api/products").with(as("OWNER")).header("X-Business-Id", otherBusiness))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].itemId").value(own));
    }

    @Test
    void databaseRejectsCrossTenantReferencesAndInvalidBalances() throws Exception {
        String p = stocked("DB-CONSTRAINT", 1);
        assertEquals(1, db.number("PRAGMA foreign_keys"));
        assertEquals("wal", db.one("PRAGMA journal_mode").get("journal_mode"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("INSERT INTO inventory(product_id,business_id,location_id) VALUES(?,?,?)", p, otherBusiness, otherLocation));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("UPDATE inventory SET quantity_on_hand=-1 WHERE product_id=?", p));
    }

    @Test
    void operatorProvisionsButCannotReadTenantBusinessData() throws Exception {
        var owner = Map.of("firstName", "New", "lastName", "Owner", "email", "new@test.local", "password", PASSWORD, "jobTitle", "Owner", "role", "OWNER");
        var business = send("/api/platform/businesses", Map.of("name", "Provisioned", "locationName", "Shop", "owner", owner), "OPERATOR", 201);
        assertTrue(business.get("id").asInt() > otherBusiness);
        getJson("/api/platform/businesses", "OWNER", 403);
        getJson("/api/products", "OPERATOR", 403);
        getJson("/api/audit", "OPERATOR", 403);
        assertEquals(1, db.number("SELECT count(*) FROM platform_audit WHERE business_id=?", business.get("id").asInt()));
        var employee = employees.findByEmail("new@test.local").orElseThrow();
        assertEquals(business.get("id").asInt(), employee.getBusinessId());
        assertTrue(passwords.matches(PASSWORD, employee.getPasswordHash()));
    }

    @Test
    void employeeLifecyclePreventsEscalationAndLastOwnerRemoval() throws Exception {
        var request = new LinkedHashMap<String, Object>(Map.of("firstName", "Worker", "lastName", "One", "email", "worker@test.local", "password", PASSWORD, "jobTitle", "Clerk", "role", "CLERK"));
        var employee = send("/api/employees", request, "MANAGER", 201);
        request.put("role", "OWNER");
        request.put("email", "privileged@test.local");
        send("/api/employees", request, "MANAGER", 403);
        var edit = Map.of("firstName", "Worker", "lastName", "One", "jobTitle", "Clerk", "role", "CLERK", "status", "DEACTIVATED");
        putJson("/api/employees/" + employee.get("id").asInt(), edit, "MANAGER", 200);
        mvc.perform(get("/api/auth/me").with(httpBasic("worker@test.local", PASSWORD))).andExpect(status().isUnauthorized());
        putJson("/api/employees/" + ownerId, edit, "OWNER", 409);
        putJson("/api/employees/" + ownerId, edit, "MANAGER", 403);
        putJson("/api/employees/" + employee.get("id").asInt(), edit, "OTHER", 404);
    }

    @Test
    void authenticatesAndEnforcesOperationalRoles() throws Exception {
        mvc.perform(get("/api/auth/me").with(httpBasic("owner@test.local", PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.businessId").value(1));
        mvc.perform(get("/api/auth/me").with(httpBasic("owner@test.local", "bad"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        String p = stocked("PERMISSION", 1);
        for (String role : List.of("CLERK", "CASHIER")) {
            send("/api/products", productBody("DENIED"), role, 403);
            send("/api/inventory/" + p + "/movements", movement("RECEIPT", 1, role), role, 403);
            getJson("/api/reports/summary", role, 403);
        }
        db.update("UPDATE users SET status='SUSPENDED' WHERE email='owner@test.local'");
        mvc.perform(get("/api/auth/me").with(httpBasic("owner@test.local", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void catalogLookupArchiveUniquenessAndValidation() throws Exception {
        var body = productBody("SKU-ONE");
        body.put("barcode", " 123456789 ");
        body.put("minStockThreshold", 7);
        String p = send("/api/products", body, "OWNER", 201).get("itemId").asString();
        assertEquals(1, getJson("/api/products?barcode=123456789", "CLERK", 200).size());
        send("/api/products", body, "OWNER", 409);
        body.put("unitPrice", -1);
        send("/api/products", body, "OWNER", 400);
        send("/api/products", Map.of(), "OWNER", 400);
        mvc.perform(delete("/api/products/" + p).with(as("MANAGER"))).andExpect(status().isNoContent());
        assertEquals(0, getJson("/api/products", "CLERK", 200).size());
        assertFalse(getJson("/api/products/" + p, "OWNER", 200).get("active").asBoolean());
        assertEquals(1, getJson("/api/products?includeInactive=true", "OWNER", 200).size());
        getJson("/api/products?limit=0", "OWNER", 400);
    }

    @Test
    void verifiedRelationalFitmentSupportsMultipleProductsAndRanges() throws Exception {
        String a = product("AIR-ONE", "OWNER"), b = product("AIR-TWO", "OWNER");
        var request = new LinkedHashMap<String, Object>(Map.of("make", "Honda", "model", "Civic", "yearFrom", 2006, "yearTo", 2011, "source", "Verified manufacturer catalog fixture", "verified", true));
        String f = send("/api/fitments", request, "MANAGER", 201).get("id").asString();
        send("/api/products/" + a + "/fitments/" + f, Map.of(), "OWNER", 200);
        send("/api/products/" + b + "/fitments/" + f, Map.of(), "OWNER", 200);
        assertEquals(2, getJson("/api/products?make=honda&model=civic&year=2009", "CLERK", 200).size());
        assertEquals(0, getJson("/api/products?make=Honda&model=Civic&year=2012", "CLERK", 200).size());
        request.put("engine", "1.8L");
        String specific = send("/api/fitments", request, "OWNER", 201).get("id").asString();
        String c = product("ENGINE-SPECIFIC", "OWNER");
        send("/api/products/" + c + "/fitments/" + specific, Map.of(), "OWNER", 200);
        assertEquals(2, getJson("/api/products?make=Honda&model=Civic&year=2009", "OWNER", 200).size());
        assertEquals(3, getJson("/api/products?make=Honda&model=Civic&year=2009&engine=1.8L", "OWNER", 200).size());
        request.put("verified", false);
        send("/api/fitments", request, "OWNER", 400);
        send("/api/products/" + a + "/fitments/" + f, Map.of(), "OTHER", 404);
        String other = product("OTHER-PART", "OTHER");
        send("/api/products/" + other + "/fitments/" + f, Map.of(), "OTHER", 404);
    }

    @Test
    void movementsAreReasonCodedAtomicAndIdempotent() throws Exception {
        String p = stocked("MOVEMENT", 5);
        var move = movement("DAMAGE", 2, "damage-once");
        send("/api/inventory/" + p + "/movements", move, "MANAGER", 200);
        send("/api/inventory/" + p + "/movements", move, "MANAGER", 200);
        assertEquals(3, quantity(p));
        send("/api/inventory/" + p + "/movements", movement("DAMAGE", 3, "damage-once"), "OWNER", 409);
        send("/api/inventory/" + p + "/movements", movement("DAMAGE", 4, "too-many"), "OWNER", 409);
        send("/api/inventory/" + p + "/movements", movement("RECEIPT", 0, "zero"), "OWNER", 400);
        assertEquals(2, db.number("SELECT count(*) FROM stock_movements WHERE product_id=?", p));
        assertEquals(quantity(p), db.number("SELECT sum(delta_on_hand) FROM stock_movements WHERE product_id=?", p));
        assertEquals(1, getJson("/api/inventory/alerts", "OWNER", 200).size());
        send("/api/inventory/" + p + "/movements", Map.of("type", "DAMAGE", "quantity", 1, "requestId", "no-reason"), "OWNER", 400);
    }

    @Test
    void stockIsLocationSpecificAndOverflowIsRejected() throws Exception {
        String p = stocked("LOCATION", Integer.MAX_VALUE);
        send("/api/inventory/" + p + "/movements", movement("RECEIPT", 1, "overflow"), "OWNER", 409);
        int location = send("/api/locations", Map.of("name", "Back room"), "OWNER", 201).get("id").asInt();
        send("/api/inventory/" + p + "?locationId=" + location, Map.of("quantityOnHand", 2, "reason", "OPENING_COUNT", "requestId", "backroom"), "OWNER", 201);
        assertEquals(Integer.MAX_VALUE, quantity(p));
        assertEquals(2, getJson("/api/inventory/" + p + "?locationId=" + location, "OWNER", 200).get("quantity_on_hand").asInt());
    }

    @Test
    void resumableCountNeedsApprovalAndDetectsStockDrift() throws Exception {
        String p = stocked("COUNT-SKU", 5);
        String count = send("/api/counts", Map.of(), "CLERK", 201).get("id").asString();
        putJson("/api/counts/" + count + "/lines", Map.of("code", "COUNT-SKU", "quantity", 3), "CLERK", 200);
        assertEquals(3, getJson("/api/counts/" + count, "CLERK", 200).get("lines").get(0).get("counted_quantity").asInt());
        send("/api/counts/" + count + "/submit", Map.of(), "CLERK", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "CLERK", 403);
        send("/api/inventory/" + p + "/movements", movement("RECEIPT", 1, "drift"), "OWNER", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "MANAGER", 409);
        assertEquals(6, quantity(p));
        send("/api/counts/" + count + "/reopen", Map.of(), "MANAGER", 200);
        putJson("/api/counts/" + count + "/lines", Map.of("code", "COUNT-SKU", "quantity", 3), "CLERK", 200);
        send("/api/counts/" + count + "/submit", Map.of(), "CLERK", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "MANAGER", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "MANAGER", 200);
        assertEquals(3, quantity(p));
        assertEquals(1, db.number("SELECT count(*) FROM stock_movements WHERE reference_id=? AND movement_type='COUNT'", count));
        getJson("/api/counts/" + count, "OTHER", 404);
    }

    @Test
    void openingCountsCanInitializePreviouslyUnstockedProducts() throws Exception {
        String p = product("COUNT-OPENING", "OWNER");
        String count = send("/api/counts", Map.of(), "CLERK", 201).get("id").asString();
        putJson("/api/counts/" + count + "/lines", Map.of("code", "COUNT-OPENING", "quantity", 4), "CLERK", 200);
        send("/api/counts/" + count + "/submit", Map.of(), "CLERK", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "OWNER", 200);
        assertEquals(4, quantity(p));
    }

    @Test
    void csvPreviewShowsErrorsAndCommitIsAllOrNothing() throws Exception {
        String p = product("CSV-ONE", "OWNER"), q = product("CSV-TWO", "OWNER");
        String invalid = send("/api/imports/opening/preview", Map.of("csv", "sku,quantity,reorderLevel\nCSV-ONE,4,2\nMISSING,-1,0\n"), "OWNER", 200).get("id").asString();
        send("/api/imports/opening/" + invalid + "/commit", Map.of(), "OWNER", 409);
        assertEquals(0, db.number("SELECT count(*) FROM inventory WHERE product_id=?", p));
        String valid = send("/api/imports/opening/preview", Map.of("csv", "sku,quantity,reorderLevel\r\n\"CSV-ONE\",4,2\r\nCSV-TWO,3,1\r\n"), "OWNER", 200).get("id").asString();
        send("/api/imports/opening/" + valid + "/commit", Map.of(), "MANAGER", 200);
        send("/api/imports/opening/" + valid + "/commit", Map.of(), "MANAGER", 200);
        assertEquals(4, quantity(p));
        assertEquals(3, quantity(q));
        getJson("/api/imports/opening/" + valid, "OTHER", 404);
    }

    @Test
    void swaggerDocumentsNewEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/orders/{id}/payment']").exists())
                .andExpect(jsonPath("$.paths['/api/counts/{id}/approve']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void scannerCodesAreUnambiguousAndCsvRejectsMalformedOrDuplicateRows() throws Exception {
        var body = productBody("CODE-A");
        body.put("barcode", "CODE-B");
        send("/api/products", body, "OWNER", 201);
        send("/api/products", productBody("CODE-B"), "OWNER", 409);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> org.osyntra.osyntrainventorymanagement.services.CsvSupport.parse("sku,quantity,reorderLevel\n\"unclosed"));
        var preview = send("/api/imports/opening/preview", Map.of("csv", "sku,quantity,reorderLevel\nCODE-A,1,0\nCODE-A,2,0\n"), "OWNER", 200);
        assertFalse(preview.get("lines").get(1).get("error").isNull());
        send("/api/imports/opening/" + preview.get("id").asString() + "/commit", Map.of(), "OWNER", 409);
    }

    @Test
    void approvalCannotCountAwayCheckoutReservations() throws Exception {
        String p = stocked("RESERVED-COUNT", 3);
        order(p, 2);
        String count = send("/api/counts", Map.of(), "CLERK", 201).get("id").asString();
        putJson("/api/counts/" + count + "/lines", Map.of("code", "RESERVED-COUNT", "quantity", 1), "CLERK", 200);
        send("/api/counts/" + count + "/submit", Map.of(), "CLERK", 200);
        send("/api/counts/" + count + "/approve", Map.of(), "MANAGER", 409);
        assertEquals(3, quantity(p));
        assertEquals(2, reserved(p));
    }

    @Test
    void corsAllowsConfiguredSeparateFrontendAndRejectsUnknownOrigins() throws Exception {
        mvc.perform(options("/api/products").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/products").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
