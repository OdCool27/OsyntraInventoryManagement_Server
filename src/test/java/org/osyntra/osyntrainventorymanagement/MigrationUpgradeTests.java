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
}
