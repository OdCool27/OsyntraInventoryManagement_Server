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
    @Autowired
    org.osyntra.osyntrainventorymanagement.identity.IdentityService identity;
    @Autowired
    org.osyntra.osyntrainventorymanagement.identity.SessionService sessions;

    @Test
    void everySeededDemoAccountSignsInWithoutVerificationOrMfaEvenIfPreviouslyEnrolled() {
        var emails = new java.util.ArrayList<String>();
        emails.add("operator@demo.osyntra.local");
        for (String tenant : java.util.List.of("automotive", "grocery"))
            for (String role : java.util.List.of("owner", "manager", "clerk", "cashier"))
                emails.add(tenant + "." + role + "@demo.osyntra.local");
        for (String email : emails) {
            db.update("UPDATE users SET mfa_enabled=1 WHERE email=?", email);
            var grant = identity.login(new org.osyntra.osyntrainventorymanagement.identity.IdentityDtos.Login(email, "Test-only-demo-password-123"));
            assertEquals("FULL", grant.get("purpose"), email);
            assertEquals(false, grant.get("mfaRequired"), email);
            var principal = (org.osyntra.osyntrainventorymanagement.identity.SessionPrincipal)
                    sessions.authenticate((String) grant.get("accessToken")).getPrincipal();
            assertEquals("FULL", principal.purpose(), email);
            db.update("UPDATE users SET mfa_enabled=0 WHERE email=?", email);
        }
    }

    @Test
    void demoExceptionRequiresAnExplicitDemoOrTestProfileAndSeedMembership() {
        int demoOwner = MvpStore.integer(db.one("SELECT id FROM users WHERE email='automotive.owner@demo.osyntra.local'"), "id");
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("osyntra.demo.enabled", "true");
        var outsideDemo = new org.osyntra.osyntrainventorymanagement.identity.SessionService(db, java.time.Clock.systemUTC(), environment);
        assertFalse(outsideDemo.passwordOnlyDemo(demoOwner));
        assertTrue(outsideDemo.mfaRequired(demoOwner));
        environment.setActiveProfiles("demo", "production");
        assertFalse(new org.osyntra.osyntrainventorymanagement.identity.SessionService(db, java.time.Clock.systemUTC(), environment).passwordOnlyDemo(demoOwner));
        assertFalse(sessions.passwordOnlyDemo(-1));
    }


    @Test
    void seedsTwoIsolatedTenantsAndCanRunAgainWithoutDuplicatingData() {
        assertEquals(2, db.number("SELECT count(*) FROM demo_seeds"));
        assertEquals(2, db.number("SELECT count(DISTINCT business_id) FROM products"));
        assertEquals(2, db.number("SELECT count(DISTINCT business_id) FROM inventory"));
        assertEquals(8, db.number("SELECT count(*) FROM memberships WHERE business_id IN(SELECT business_id FROM demo_seeds)"));
        assertEquals(1, db.number("SELECT count(*) FROM product_fitments"));
        seed.run(new DefaultApplicationArguments());
        assertEquals(2, db.number("SELECT count(*) FROM products"));
    }
}
