package org.osyntra.osyntrainventorymanagement.repositories;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.Calendar;
import java.util.TimeZone;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The few JDBC/SQL differences used by the local MVP and retained PostgreSQL setup.
 */
@Component
public class DatabaseDialect {
    private final boolean postgres;

    public DatabaseDialect(@Value("${osyntra.database}") String database) {
        if (!database.equals("sqlite") && !database.equals("postgres")) {
            throw new IllegalArgumentException("Unsupported database: " + database);
        }
        postgres = database.equals("postgres");
    }

    public Object id(UUID id) {
        return postgres ? id : id.toString();
    }

    public String lock() {
        // SQLite uses IMMEDIATE transactions instead of unsupported row locks.
        return postgres ? " FOR UPDATE" : "";
    }

    public String now() {
        return postgres ? "clock_timestamp()" : "strftime('%Y-%m-%d %H:%M:%f', 'now')";
    }

    public boolean isPostgres() {
        return postgres;
    }

    /**
     * SQLite's stored text is UTC; never interpret it in the host computer's timezone.
     */
    public static Instant utcInstant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant();
    }
}
