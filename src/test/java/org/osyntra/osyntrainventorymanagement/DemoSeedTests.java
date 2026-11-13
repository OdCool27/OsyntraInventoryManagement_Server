package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.config.DemoInitializer;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"osyntra.demo.enabled=true", "OSYNTRA_DEMO_PASSWORD=Test-only-demo-password-123"})
@ActiveProfiles("test")
@DirtiesContext
class DemoSeedTests {
    @Autowired
    MvpStore db;
    @Autowired
    DemoInitializer seed;

    @Test
    void seedsTwoIsolatedTenantsAndCanRunAgainWithoutDuplicatingData() {
        assertEquals(2, db.number("SELECT count(*) FROM demo_seeds"));
        assertEquals(2, db.number("SELECT count(DISTINCT business_id) FROM products"));
        assertEquals(2, db.number("SELECT count(DISTINCT business_id) FROM inventory"));
        assertEquals(8, db.number("SELECT count(*) FROM employees WHERE business_id IN(SELECT business_id FROM demo_seeds)"));
        assertEquals(1, db.number("SELECT count(*) FROM product_fitments"));
        seed.run(new DefaultApplicationArguments());
        assertEquals(2, db.number("SELECT count(*) FROM products"));
    }
}
