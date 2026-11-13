package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import tools.jackson.databind.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/osyntra-concurrency-${random.uuid}.db")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext
class InventoryConcurrencyTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    EmployeeRepository employees;
    @Autowired
    MvpStore db;

    @Test
    void simultaneousPosAndOnlineCheckoutOfLastUnitAndDuplicatePaymentAreSafe() throws Exception {
        employees.save(new Employee("Test", "Owner", "concurrent-owner@test.local", "unused-hash", null, "Owner", EmployeeRole.OWNER));
        employees.save(new Employee("Test", "Cashier", "concurrent-cashier@test.local", "unused-hash", null, "Cashier", EmployeeRole.CASHIER));
        String product = mapper.readTree(mvc.perform(post("/api/products").with(user("concurrent-owner@test.local").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("sku", "LAST", "itemName", "Last item", "itemDescription", "Concurrency test", "unitPrice", 10))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("itemId").asString();
        mvc.perform(post("/api/inventory/" + product).with(user("concurrent-owner@test.local").roles("OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("quantityOnHand", 1, "reason", "OPENING_COUNT", "requestId", "concurrent-opening")))).andExpect(status().isCreated());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var pos = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return reserve(product, false);
            });
            var online = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return reserve(product, true);
            });
            start.countDown();
            int first = pos.get(20, TimeUnit.SECONDS), second = online.get(20, TimeUnit.SECONDS);
            assertTrue((first == 201 && second == 409) || (first == 409 && second == 201));
            assertEquals(1, db.number("SELECT count(*) FROM orders"));
            assertEquals(1, db.number("SELECT quantity_reserved FROM inventory WHERE product_id=?", product));
            String id = db.one("SELECT id FROM orders").get("id").toString();
            CountDownLatch payStart = new CountDownLatch(1);
            Callable<Integer> payment = () -> {
                assertTrue(payStart.await(10, TimeUnit.SECONDS));
                return mvc.perform(post("/api/orders/" + id + "/payment").with(user("concurrent-cashier@test.local").roles("CASHIER"))
                                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("method", "CARD", "successful", true, "requestId", "same-payment"))))
                        .andReturn().getResponse().getStatus();
            };
            var payOne = executor.submit(payment);
            var payTwo = executor.submit(payment);
            payStart.countDown();
            assertEquals(200, payOne.get(20, TimeUnit.SECONDS));
            assertEquals(200, payTwo.get(20, TimeUnit.SECONDS));
            assertEquals(0, db.number("SELECT quantity_on_hand FROM inventory WHERE product_id=?", product));
            assertEquals(0, db.number("SELECT quantity_reserved FROM inventory WHERE product_id=?", product));
            assertEquals(1, db.number("SELECT count(*) FROM simulated_payments"));
            assertEquals(1, db.number("SELECT count(*) FROM stock_movements WHERE movement_type='SALE'"));
        }
    }

    private int reserve(String product, boolean online) throws Exception {
        var request = post(online ? "/api/storefront/1/orders" : "/api/orders");
        if (!online) request.with(user("concurrent-cashier@test.local").roles("CASHIER"));
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("lines", List.of(Map.of("productId", product, "quantity", 1)), "requestId", online ? "online" : "pos"))))
                .andReturn().getResponse().getStatus();
    }
}
