package org.osyntra.osyntrainventorymanagement.config;

import org.osyntra.osyntrainventorymanagement.dtos.*;
import org.osyntra.osyntrainventorymanagement.dtos.product.*;
import org.osyntra.osyntrainventorymanagement.dtos.user.*;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Component
@Order(10)
@ConditionalOnProperty(name = "osyntra.demo.enabled", havingValue = "true")
public class DemoInitializer implements ApplicationRunner {
    private final MvpStore db;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwords;
    private final TenancyService tenancy;
    private final ProductService products;
    private final EmployeeService staff;
    private final InventoryService inventory;
    private final FitmentService fitments;
    private final String password;

    public DemoInitializer(MvpStore db, EmployeeRepository employees, PasswordEncoder passwords, TenancyService tenancy, ProductService products,
                           EmployeeService staff, InventoryService inventory, FitmentService fitments, @Value("${OSYNTRA_DEMO_PASSWORD:}") String password) {
        this.db = db;
        this.employees = employees;
        this.passwords = passwords;
        this.tenancy = tenancy;
        this.products = products;
        this.staff = staff;
        this.inventory = inventory;
        this.fitments = fitments;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (db.number("SELECT count(*) FROM demo_seeds") == 2) return;
        if (password.length() < 12 || password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("The demo profile requires OSYNTRA_DEMO_PASSWORD (12–72 characters, at most 72 UTF-8 bytes)");
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            var operators = db.rows("SELECT u.email FROM users u JOIN platform_staff s ON s.user_id=u.id WHERE s.active=1 AND s.role IN('SUPER_ADMIN','PROVISIONER') AND u.status='ACTIVE' ORDER BY u.id LIMIT 1");
            String operator;
            if (operators.isEmpty()) {
                operator = "operator@demo.osyntra.local";
                Employee e = new Employee("Demo", "Operator", operator, passwords.encode(password), null, "Platform operator", EmployeeRole.OWNER);
                e.setOperator(true);
                e.setBusinessId(null);
                employees.save(e);
                confirmDemoIdentity(operator);
            } else operator = string(operators.getFirst(), "email");
            for (String key : List.of("automotive", "grocery")) {
                if (!db.rows("SELECT seed_key FROM demo_seeds WHERE seed_key=?", key).isEmpty()) continue;
                login(operator, "OPERATOR");
                String ownerEmail = key + ".owner@demo.osyntra.local";
                var owner = new CreateEmployeeRequest("Demo", "Owner", ownerEmail, password, "Owner", EmployeeRole.OWNER);
                int business = integer(tenancy.provision(new TenancyDtos.Provision(
                        key.equals("automotive") ? "Osyntra Demo Auto Parts" : "Osyntra Demo Grocery", "Main", "JMD", 3, owner)), "id");
                confirmDemoIdentity(ownerEmail);
                login(ownerEmail, "OWNER");
                for (EmployeeRole role : List.of(EmployeeRole.MANAGER, EmployeeRole.CLERK, EmployeeRole.CASHIER)) {
                    staff.create(new CreateEmployeeRequest("Demo", role.name(), key + "." + role.name().toLowerCase(Locale.ROOT) + "@demo.osyntra.local", password, role.name(), role));
                    confirmDemoIdentity(key + "." + role.name().toLowerCase(Locale.ROOT) + "@demo.osyntra.local");
                }
                boolean auto = key.equals("automotive");
                var p = products.create(new ProductRequest(auto ? "AF-CIVIC-01" : "RICE-1KG", auto ? "DEMO1001" : "DEMO2001",
                        auto ? "Honda Civic air filter (demo fixture)" : "Rice 1 kg", auto ? "Structured compatibility demonstration fixture" : "Grocery demonstration fixture",
                        new BigDecimal(auto ? "2000.00" : "250.00"), null, new BigDecimal(auto ? "1200.00" : "180.00"), "EACH", 3));
                inventory.open(p.itemId(), null, new StockDtos.Opening(auto ? 6 : 12, 3, "OPENING_COUNT", "Local demonstration fixture", "demo-opening-" + key));
                if (auto) {
                    String fitment = string(fitments.create(new FitmentRequest("Honda", "Civic", 2006, 2011, null, null,
                            "Curated demonstration fixture; not a real manufacturer part guarantee", true)), "id");
                    fitments.link(p.itemId(), UUID.fromString(fitment), false);
                }
                db.update("INSERT INTO demo_seeds(seed_key,business_id) VALUES(?,?)", key, business);
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private void login(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, "unused", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private void confirmDemoIdentity(String email) {
        if (!email.endsWith("@demo.osyntra.local"))
            throw new IllegalArgumentException("Only explicit demo fixtures are preverified");
        db.update("UPDATE users SET email_verified=1 WHERE email=?", email);
        db.update("UPDATE identity_tokens SET consumed_ms=? WHERE user_id IN(SELECT id FROM users WHERE email=?) AND consumed_ms IS NULL", System.currentTimeMillis(), email);
        db.update("UPDATE email_outbox SET status='CANCELLED' WHERE user_id IN(SELECT id FROM users WHERE email=?) AND status IN('PENDING','FAILED')", email);
    }
}
