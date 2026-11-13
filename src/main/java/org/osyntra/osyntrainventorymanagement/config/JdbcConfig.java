package org.osyntra.osyntrainventorymanagement.config;

import org.osyntra.osyntrainventorymanagement.repositories.DatabaseDialect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource, DatabaseDialect dialect) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        if (!dialect.isPostgres()) {
            var fallback = new SQLExceptionSubclassTranslator();
            template.setExceptionTranslator((task, sql, exception) -> {
                // SQLite reports constraints with code 19 instead of a standard SQLState.
                // Mask extended codes so FK, CHECK and UNIQUE violations share HTTP 409.
                if ((exception.getErrorCode() & 0xff) == 19) {
                    return new DataIntegrityViolationException("Database constraint violated", exception);
                }
                return fallback.translate(task, sql, exception);
            });
        }
        return template;
    }
}
