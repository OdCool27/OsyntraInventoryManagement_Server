package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class MigrationUpgradeTests {
    @TempDir
    Path temporary;

    @Test
    void upgradesV6RolesWithoutChangingPasswordsOrTrustingUnverifiedLegacyEmail() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("v6-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite")
                .target(MigrationVersion.fromVersion("6")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(id,first_name,last_name,email,password_hash,status,is_operator) VALUES(1,'Old','Owner','owner@legacy.local','owner-hash','ACTIVE',0),(2,'Old','Clerk','clerk@legacy.local','clerk-hash','ACTIVE',0),(3,'Old','Operator','operator@legacy.local','operator-hash','ACTIVE',1)");
            statement.executeUpdate("INSERT INTO employees(user_id,job_title,employee_role,business_id) VALUES(1,'Owner','OWNER',1),(2,'Clerk','CLERK',1),(3,'Operator','OWNER',NULL)");
        }
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT password_hash,email_verified,mfa_enabled FROM users ORDER BY id")) {
                for (String hash : new String[]{"owner-hash", "clerk-hash", "operator-hash"}) {
                    assertTrue(rows.next());
                    assertEquals(hash, rows.getString(1));
                    assertEquals(0, rows.getInt(2));
                    assertEquals(0, rows.getInt(3));
                }
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT role,all_branches FROM memberships ORDER BY user_id")) {
                assertTrue(rows.next());
                assertEquals("OWNER", rows.getString(1));
                assertEquals(1, rows.getInt(2));
                assertTrue(rows.next());
                assertEquals("INVENTORY_CLERK", rows.getString(1));
                assertEquals(1, rows.getInt(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT user_id,role FROM platform_staff")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertEquals("SUPER_ADMIN", rows.getString(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM employees")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void upgradesPopulatedBaselineWithoutLosingAccountsStockOrAudit() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite")
                .target(MigrationVersion.fromVersion("1")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(first_name,last_name,email,password_hash,status) VALUES('Existing','Owner','existing@test.local','preserved-hash','ACTIVE')");
            statement.executeUpdate("INSERT INTO employees(user_id,job_title,employee_role) VALUES(1,'Owner','OWNER')");
            statement.executeUpdate("INSERT INTO categories(name) VALUES('Existing category')");
            statement.executeUpdate("INSERT INTO products(id,sku,item_name,item_description,unit_price,category_id) VALUES('00000000-0000-0000-0000-000000000001','EXISTING','Existing product','Keep me',10,1)");
            statement.executeUpdate("INSERT INTO inventory(product_id,quantity_on_hand,quantity_reserved) VALUES('00000000-0000-0000-0000-000000000001',5,1)");
            statement.executeUpdate("INSERT INTO audit_logs(user_id,action,entity_type,entity_id) VALUES(1,'BASELINE','PRODUCT','00000000-0000-0000-0000-000000000001')");
        }
        var flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite").load();
        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT business_id,location_id,quantity_on_hand,quantity_reserved FROM inventory")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
                assertEquals(1, row.getInt(2));
                assertEquals(5, row.getInt(3));
                assertEquals(1, row.getInt(4));
            }
            try (var row = statement.executeQuery("SELECT password_hash FROM users")) {
                assertTrue(row.next());
                assertEquals("preserved-hash", row.getString(1));
            }
            try (var row = statement.executeQuery("SELECT sum(delta_on_hand),sum(delta_reserved) FROM stock_movements")) {
                assertTrue(row.next());
                assertEquals(5, row.getInt(1));
                assertEquals(1, row.getInt(2));
            }
            try (var row = statement.executeQuery("SELECT business_id FROM audit_logs")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
            try (var row = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(row.next());
            }
        }
    }

    @Test
    void upgradesV7OrdersWithoutInventingHistoricalTaxOrChangingPayments() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("v7-order-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite")
                .target(MigrationVersion.fromVersion("7")).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO products(id,sku,item_name,item_description,unit_price,business_id) VALUES('00000000-0000-0000-0000-000000000008','LEGACY','Old item','Preserve',10,1)");
            statement.executeUpdate("INSERT INTO orders(id,business_id,location_id,channel,status,expires_at_ms,total_cents,currency) VALUES('old-order',1,1,'POS','COMPLETED',0,2000,'JMD')");
            statement.executeUpdate("INSERT INTO order_lines(order_id,business_id,product_id,sku,item_name,quantity,price_cents,cost_cents) VALUES('old-order',1,'00000000-0000-0000-0000-000000000008','LEGACY','Old item',2,1000,500)");
            statement.executeUpdate("INSERT INTO simulated_payments(order_id,business_id,method,successful,amount_cents) VALUES('old-order',1,'CASH',1,2000)");
        }
        var flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite").load();
        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT total_cents,net_cents,tax_cents,tax_snapshot_version,seller_registration_status FROM orders")) {
                assertTrue(row.next());
                assertEquals(2000, row.getLong(1));
                assertEquals(2000, row.getLong(2));
                assertEquals(0, row.getLong(3));
                assertEquals(0, row.getInt(4));
                assertEquals("LEGACY_UNSPECIFIED", row.getString(5));
            }
            try (var row = statement.executeQuery("SELECT price_cents,cost_cents,net_cents,tax_cents,total_cents,tax_classification FROM order_lines")) {
                assertTrue(row.next());
                assertEquals(1000, row.getLong(1));
                assertEquals(500, row.getLong(2));
                assertEquals(2000, row.getLong(3));
                assertEquals(0, row.getLong(4));
                assertEquals(2000, row.getLong(5));
                assertEquals("LEGACY_UNSPECIFIED", row.getString(6));
            }
            try (var row = statement.executeQuery("SELECT amount_cents FROM simulated_payments")) {
                assertTrue(row.next());
                assertEquals(2000, row.getLong(1));
            }
            try (var row = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(row.next());
            }
        }
    }

    @Test
    void upgradesPopulatedV8PreservingBalancesPaymentsAndArchivingOldThresholds() throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("v8-mvp-upgrade.db") + "?foreign_keys=on";
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite").target(MigrationVersion.fromVersion("8")).load().migrate();
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO locations(id,business_id,name) VALUES(2,1,'Second')");
            s.executeUpdate("INSERT INTO products(id,business_id,sku,item_name,item_description,unit_price,cost) VALUES('00000000-0000-0000-0000-000000000009',1,'V8','Preserved','Original',10,10)");
            s.executeUpdate("INSERT INTO inventory(product_id,business_id,location_id,quantity_on_hand,quantity_reserved,reorder_level) VALUES('00000000-0000-0000-0000-000000000009',1,1,5,1,2),('00000000-0000-0000-0000-000000000009',1,2,3,0,7)");
            s.executeUpdate("INSERT INTO orders(id,business_id,location_id,channel,status,expires_at_ms,total_cents,currency,net_cents) VALUES('v8-order',1,1,'POS','COMPLETED',0,1000,'JMD',1000)");
            s.executeUpdate("INSERT INTO order_lines(order_id,business_id,product_id,sku,item_name,quantity,price_cents,cost_cents,net_cents,total_cents) VALUES('v8-order',1,'00000000-0000-0000-0000-000000000009','V8','Preserved',1,1000,300,1000,1000)");
            s.executeUpdate("INSERT INTO simulated_payments(order_id,business_id,method,successful,amount_cents) VALUES('v8-order',1,'CASH',1,1000)");
        }
        var flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration/sqlite").load();
        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT sum(quantity_on_hand),sum(quantity_reserved),sum(CAST(stock_value AS NUMERIC)) FROM inventory")) {
                assertTrue(r.next());
                assertEquals(8, r.getInt(1));
                assertEquals(1, r.getInt(2));
                assertEquals(80, r.getInt(3));
            }
            try (var r = s.executeQuery("SELECT count(*),min(reorder_level),max(reorder_level) FROM legacy_reorder_levels")) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
                assertEquals(2, r.getInt(2));
                assertEquals(7, r.getInt(3));
            }
            try (var r = s.executeQuery("SELECT provider,amount_cents FROM payment_records")) {
                assertTrue(r.next());
                assertEquals("SIMULATOR", r.getString(1));
                assertEquals(1000, r.getLong(2));
            }
            try (var r = s.executeQuery("SELECT quantity,price_cents,cost_cents,total_cents FROM order_lines")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertEquals(1000, r.getInt(2));
                assertEquals(300, r.getInt(3));
                assertEquals(1000, r.getInt(4));
            }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(r.next());
            }
        }
    }
}
