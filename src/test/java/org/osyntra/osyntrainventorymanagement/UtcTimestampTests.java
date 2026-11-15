package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.repositories.DatabaseDialect;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtcTimestampTests {
    @Test
    void sqliteUtcTextIsNotShiftedByTheServersDefaultTimezone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Jamaica"));
            try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT '2026-11-13 12:34:56.789' AS created_at")) {
                result.next();
                assertEquals(Instant.parse("2026-11-13T12:34:56.789Z"), DatabaseDialect.utcInstant(result, "created_at"));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
