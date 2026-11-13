package org.osyntra.osyntrainventorymanagement.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class MvpDatabaseConfig {
    @Bean
    public static BeanFactoryPostProcessor mvpDatabaseGuard(Environment environment) {
        return factory -> {
            if ("postgres".equals(environment.getProperty("osyntra.database")))
                throw new IllegalStateException("The MVP phases run on SQLite. PostgreSQL configuration is retained for the post-MVP schema migration and database-specific verification.");
        };
    }
}
