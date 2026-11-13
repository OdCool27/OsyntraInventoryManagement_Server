package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.config.MvpDatabaseConfig;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseProfileTests {
    @Test
    void retainedPostgresProfileFailsBeforeOpeningOrMigratingDatabase() {
        new ApplicationContextRunner().withUserConfiguration(MvpDatabaseConfig.class)
                .withPropertyValues("osyntra.database=postgres").run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("MVP phases run on SQLite"));
                });
    }
}
