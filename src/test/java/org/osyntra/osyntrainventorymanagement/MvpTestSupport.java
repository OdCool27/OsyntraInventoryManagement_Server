package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.BeforeEach;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.http.MediaType;
import tools.jackson.databind.*;

import java.util.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/osyntra-api-test-${random.uuid}.db")
@ActiveProfiles("test")
@AutoConfigureMockMvc
abstract class MvpTestSupport {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MvpStore db;
    @Autowired
    EmployeeRepository employees;
    @Autowired
    PasswordEncoder passwords;
    static final String PASSWORD = "Test-only-password-123";
    int otherBusiness, otherLocation, ownerId, clerkId;
    private static String passwordHash;

    @BeforeEach
    void fixtures() {
        // Real request transactions are essential: an outer test transaction would hide request rollbacks.
        for (String table : List.of("promotion_revocations", "legacy_reorder_levels", "sale_reversal_lines", "sale_reversals", "sale_receipts", "payment_events", "payment_attempts", "payment_records", "checkout_promotions", "branch_price_versions", "goods_receipt_lines", "goods_receipts", "purchase_order_lines", "purchase_orders", "suppliers", "stock_transfer_lines", "stock_transfers", "order_batch_allocations", "batch_movements", "stock_batches", "sale_cost_snapshots", "valuation_adjustments", "catalogue_import_rows", "catalogue_imports", "product_units", "onboarding_steps", "branch_preferences", "product_tax_versions", "tax_rate_versions", "tax_profiles", "tax_policy_versions", "business_preferences", "email_outbox", "identity_tokens", "identity_sessions", "mfa_recovery_codes", "invitation_branches", "invitations", "identity_audit", "membership_branches", "platform_staff", "memberships", "auth_rate_limits",
                "order_events", "simulated_refunds", "simulated_payments", "order_lines", "orders", "command_receipts",
                "stock_movements", "stock_count_lines", "stock_counts", "opening_import_lines", "opening_imports", "product_fitments",
                "vehicle_fitments", "inventory", "products", "categories", "platform_audit", "employees", "audit_logs", "users",
                "locations", "movement_reasons", "demo_seeds", "businesses"))
            db.update("DELETE FROM " + table);
        db.update("INSERT INTO businesses(id,name) VALUES(1,'Test business')");
        db.update("INSERT INTO locations(id,business_id,name) VALUES(1,1,'Main')");
        for (String reason : List.of("OPENING_COUNT", "RECEIPT", "DAMAGE", "CORRECTION", "COUNT_APPROVAL"))
            db.update("INSERT INTO movement_reasons(business_id,code,label) VALUES(1,?,?)", reason, reason);
        if (passwordHash == null) passwordHash = passwords.encode(PASSWORD);
        ownerId = employee("owner@test.local", EmployeeRole.OWNER, 1, false);
        employee("manager@test.local", EmployeeRole.MANAGER, 1, false);
        clerkId = employee("clerk@test.local", EmployeeRole.CLERK, 1, false);
        employee("cashier@test.local", EmployeeRole.CASHIER, 1, false);
        employee("operator@test.local", EmployeeRole.OWNER, null, true);
        otherBusiness = MvpStore.integer(db.one("INSERT INTO businesses(name) VALUES('Other test business') RETURNING id"), "id");
        otherLocation = MvpStore.integer(db.one("INSERT INTO locations(business_id,name) VALUES(?,'Main') RETURNING id", otherBusiness), "id");
        for (String reason : List.of("OPENING_COUNT", "RECEIPT", "DAMAGE", "CORRECTION", "COUNT_APPROVAL"))
            db.update("INSERT INTO movement_reasons(business_id,code,label) VALUES(?,?,?)", otherBusiness, reason, reason);
        employee("other@test.local", EmployeeRole.OWNER, otherBusiness, false);
    }

    int employee(String email, EmployeeRole role, Integer business, boolean operator) {
        Employee e = new Employee("Test", "Employee", email, passwordHash, null, "Test", role);
        e.setBusinessId(business);
        e.setOperator(operator);
        employees.save(e);
        db.update("UPDATE users SET email_verified=1 WHERE id=?", e.getId());
        return e.getId();
    }

    RequestPostProcessor as(String role) {
        String name = role.equals("OTHER") ? "other" : role.equals("OPERATOR") ? "operator" : role.toLowerCase(Locale.ROOT);
        return user(name + "@test.local").roles(role.equals("OTHER") ? "OWNER" : role);
    }

    JsonNode send(String path, Object body, String role, int expected) throws Exception {
        return json(mvc.perform(post(path).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn());
    }

    JsonNode putJson(String path, Object body, String role, int expected) throws Exception {
        return json(mvc.perform(put(path).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn());
    }

    JsonNode getJson(String path, String role, int expected) throws Exception {
        return json(mvc.perform(get(path).with(as(role))).andExpect(status().is(expected)).andReturn());
    }

    JsonNode json(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString();
        return mapper.readTree(text.isEmpty() ? "null" : text);
    }

    Map<String, Object> productBody(String sku) {
        return new LinkedHashMap<>(Map.of("sku", sku, "itemName", "Test item", "itemDescription", "Verified test catalog item", "unitPrice", 25.50, "cost", 10.00, "unitOfMeasure", "EACH"));
    }

    String product(String sku, String role) throws Exception {
        return send("/api/products", productBody(sku), role, 201).get("itemId").asString();
    }

    String stocked(String sku, int quantity) throws Exception {
        String id = product(sku, "OWNER");
        open(id, quantity, "OWNER", 201);
        return id;
    }

    JsonNode open(String product, int quantity, String role, int status) throws Exception {
        return send("/api/inventory/" + product, Map.of("quantityOnHand", quantity, "reason", "OPENING_COUNT", "requestId", UUID.randomUUID().toString()), role, status);
    }

    Map<String, Object> movement(String type, int qty, String key) {
        return Map.of("type", type, "quantity", qty, "reason", type, "requestId", key);
    }

    Map<String, Object> checkout(String product, int qty, String key) {
        return Map.of("lines", List.of(Map.of("productId", product, "quantity", qty)), "requestId", key);
    }

    String order(String product, int qty) throws Exception {
        return send("/api/orders", checkout(product, qty, UUID.randomUUID().toString()), "CASHIER", 201).get("id").asString();
    }

    Map<String, Object> payment(boolean success, String key) {
        return Map.of("method", "CARD", "successful", success, "requestId", key);
    }

    Map<String, Object> action(String key) {
        return Map.of("reason", "Test reason", "requestId", key);
    }

    long quantity(String product) {
        return db.number("SELECT quantity_on_hand FROM inventory WHERE business_id=1 AND location_id=1 AND product_id=?", product);
    }

    long reserved(String product) {
        return db.number("SELECT quantity_reserved FROM inventory WHERE business_id=1 AND location_id=1 AND product_id=?", product);
    }
}
